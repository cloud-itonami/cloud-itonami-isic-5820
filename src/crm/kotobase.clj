(ns crm.kotobase
  "A `crm.store/Store` backed by a live kotobase-server graph (kotobase.net),
  self-sovereign via this actor's OWN Ed25519 key — no owner hand-off, no
  shared secret. Same pattern as com-etzhayyim-tsumugu's tsumugu.cacao/
  tsumugu.kotoba (CLAUDE.md \"kotoba-server（kotobase.net）= actor が自分の鍵で
  CACAO を自己発行\"): the actor mints its own transact-scoped CACAO for
  `kotobase/db/<its-own-did>/<db-name>` and presents that, never a
  platform-held token. Faithful port of `tsumugu.cacao`'s canonical-graph/
  mint plumbing onto `cacao.core`/`ed25519.core` (org-chainagnostic-cacao /
  org-ietf-ed25519) instead of a hand-rolled JDK Ed25519 + CBOR
  implementation — this actor's seed is a raw 32-byte hex string (from
  kagi, see `load-seed-hex`), and `cacao.core/mint` accepts a raw seed
  directly, so there is no PKCS8/X.509 DER wrapping to hand-roll here the
  way tsumugu's JDK-KeyPair-based implementation needs.

  JVM-only (`.clj`, not `.cljc`) — same reason `crm.http`/`crm.file-store`
  are: this is I/O/crypto infrastructure glue over the already-portable
  `crm.store/Store` protocol, not a reimplementation of any
  governance/domain logic."
  (:require [clojure.string :as str]
            [ed25519.core :as ed]
            [cacao.core :as cacao]
            [langchain.kotoba-db :as kdb]
            [crm.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util UUID]))

(def default-db-name
  "This actor's kotobase.net tenant database name — the sales-pipeline +
  subscription-entitlement ledger. Combined with this actor's own DID (via
  `canonical-graph`) into the graph the edge derives + verifies on every
  tenant Datom write."
  "cloud-itonami-isic-5820")

;; ───────── graph CID (byte-identical port of kotobase.cid/tsumugu.cacao) ────
;; The edge (kotobase.net worker.cljc / kotobase.graph) recomputes this same
;; CIDv1/dag-cbor/sha2-256 of "kotobase/db/<did>/<db-name>" from the DID a
;; tenant Datom write's CACAO authenticates as — see this repo session's own
;; ADR (read-plane ACL fix, kotobase.proxy/scope-tenant-read) for the write-
;; side sibling this graph derivation must agree with byte-for-byte.

(def ^:private b32 "abcdefghijklmnopqrstuvwxyz234567")

(defn- sha256 ^bytes [^bytes data]
  (.digest (MessageDigest/getInstance "SHA-256") data))

(defn- base32-lower-no-pad [^bytes data]
  (let [sb (StringBuilder.)
        {:keys [bits value]}
        (reduce
         (fn [{:keys [bits value]} b]
           (let [b (bit-and (int b) 0xff)
                 value (bit-or (bit-shift-left value 8) b)
                 bits (+ bits 8)]
             (loop [bits bits value value]
               (if (>= bits 5)
                 (do (.append sb (.charAt b32 (bit-and (unsigned-bit-shift-right value (- bits 5)) 31)))
                     (recur (- bits 5) value))
                 {:bits bits :value value}))))
         {:bits 0 :value 0}
         data)]
    (when (pos? bits)
      (.append sb (.charAt b32 (bit-and (bit-shift-left value (- 5 bits)) 31))))
    (.toString sb)))

(defn graph-cid-from-name
  "CIDv1/dag-cbor/sha2-256 of `name`, multibase 'b'-prefixed base32-lower —
  matches `kotobase.graph/graph-cid-from-name` (net-kotobase clj-edge) and
  `tsumugu.cacao/graph-cid-from-name` byte-for-byte."
  [^String name]
  (let [hash (sha256 (.getBytes name "UTF-8"))
        cid (byte-array (concat [(unchecked-byte 0x01) (unchecked-byte 0x71)
                                  (unchecked-byte 0x12) (unchecked-byte 0x20)]
                                 (seq hash)))]
    (str "b" (base32-lower-no-pad cid))))

(defn canonical-graph
  "This actor's deterministic graph CID for `db-name` under `did` — the edge
  recomputes exactly this from the DID + db-name on every tenant write."
  [did db-name]
  (graph-cid-from-name (str "kotobase/db/" did "/" db-name)))

;; ───────── seed / identity ───────────────────────────────────────────────

(defn load-seed-hex
  "Reads this actor's 32-byte Ed25519 seed (64 hex chars) from
  `$ISIC5820_KOTOBASE_SEED_HEX`, or nil if unset/blank. The seed itself is
  never generated or persisted here — provisioning (kagi mint, `bin/kagi
  add`) is an operator action, not something this actor does to itself on
  boot; this fn only reads what the caller already put in the environment."
  []
  (let [v (System/getenv "ISIC5820_KOTOBASE_SEED_HEX")]
    (when (and v (seq (str/trim v))) (str/trim v))))

(defn identity-from-seed-hex
  "Hex seed → {:seed-hex :did :graph}. `:seed` (raw bytes) is NOT included in
  the returned map — callers that need to mint (`mint-cacao`) re-derive it
  from `:seed-hex` at the point of use, so a stray `(pr-str identity)` or
  log line can never leak raw key bytes even by accident."
  ([seed-hex] (identity-from-seed-hex seed-hex default-db-name))
  ([seed-hex db-name]
   (let [seed (ed/unhex seed-hex)
         did (ed/did-key-from-seed seed)]
     {:seed-hex seed-hex :did did :graph (canonical-graph did db-name)})))

;; ───────── CACAO mint ─────────────────────────────────────────────────────

(defn mint-cacao
  "Self-mint a base64 CACAO scoped to `graph` (default: `:cap/transact` on
  this actor's own db). `identity` is `identity-from-seed-hex`'s return map
  (`:seed-hex` + `:did` + `:graph`); `aud` is the kotobase-server base URL
  this CACAO will be presented to. `ttl-sec` default 3600 (1h) — short-
  lived, re-minted per `kotobase-store` construction rather than cached
  across restarts, since minting is cheap (pure local signing, no
  round-trip)."
  ([identity aud] (mint-cacao identity aud {}))
  ([{:keys [seed-hex graph]} aud {:keys [ttl-sec cap] :or {ttl-sec 3600 cap :cap/transact}}]
   (let [seed (ed/unhex seed-hex)
         now (Instant/now)
         op (case cap :cap/read "datom:read" :cap/transact "datom:transact" "datom:transact")
         resources [(str "kotoba://op/" op) (str "kotoba://graph/" graph)
                    ;; The live edge's auth gate (kotobase-cf-wasm/src/
                    ;; kotobase_cf_wasm/auth.cljs validate-payload) hardcodes
                    ;; a single required-capability constant regardless of
                    ;; the actual op/scope resources above:
                    ;; "kotoba://can/kotobase:pin" -- a resource string this
                    ;; namespace never otherwise mints or means (this actor
                    ;; is not "pinning" anything). Every CACAO minted here
                    ;; must include it or every datomic.transact/q/pull call
                    ;; is rejected 401, confirmed directly against
                    ;; kotobase.net, 2026-07-18. This is a live server-side
                    ;; constraint this client-side ns cannot route around;
                    ;; tracked as a known gap, not a design choice of this
                    ;; actor's own capability model.
                    "kotoba://can/kotobase:pin"]]
     (:cacao-b64
      (cacao/mint {:seed seed
                   :aud aud
                   :nonce (str (UUID/randomUUID))
                   :iat (str now)
                   :exp (str (.plusSeconds now ttl-sec))
                   :resources resources})))))

;; ───────── host-caps http-fn (JDK HttpClient, no dependency) ───────────────

(defn jvm-http-fn
  "host-caps :http-fn backed by the JDK HTTP client — faithful port of
  `tsumugu.kotoba/jvm-http-fn`."
  [{:keys [url method headers body]}]
  (let [b (HttpRequest/newBuilder (URI/create url))]
    (doseq [[k v] headers] (.header b ^String k ^String v))
    (let [req (-> b (.method (str/upper-case (name (or method :post)))
                            (if body
                              (HttpRequest$BodyPublishers/ofString ^String body)
                              (HttpRequest$BodyPublishers/noBody)))
                  (.build))
          resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp)})))

;; ───────── Store constructor ────────────────────────────────────────────────

(defn kotobase-store
  "A `crm.store/Store` (via `crm.store/store-with-api`) backed by a live
  kotobase-server graph, self-sovereign via this actor's own Ed25519 key.

  opts:
    :url          kotobase-server base URL (default \"https://kotobase.net\")
    :seed-hex     32-byte hex Ed25519 seed (default: `load-seed-hex`, i.e.
                  $ISIC5820_KOTOBASE_SEED_HEX)
    :db-name      tenant database name (default `default-db-name`)
    :json-write   injected JSON encode fn, e.g. `clojure.data.json/write-str`
    :json-read    injected JSON decode fn (keywordize-keys), e.g.
                  `#(clojure.data.json/read-str % :key-fn keyword)`
    :http-fn      optional override (defaults to `jvm-http-fn`)
    :cap          :cap/transact (default) or :cap/read
    :ttl-sec      CACAO lifetime in seconds (default 3600)

  Throws if no seed is available (fail-closed — this Store must never
  silently fall back to an unauthenticated or misconfigured connection).
  Does NOT seed demo data (see `crm.store/store-with-api`).

  A fresh CACAO is minted for EVERY `:transact!` call, not once at
  construction time and reused: kotobase-server's nonce-replay protection
  (`kotobase_cf_wasm.auth/nonce-seen?`) rejects a second `datomic.transact`
  presenting the same CACAO's nonce (`\"CACAO nonce already used\"`, 401) —
  confirmed directly, 2026-07-18: a Store built with one static CACAO
  succeeded on its first write and 401'd on its second. Reads (`:q`/
  `:pull`/`:entid`) are unauthenticated on the live edge (any graph-CID
  holder can read — see `kotobase.proxy/scope-tenant-read`'s docstring),
  so they reuse a single conn/CACAO safely; only the write path needs a
  fresh mint per call."
  [{:keys [url seed-hex db-name json-write json-read http-fn cap ttl-sec]
    :or {url "https://kotobase.net" db-name default-db-name}}]
  (let [seed-hex (or seed-hex (load-seed-hex))
        _ (when-not seed-hex
            (throw (ex-info "crm.kotobase/kotobase-store: no seed — set $ISIC5820_KOTOBASE_SEED_HEX (kagi get cloud-itonami-isic-5820-kotobase-seed)"
                            {})))
        id (identity-from-seed-hex seed-hex db-name)
        host-caps {:http-fn (or http-fn jvm-http-fn)
                   :json-write json-write :json-read json-read}
        raw-api (kdb/kotoba-api host-caps)
        write-conn! (fn []
                      (kdb/kotoba-conn* url db-name
                                        {:cacao (mint-cacao id url {:cap :cap/transact :ttl-sec (or ttl-sec 3600)})
                                         :did (:did id) :graph (:graph id)}))
        read-conn (kdb/kotoba-conn* url db-name
                                    {:cacao (mint-cacao id url {:cap (or cap :cap/read) :ttl-sec (or ttl-sec 3600)})
                                     :did (:did id) :graph (:graph id)})
        api {:transact! (fn [_conn tx-data] ((:transact! raw-api) (write-conn!) tx-data))
             :db identity
             :q (fn [query _conn & inputs] (apply (:q raw-api) query read-conn inputs))
             :pull (fn [_conn pattern eid] ((:pull raw-api) read-conn pattern eid))
             :entid (fn [_conn eid] ((:entid raw-api) read-conn eid))}]
    (store/store-with-api api read-conn)))
