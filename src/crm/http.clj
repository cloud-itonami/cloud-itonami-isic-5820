(ns crm.http
  "Minimal, real HTTP service layer over the existing `crm.operation` /
  `crm.policy` / `crm.dashboard` actor graph — the first step toward
  running this actor as a live, network-callable process instead of a
  Clojure library invoked only via `clojure -M:dev:run` or test code.

  JVM-only (`.clj`, not `.cljc`) is the correct choice here per this
  fleet's runtime-priority convention (CLAUDE.md's `.cljc`/`.kotoba`
  section): there is no portable 'bind a TCP socket and run an HTTP
  server' primitive at the kotoba-wasm/clojurewasm/cljs/nbb level, and
  this file is infrastructure glue (a server binding) over app logic
  that already lives in portable `.cljc` (`crm.operation`, `crm.policy`,
  `crm.dashboard`, …) — it reimplements NONE of that logic, it only
  adapts HTTP requests/responses onto it.

  Endpoints (full shapes in docs/api.md):
    GET  /            no auth  — actor info + links
    GET  /health       no auth — liveness (+ store reachability)
    POST /propose      auth    — RevOps-LLM proposal -> governed decision
                                 (thin adapter over `crm.operation/build`'s
                                 compiled StateGraph: advise -> govern ->
                                 decide -> commit/hold/escalate)
    GET  /dashboard     auth    — book-wide pipeline/revenue rollup,
                                 RBAC-gated via `crm.policy/check`'s
                                 existing `:pipeline/dashboard-query`
                                 rule (never bypassed/reimplemented here)

  Auth: bearer token (`Authorization: Bearer <token>`) compared against
  a token supplied at server-start time (from `$ISIC5820_API_TOKEN` when
  started via `-main`/`clojure -M:serve`). FAIL CLOSED: `start-server!`
  refuses to start at all if given a blank/nil token, and `-main` exits
  1 without starting anything if the env var is unset — there is no
  'runs with auth disabled' path. See docs/api.md for the full contract,
  and its explicit honest-scope statement (single-process, single-tenant,
  no TLS termination, no rate limiting)."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [org.httpkit.server :as httpkit]
            [ring.middleware.params :refer [wrap-params]]
            [langgraph.graph :as g]
            [crm.store :as store]
            [crm.file-store :as file-store]
            [crm.operation :as operation]
            [crm.policy :as policy]
            [crm.dashboard :as dashboard])
  (:gen-class))

(def actor-name "cloud-itonami-isic-5820")
(def isic-code "5820")
(def service-version "0.1.0")
(def default-port 8080)

;; ───────────────────────── JSON encode/decode ─────────────────────────
;; clojure.data.json's default keyword encoding drops namespaces (writes
;; (name kw)); this actor's domain vocabulary is namespaced keywords
;; (:opportunity/transition-stage, :tier/pro, …), so we walk values
;; ourselves before encoding to preserve the namespace instead of
;; silently truncating it.

(defn- kw->str [k] (subs (str k) 1))

(defn- ->json-safe [v]
  (cond
    (keyword? v)    (kw->str v)
    (map? v)        (into {} (map (fn [[k v]] [(if (keyword? k) (kw->str k) k) (->json-safe v)])) v)
    (set? v)        (mapv ->json-safe v)
    (sequential? v) (mapv ->json-safe v)
    :else           v))

(defn- write-json [v] (json/write-str (->json-safe v)))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body (write-json body)})

(defn- read-body-json
  "Reads+parses the request body as JSON with string->keyword keys.
  Returns `::parse-error` on invalid/absent JSON so callers can 400
  instead of 500ing on a malformed body."
  [req]
  (try
    (if-let [b (:body req)]
      (let [s (slurp b)]
        (if (str/blank? s) {} (json/read-str s :key-fn keyword)))
      {})
    (catch Exception _ ::parse-error)))

;; ───────────────────────── auth ─────────────────────────

(defn- bearer-token [req]
  (when-let [h (get-in req [:headers "authorization"])]
    (when (str/starts-with? h "Bearer ")
      (str/trim (subs h (count "Bearer "))))))

(defn- authorized? [req token]
  (and (some? token) (not (str/blank? token))
       (= token (bearer-token req))))

;; ───────────────────────── request coercion ─────────────────────────
;; JSON has no keyword type. This actor's request/proposal maps are
;; already keyword-keyed 1:1 with the JSON field names we document in
;; docs/api.md (op, subject, opportunity-id, to-stage, rep-id, …) —
;; `json/read-str :key-fn keyword` gets keys for free. Only VALUES that
;; must be keywords (not strings) in the existing crm.operation/
;; crm.policy/crm.llm code are coerced explicitly here, nothing else —
;; this is a thin adapter, not a schema/validation layer.

(defn- kw-val [v] (when (some? v) (if (keyword? v) v (keyword (str v)))))

(defn- ns-kw-val
  "\"opportunity/transition-stage\" -> :opportunity/transition-stage,
  \"tier/pro\" -> :tier/pro. A value with no '/' becomes a simple keyword."
  [v]
  (when (some? v)
    (if (keyword? v)
      v
      (let [s (str v)
            [a b] (str/split s #"/" 2)]
        (if b (keyword a b) (keyword s))))))

(defn- coerce-source [src] (when (map? src) (update src :class kw-val)))

(defn- coerce-request
  [m]
  (cond-> m
    (contains? m :op)                    (update :op ns-kw-val)
    (contains? m :to-stage)              (update :to-stage kw-val)
    (contains? m :activate-feature-tier) (update :activate-feature-tier ns-kw-val)
    (contains? m :disputed-field)        (update :disputed-field kw-val)
    (contains? m :claim)                 (update :claim #(if (string? %) (kw-val %) %))
    (contains? m :source)                (update :source coerce-source)))

(defn- coerce-context
  [m]
  (cond-> m
    (contains? m :actor-role) (update :actor-role kw-val)))

;; ───────────────────────── /propose ─────────────────────────

(defn- propose-decision
  "Runs `request`/`context` through the EXISTING OperationActor graph
  (`actor`, built once at server-start via `crm.operation/build`) — this
  function contains no governance logic of its own, it only shapes the
  graph's result into an HTTP response.

  One HTTP call = one fresh thread-id = one graph run. A `:commit`/
  `:hold` result is final. An `:escalate` result means the graph
  interrupted before `:request-approval` (human-in-the-loop) — there is
  no HTTP endpoint yet to submit that approval/rejection (out of scope
  for this first HTTP layer, see docs/api.md), so it's surfaced as 202
  with the thread-id and reason rather than silently blocking or 500ing."
  [actor request context]
  (let [thread-id (str (java.util.UUID/randomUUID))
        res (g/run* actor {:request request :context context} {:thread-id thread-id})
        state (:state res)
        disposition (:disposition state)]
    (case (:status res)
      :interrupted
      (json-response 202
        {:decision    "escalated"
         :op          (:op request)
         :subject     (:subject request)
         :thread-id   thread-id
         :reason      (-> state :audit last :reason)
         :confidence  (-> state :verdict :confidence)
         :note        (str "Escalated for human approval; this HTTP layer does not yet "
                            "expose an approval/rejection endpoint (see docs/api.md).")})

      ;; :done
      (case disposition
        :commit
        (json-response 200
          {:decision "committed"
           :op       (:op request)
           :subject  (:subject request)
           :record   (:record state)})

        :hold
        (json-response 200
          {:decision   "held"
           :op         (:op request)
           :subject    (:subject request)
           :violations (-> state :verdict :violations)
           :confidence (-> state :verdict :confidence)})

        (json-response 500 {:error "unexpected disposition" :disposition disposition})))))

;; ───────────────────────── /dashboard ─────────────────────────

(defn- dashboard-response
  "RBAC is enforced via the SAME `crm.policy/check` rule this actor
  already applies to any other `:pipeline/dashboard-query` caller
  (`crm.dashboard`'s own ns docstring: it is a pure aggregation layer
  over an ALREADY-authorized `db` and never re-checks permission
  itself) — this function does not reimplement or bypass that gate, it
  only routes the HTTP caller's role through it and 403s when the
  verdict is not ok."
  [store actor-role as-of-date]
  (let [verdict (policy/check {:op :pipeline/dashboard-query :subject "book-wide"}
                               {:actor-id "http-caller" :actor-role actor-role}
                               {:confidence 1.0}
                               store)]
    (if (:ok? verdict)
      (json-response 200 (dashboard/render store as-of-date))
      (json-response 403 {:error "forbidden" :violations (:violations verdict)}))))

;; ───────────────────────── root/info ─────────────────────────

(defn- root-info []
  {:actor     actor-name
   :isic-code isic-code
   :version   service-version
   :links     {:health    "/health"
               :propose   "/propose"
               :dashboard "/dashboard"
               :api-docs  "docs/api.md"}})

;; ───────────────────────── handler ─────────────────────────

(defn make-handler
  "Builds the Ring handler. `store` and `actor` (a compiled
  `crm.operation/build` graph over `store`) are injected so tests/callers
  control the backend; `token` is the bearer token every protected
  request must present."
  [{:keys [store actor token]}]
  (fn [{:keys [request-method uri params] :as req}]
    (try
      (cond
        (and (= :get request-method) (= "/" uri))
        (json-response 200 (root-info))

        (and (= :get request-method) (= "/health" uri))
        (let [reachable? (try (store/all-reps store) true (catch Exception _ false))]
          (json-response (if reachable? 200 503)
                          {:status (if reachable? "ok" "degraded")
                           :store  (if reachable? "reachable" "unreachable")}))

        (and (= :post request-method) (= "/propose" uri))
        (if-not (authorized? req token)
          (json-response 401 {:error "unauthorized"})
          (let [body (read-body-json req)]
            (cond
              (= body ::parse-error)
              (json-response 400 {:error "invalid JSON body"})

              (not (map? body))
              (json-response 400 {:error "body must be a JSON object"})

              :else
              (let [request (coerce-request (dissoc body :context))
                    context (coerce-context (get body :context {}))]
                (if (nil? (:op request))
                  (json-response 400 {:error "missing required field: op"})
                  (propose-decision actor request context))))))

        (and (= :get request-method) (= "/dashboard" uri))
        (if-not (authorized? req token)
          (json-response 401 {:error "unauthorized"})
          (let [role  (kw-val (get params "role"))
                year  (try (some-> (get params "year") Integer/parseInt) (catch Exception _ ::bad))
                month (try (some-> (get params "month") Integer/parseInt) (catch Exception _ ::bad))]
            (cond
              (nil? role)
              (json-response 400 {:error "missing required query param: role"})

              (or (nil? year) (nil? month) (= year ::bad) (= month ::bad))
              (json-response 400 {:error "missing/invalid required query params: year, month"})

              :else
              (dashboard-response store role {:year year :month month}))))

        :else
        (json-response 404 {:error "not found"}))
      (catch Exception e
        (json-response 500 {:error (or (ex-message e) (str e))})))))

;; ───────────────────────── server lifecycle ─────────────────────────

(defn start-server!
  "Starts the real HTTP server. `store` — any `crm.store/Store`
  (`MemStore`, `crm.store/DatomicStore`, or `crm.file-store/FileStore` —
  see `-main`'s docstring for which of these actually survives a process
  restart); `port` — TCP port (default `default-port`); `token` —
  the bearer token EVERY protected request must present, and MUST be a
  non-blank string. FAIL CLOSED: throws (refuses to start) if `token` is
  blank/nil rather than starting with auth silently disabled.

  Returns the `org.httpkit.server.HttpServer`; use
  `org.httpkit.server/server-port` to read the actual bound port
  (useful with `:port 0` for tests) and
  `org.httpkit.server/server-stop!` to stop it."
  [{:keys [store port token] :or {port default-port}}]
  (when (str/blank? token)
    (throw (ex-info (str "ISIC5820_API_TOKEN (or explicit `token`) must be a non-blank "
                          "value — refusing to start crm.http with auth disabled")
                     {})))
  (let [actor (operation/build store)
        handler (-> (make-handler {:store store :actor actor :token token})
                    wrap-params)]
    (httpkit/run-server handler {:port port :legacy-return-value? false})))

(defn- warn-ephemeral-store!
  "Prints a loud, unmissable stderr warning that `-main` is about to run
  against an ephemeral (process-lifetime-only) store. There is
  deliberately no quiet/default path into this mode — see `resolve-store!`."
  []
  (binding [*out* *err*]
    (println "WARNING: ISIC5820_STORE_FILE is not set — running against an"
             "EPHEMERAL in-memory store (crm.store/seed-db). ALL STATE"
             "(opportunities, subscriptions, accounts, reps, the audit"
             "ledger) WILL BE LOST when this process exits or restarts.")
    (println "WARNING: do not use this mode for real operation. Set"
             "ISIC5820_STORE_FILE=/path/to/db.edn to run against a"
             "disk-durable store instead (see docs/api.md's Persistence"
             "section).")))

(defn- resolve-store!
  "Picks the `Store` backend for `-main` from environment configuration.

    $ISIC5820_STORE_FILE — if set, a disk-durable `crm.file-store/FileStore`
                            at that path: loads existing state if the file
                            is already there, otherwise seeds it with the
                            same demo dataset `seed-db` uses and writes
                            that as the first snapshot. Every mutating
                            call persists a fresh snapshot to that path —
                            this is the ONLY backend wired here that
                            survives a process restart, and it has been
                            verified end-to-end (real process start ->
                            commit over real HTTP -> kill -> restart ->
                            data still there), not just unit-tested.

  If unset, falls back to `crm.store/seed-db` (ephemeral, in-memory,
  discarded on exit) and prints a WARNING to stderr via
  `warn-ephemeral-store!` so an operator can never end up running without
  persistence silently/by accident.

  NOTE, deliberately NOT wired here: `crm.store/datomic-store`
  (`DatomicStore`). Despite the name, as implemented in this repo today
  it provides NO durability beyond `MemStore` — its constructor
  (`(langchain.db/create-conn schema)`) is a plain in-process atom with no
  connection URI/socket/file, so selecting it here under a
  durability-implying env var (e.g. an `ISIC5820_DATOMIC_URI`) would be
  exactly the 'fake persistence' this fix is supposed to remove, not add.
  Making `DatomicStore` genuinely durable needs `crm.store` refactored to
  accept an injected `:db-api` (see `langchain.db/api` /
  `langchain.kotoba-db/kotoba-api`) pointed at a real Datomic Local or a
  live kotoba-server pod — real infrastructure this entry point does not
  have in this environment. See `crm.file-store`'s ns docstring and
  docs/api.md's Persistence section for the full explanation."
  []
  (if-let [path (System/getenv "ISIC5820_STORE_FILE")]
    (file-store/file-store! path)
    (do (warn-ephemeral-store!)
        (store/seed-db))))

(defn -main
  "Entry point for `clojure -M:serve`. Reads:
    $ISIC5820_API_TOKEN — REQUIRED. If unset/blank, prints a fatal error
                          to stderr and exits 1 WITHOUT starting the
                          server (fail closed — no 'runs with no auth'
                          fallback).
    $ISIC5820_HTTP_PORT — optional, default `default-port` (8080).
    $ISIC5820_STORE_FILE — optional. See `resolve-store!`: if set, runs
                          against a disk-durable `crm.file-store/FileStore`
                          at that path (survives restart); if unset, runs
                          against an ephemeral `crm.store/seed-db` and
                          prints a stderr WARNING that state will be lost
                          on exit."
  [& _]
  (let [token (System/getenv "ISIC5820_API_TOKEN")
        port  (if-let [p (System/getenv "ISIC5820_HTTP_PORT")]
                (Integer/parseInt p)
                default-port)]
    (if (str/blank? token)
      (do (binding [*out* *err*]
            (println "FATAL: ISIC5820_API_TOKEN is not set (or blank)."
                     "Refusing to start crm.http with auth disabled."))
          (System/exit 1))
      (let [store (resolve-store!)
            srv (start-server! {:store store :port port :token token})]
        (println (str "crm.http listening on :" (httpkit/server-port srv)
                       " (actor=" actor-name " isic=" isic-code
                       " version=" service-version ")"))
        (httpkit/server-join srv)))))
