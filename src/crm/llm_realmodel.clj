(ns crm.llm-realmodel
  "Real-model adapter for `crm.llm`'s Advisor protocol — the piece that was
  genuinely missing before this file existed: `src/crm/llm.cljc`'s
  RevOps-LLM was a SEALED/deterministic mock (`crm.llm/mock-advisor`,
  `crm.llm/infer`) with no way to point the actor at an actual language
  model. `crm.llm.cljc` already defines the seam this needed — a generic
  `crm.llm/llm-advisor` that wraps ANY `langchain.model/ChatModel` in the
  exact same `crm.llm/Advisor` protocol (`-advise`) the OperationActor
  graph (`crm.operation/build`) calls, with the identical proposal shape
  (`:summary`/`:rationale`/`:cites`/`:source`/`:effect`/`:value`/
  `:confidence`) parsed via `crm.llm/parse-proposal`. This file does NOT
  change that graph-facing contract at all — it only supplies a second,
  real implementation of `ChatModel` for `llm-advisor` to wrap.

  JVM-only (`.clj`, not `.cljc`) for the same reason `crm.http`/
  `crm.file-store` are: a real outbound HTTP call to an external model API
  needs a real HTTP client, and there is no portable 'open a TCP socket to
  the internet' primitive at the kotoba-wasm/clojurewasm/cljs/nbb tier
  this fleet prefers (CLAUDE.md's `.cljc`/`.kotoba` runtime-priority
  section). This is infrastructure glue over `crm.llm`'s already-portable
  domain logic, not a reimplementation of it.

  ## Convention: this org's own `ITO_MODEL_*` precedent, renamed per-actor

  `orgs/gftdcojp/cloud-itonami`'s `cloud_itonami.runtime` namespace
  already established exactly this shape for this same lineage/org:
  env-var-driven `ITO_MODEL_PROVIDER`/`ITO_MODEL_URL`/`ITO_MODEL`/
  `ITO_MODEL_API_KEY`, a JDK `HttpClient`-based `:http-fn`, an OpenAI-
  compatible + Anthropic adapter (both already implemented generically in
  `langchain.model`, not reimplemented here), and a `preflight` function
  that reports what's missing without ever attempting a live call. This
  file adapts that SAME shape — deliberately not inventing an incompatible
  new one — with `ISIC5820_`-prefixed env vars so multiple `cloud-itonami-
  isic-*` actors running on the same host/CI never collide on one another's
  model config:

    ISIC5820_MODEL_PROVIDER  \"openai\" (default) | \"anthropic\" | \"openclaw\"
                             (\"openclaw\" = any OpenAI-compatible endpoint
                             at a custom URL — self-hosted gateway, Ollama,
                             vLLM, etc. — same convention `cloud_itonami.
                             runtime`'s :openclaw/:hermes provider kind uses)
    ISIC5820_MODEL_URL       required for \"openclaw\" (no canonical default
                             endpoint); optional override for openai/
                             anthropic (defaults to the public API URL
                             `langchain.model` already hardcodes)
    ISIC5820_MODEL           chat model name (default: gpt-4o-mini for
                             openai/openclaw, claude-opus-4-8 for anthropic)
    ISIC5820_MODEL_API_KEY   the API key/bearer token. THE SOLE TRIGGER:
                             `crm.http` switches from the sealed mock
                             advisor to this real-model advisor if and only
                             if this is set and non-blank (see `crm.http`'s
                             `resolve-advisor!`).

  ## Honest, explicit gap this file does NOT close

  2026-07-17 update (ADR-2607173300): a real end-to-end call through
  `real-advisor` to a live model API IS now verified — see
  `dev/real_advisor_check.clj`, run against the murakumo fleet's
  `murakumo-main` alias (provider :anthropic, url
  https://api.murakumo.cloud/v1/messages, currently resolving to
  qwen3.6-35b-a3b). Both a bad-subject request (opportunity not found,
  governor correctly HOLDs) and a valid opp-100 transition (model
  correctly returns a well-formed :stage-transition-upsert proposal,
  parsed EDN, routed through the SAME actor graph as the mock advisor)
  were exercised live. Unit tests (`test/crm/llm_realmodel_test.clj`)
  still only exercise the adapter against a local http-kit stub — that
  remains the deterministic/CI-safe coverage; `dev/real_advisor_check.clj`
  is the live-model companion (same pattern as
  cloud-itonami-isic-6399/dev/real_advisor_check.clj), NOT part of the
  CI suite (needs a live token)."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [langchain.model :as model]
            [crm.llm :as llm])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.time Duration)))

(defn- env
  "Blank-safe `System/getenv` — a set-but-empty-string env var (a common
  CI/shell footgun, e.g. `FOO=` in a compose file) is treated the same as
  unset, not as a present-but-empty value."
  ([k]
   (let [v (System/getenv k)]
     (when-not (str/blank? v) v)))
  ([k default] (or (env k) default)))

(defn- nonblank [v] (when (and (string? v) (not (str/blank? v))) v))

(def known-providers
  "Providers `real-chat-model` can actually build. Anything else is a
  configuration error surfaced by `preflight`/`real-chat-model`, never
  silently coerced to a default."
  #{:openai :anthropic :openclaw})

(def providers-needing-url
  "Providers with no canonical default endpoint to fall back to —
  `:openclaw` is \"any OpenAI-compatible endpoint at a URL you supply\",
  so `ISIC5820_MODEL_URL` is required for it. `:openai`/`:anthropic` both
  have a public default URL already hardcoded in `langchain.model`."
  #{:openclaw})

(defn model-config
  "Resolves provider/url/model/api-key-presence from explicit `opts` first,
  then `ISIC5820_MODEL_*` env vars, then a provider-appropriate default.
  Never returns the api-key itself in the result map — only `:api-key?`
  (boolean) — so this map is always safe to log/print in full."
  ([] (model-config {}))
  ([{:keys [provider url api-key model]}]
   (let [provider (keyword (or (nonblank provider)
                               (env "ISIC5820_MODEL_PROVIDER")
                               "openai"))
         url (or (nonblank url) (env "ISIC5820_MODEL_URL"))
         api-key (or (nonblank api-key) (env "ISIC5820_MODEL_API_KEY"))
         model-name (or (nonblank model)
                        (env "ISIC5820_MODEL")
                        (if (= provider :anthropic) "claude-opus-4-8" "gpt-4o-mini"))]
     {:provider provider
      :url url
      :api-key? (boolean api-key)
      :model model-name})))

(defn preflight
  "Reports exactly what's configured/missing WITHOUT attempting any live
  network call — safe to run with zero credentials present (this sandbox
  has none) and always reports honestly rather than guessing. Returns
  `model-config`'s map plus `:ok?` and `:missing` (a vector of the
  `ISIC5820_MODEL_*` env var names still needed, in check order:
  unknown/unsupported provider, then a required-but-absent URL, then a
  missing API key)."
  ([] (preflight {}))
  ([opts]
   (let [{:keys [provider url api-key?] :as cfg} (model-config opts)
         missing (cond-> []
                   (not (known-providers provider))
                   (conj :ISIC5820_MODEL_PROVIDER)

                   (and (providers-needing-url provider) (str/blank? url))
                   (conj :ISIC5820_MODEL_URL)

                   (not api-key?)
                   (conj :ISIC5820_MODEL_API_KEY))]
     (assoc cfg :ok? (empty? missing) :missing missing))))

(defn jvm-http-fn
  "The `:http-fn` host capability `langchain.model/anthropic-model`/
  `openai-model` require: `(fn [{:keys [url method headers body]}]) ->
  {:status int :body string}`. Built on `java.net.http.HttpClient` — JDK
  built-in, zero extra dependency, same client `crm.http_test.clj` already
  uses to drive this actor's own HTTP server in tests."
  ([] (jvm-http-fn {}))
  ([{:keys [timeout-seconds] :or {timeout-seconds 180}}]
   (let [client (-> (HttpClient/newBuilder)
                    (.connectTimeout (Duration/ofSeconds timeout-seconds))
                    (.build))]
     (fn [{:keys [url method headers body]}]
       (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                         (.timeout (Duration/ofSeconds timeout-seconds)))
             builder (reduce-kv (fn [b k v] (.header b (str k) (str v))) builder headers)
             request (case method
                       :post (-> builder
                                 (.POST (HttpRequest$BodyPublishers/ofString (or body "")))
                                 (.build))
                       :get (-> builder .GET .build)
                       (throw (ex-info "crm.llm-realmodel/jvm-http-fn: unsupported HTTP method"
                                       {:method method})))
             resp (.send client request (HttpResponse$BodyHandlers/ofString))]
         {:status (.statusCode resp)
          :body (.body resp)})))))

(defn real-chat-model
  "Builds a `langchain.model/ChatModel` for a REAL model API from
  `ISIC5820_MODEL_*` env vars (or explicit `opts` overrides, same keys as
  `model-config`, plus `:http-fn` to inject a stub for testing — see
  `test/crm/llm_realmodel_test.clj`). Delegates the actual request/response
  wire shape entirely to `langchain.model/anthropic-model`/`openai-model`
  (both already generic, already tested in `kotoba-lang/langchain`) —
  this function only resolves config and picks which of those two to call.
  Throws (does not silently fall back) for any provider outside
  `known-providers`."
  ([] (real-chat-model {}))
  ([opts]
   (let [{:keys [provider url model]} (model-config opts)
         api-key (or (:api-key opts) (env "ISIC5820_MODEL_API_KEY"))
         common {:api-key api-key
                 :model model
                 :max-tokens (:max-tokens opts)
                 :http-fn (or (:http-fn opts) (jvm-http-fn opts))
                 :json-write json/write-str
                 :json-read #(json/read-str % :key-fn keyword)}]
     (case provider
       :anthropic
       (model/anthropic-model (cond-> common url (assoc :url url)))

       (:openai :openclaw)
       (model/openai-model (cond-> common url (assoc :url url)))

       (throw (ex-info (str "crm.llm-realmodel: unsupported ISIC5820_MODEL_PROVIDER " (pr-str provider)
                            " (supported: " (pr-str known-providers) ")")
                       {:provider provider :supported known-providers}))))))

(defn real-advisor
  "A real-model `crm.llm/Advisor` — satisfies the EXACT SAME protocol
  `crm.llm/mock-advisor` does (`-advise`, called by `crm.operation/build`'s
  `:advise` node with `(store request)` and expected to return a proposal
  map), so swapping this in changes nothing about how the OperationActor
  graph is wired or what shape it expects back. Built by wrapping
  `real-chat-model` in `crm.llm/llm-advisor`, which already exists in
  `crm.llm.cljc` for exactly this purpose (system prompt + fact-gathering +
  EDN proposal parsing all already implemented there, not duplicated
  here)."
  ([] (real-advisor {}))
  ([opts] (llm/llm-advisor (real-chat-model opts))))
