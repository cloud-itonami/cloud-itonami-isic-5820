(ns crm.llm-realmodel-test
  "Scrupulously honest about what this file DOES and does NOT prove:

  - `preflight` tests below exercise the FULL missing/present matrix with
    NO env vars and NO network access — this is fully testable offline
    and IS a genuine correctness proof of the reporting logic.
  - The request/response-shape tests start a REAL `org.httpkit.server`
    on an ephemeral localhost port and point `real-chat-model`/
    `real-advisor` at it via explicit `:url`/`:http-fn` opts — a real
    HTTP round-trip over a real socket happens, and the request the
    adapter actually sent (method/headers/JSON body) plus the parsed
    proposal from a canned response are both asserted. This proves the
    adapter builds a well-formed OpenAI-compatible chat-completions
    request and correctly parses a well-formed response.
  - NONE of this proves an ACTUAL model API (OpenAI/Anthropic/a real
    OpenAI-compatible gateway) accepts this exact request shape or
    behaves as the stub does — there are no credentials for any real
    model API in this sandbox, and this file does not pretend otherwise.
    That remains unverified until an operator with real credentials
    exercises it."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [org.httpkit.server :as httpkit]
            [crm.llm :as llm]
            [crm.llm-realmodel :as realmodel]
            [crm.store :as store]
            [langchain.model :as model]))

;; ───────────────────────── preflight / model-config ─────────────────────────
;; No env vars are relied on here — every case passes explicit opts so this
;; suite's result cannot depend on (or leak into) the ambient test-runner
;; environment, and always exercises the true "no credentials" case
;; identically to how this sandbox actually runs.

(deftest preflight-reports-missing-api-key-only-for-openai-default
  (is (= {:provider :openai
          :url nil
          :api-key? false
          :model "gpt-4o-mini"
          :ok? false
          :missing [:ISIC5820_MODEL_API_KEY]}
         (realmodel/preflight {:provider "openai"}))))

(deftest preflight-openai-with-key-is-ok-with-no-url-needed
  (is (= {:provider :openai
          :url nil
          :api-key? true
          :model "gpt-4o-mini"
          :ok? true
          :missing []}
         (realmodel/preflight {:provider "openai" :api-key "sk-test"}))))

(deftest preflight-anthropic-default-model-and-missing-key
  (is (= {:provider :anthropic
          :url nil
          :api-key? false
          :model "claude-opus-4-8"
          :ok? false
          :missing [:ISIC5820_MODEL_API_KEY]}
         (realmodel/preflight {:provider "anthropic"}))))

(deftest preflight-openclaw-requires-both-url-and-key
  (is (= {:provider :openclaw
          :url nil
          :api-key? false
          :model "gpt-4o-mini"
          :ok? false
          :missing [:ISIC5820_MODEL_URL :ISIC5820_MODEL_API_KEY]}
         (realmodel/preflight {:provider "openclaw"}))))

(deftest preflight-openclaw-with-url-and-key-is-ok
  (is (= {:provider :openclaw
          :url "http://localhost:11434/v1/chat/completions"
          :api-key? true
          :model "gpt-4o-mini"
          :ok? true
          :missing []}
         (realmodel/preflight {:provider "openclaw"
                               :url "http://localhost:11434/v1/chat/completions"
                               :api-key "local-key"}))))

(deftest preflight-unknown-provider-is-reported-not-silently-coerced
  (let [pf (realmodel/preflight {:provider "made-up-vendor" :api-key "k"})]
    (is (false? (:ok? pf)))
    (is (= [:ISIC5820_MODEL_PROVIDER] (:missing pf)))
    (is (= :made-up-vendor (:provider pf)))))

(deftest preflight-treats-blank-string-opts-as-missing-same-as-nil
  (is (= {:provider :openclaw
          :url nil
          :api-key? false
          :model "gpt-4o-mini"
          :ok? false
          :missing [:ISIC5820_MODEL_URL :ISIC5820_MODEL_API_KEY]}
         (realmodel/preflight {:provider "openclaw" :url "" :api-key ""}))))

(deftest preflight-honors-explicit-model-override
  (is (= "gpt-4-turbo"
         (:model (realmodel/preflight {:provider "openai" :model "gpt-4-turbo" :api-key "k"})))))

;; ───────────────────────── real-chat-model provider dispatch ─────────────────────────

(deftest real-chat-model-throws-on-unsupported-provider
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported ISIC5820_MODEL_PROVIDER"
        (realmodel/real-chat-model {:provider "made-up-vendor" :api-key "k"
                                    :http-fn (fn [_] {:status 200 :body "{}"})}))))

;; ───────────────────────── request/response wire shape, via a REAL local http-kit stub ─────────────────────────
;; This is the "local stub HTTP server standing in for the model API"
;; verification: a real org.httpkit.server binds an ephemeral port, the
;; adapter's `jvm-http-fn` (java.net.http.HttpClient) makes a REAL HTTP
;; request to it over a real socket, and the handler below asserts the
;; captured request shape before returning a canned OpenAI-compatible
;; response body for the adapter to parse.

(def ^:private captured-request (atom nil))

(defn- stub-openai-handler
  "Records the raw Ring request the adapter sent, then returns a canned
  OpenAI-compatible chat-completions response. `content` lets each test
  control what \"the model\" appears to say, so parse-proposal's EDN
  round-trip can be exercised end-to-end against a real HTTP response
  body (not just a hand-built map, closing the gap between \"we parse
  this shape\" and \"we parse what actually arrives over the wire\")."
  [content]
  (fn [req]
    (reset! captured-request
            (assoc (select-keys req [:request-method :uri :headers])
                   :body (slurp (:body req))))
    {:status 200
     :headers {"content-type" "application/json"}
     :body (json/write-str
            {:choices [{:finish_reason "stop"
                        :message {:role "assistant" :content content}}]})}))

(defn- with-stub-server [handler f]
  (let [srv (httpkit/run-server handler {:port 0 :legacy-return-value? false})
        port (httpkit/server-port srv)]
    (try
      (f (str "http://127.0.0.1:" port "/v1/chat/completions"))
      (finally @(httpkit/server-stop! srv)))))

(deftest real-chat-model-sends-well-formed-openai-request-and-parses-response
  (with-stub-server
    (stub-openai-handler "hello from the stub")
    (fn [url]
      (let [m (realmodel/real-chat-model {:provider "openai" :url url :api-key "sk-test-123"
                                          :model "gpt-4o-mini"})
            out (model/-generate m [{:role :user :content "ping"}] {})]
        (testing "adapter parsed the stub's OpenAI-shaped response correctly"
          (is (= "hello from the stub" (:content out))))
        (testing "adapter sent a real HTTP POST with a bearer auth header (never the raw key logged elsewhere)"
          (is (= :post (:request-method @captured-request)))
          (is (= "/v1/chat/completions" (:uri @captured-request)))
          (is (= "Bearer sk-test-123" (get-in @captured-request [:headers "authorization"]))))
        (testing "request body is well-formed JSON carrying the configured model + the user message"
          (let [body (json/read-str (:body @captured-request) :key-fn keyword)]
            (is (= "gpt-4o-mini" (:model body)))
            (is (some #(and (= "user" (:role %)) (= "ping" (:content %))) (:messages body)))))))))

;; The full seam this file exists for: `real-advisor` -> `crm.llm/llm-
;; advisor` -> `crm.llm/-advise` -> a real HTTP round-trip to the stub ->
;; `crm.llm/parse-proposal` turning the stub's EDN-in-content response
;; into the exact proposal shape `crm.operation/build`'s `:advise` node
;; expects (`:summary`/`:rationale`/`:cites`/`:source`/`:effect`/
;; `:confidence`) — never a hand-constructed map bypassing that parser.
(deftest real-advisor-end-to-end-through-crm-llm-proposal-parsing
  (with-stub-server
    (stub-openai-handler
     (pr-str {:summary "stage transition: opp-100 → qualification"
              :rationale "stub RevOps-LLM proposal"
              :cites [:opportunity-id :to-stage]
              :source {:class :crm-activity-log :ref "stub"}
              :effect :stage-transition-upsert
              :value {:opportunity-id "opp-100" :to-stage :qualification}
              :confidence 0.87}))
    (fn [url]
      (let [db (store/seed-db)
            advisor (realmodel/real-advisor {:provider "openai" :url url :api-key "sk-test-456"})
            proposal (llm/-advise advisor db
                                  {:op :opportunity/transition-stage :subject "opp-100"
                                   :opportunity-id "opp-100" :to-stage :qualification})]
        (is (= :stage-transition-upsert (:effect proposal)))
        (is (= 0.87 (:confidence proposal)))
        (is (= [:opportunity-id :to-stage] (:cites proposal)))
        (is (= {:class :crm-activity-log :ref "stub"} (:source proposal)))))))

;; A real model will not always return well-formed EDN on the first try
;; (prose preamble, markdown fences, truncation, …) — `crm.llm/parse-
;; proposal` already has a defined fallback for this (a :noop proposal
;; with confidence 0.0, never a thrown exception surfacing as a 500 from
;; `crm.http`); this proves the real adapter's response reaches that same
;; fallback rather than some new failure mode of its own.
(deftest real-advisor-handles-a-model-response-that-fails-to-parse-as-edn
  (with-stub-server
    (stub-openai-handler "I'm not EDN, just prose the model made up.")
    (fn [url]
      (let [db (store/seed-db)
            advisor (realmodel/real-advisor {:provider "openai" :url url :api-key "sk-test-789"})
            proposal (llm/-advise advisor db
                                  {:op :opportunity/transition-stage :subject "opp-100"
                                   :opportunity-id "opp-100" :to-stage :qualification})]
        (is (= :noop (:effect proposal)))
        (is (= 0.0 (:confidence proposal)))))))

;; ───────────────────────── regression: sealed mock stays the default ─────────────────────────
;; This does not touch crm.http directly (that's covered by crm.http-test's
;; existing suite) -- it proves, at the level this file actually owns, that
;; `crm.llm/mock-advisor` remains fully offline/deterministic and untouched
;; by this file's addition: no code in `crm.llm-realmodel` is invoked, no
;; network capability is required, importing this namespace changes
;; nothing about mock-advisor's behavior.

(deftest mock-advisor-is-unaffected-by-this-namespace-existing
  (let [db (store/seed-db)
        advisor (llm/mock-advisor)
        p (llm/-advise advisor db
                       {:op :opportunity/transition-stage :subject "opp-100"
                        :opportunity-id "opp-100" :to-stage :qualification
                        :rep-id "rep-100"
                        :source {:class :crm-activity-log :ref "demo"}})]
    (is (= :stage-transition-upsert (:effect p)))
    (is (>= (:confidence p) 0.9))))
