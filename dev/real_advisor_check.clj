(ns real-advisor-check
  "Live-model verification of crm.llm-realmodel/real-advisor against the
  murakumo fleet main model (ADR-2607173300) -- closes the gap
  llm_realmodel.clj's own docstring admits: \"a real end-to-end call...
  has NEVER been exercised\". Mirrors cloud-itonami-isic-6399's
  dev/real_advisor_check.clj pattern.

  NOT part of the CI suite: needs a live api.murakumo.cloud token. Run:
    clojure -M:dev -m real-advisor-check <token>"
  (:require [langgraph.graph :as g]
            [crm.llm-realmodel :as realmodel]
            [crm.operation :as op]
            [crm.store :as store]))

(defn- check! [ok? msg]
  (if ok? (println "ok  " msg) (do (println "FAIL" msg) (System/exit 1))))

(defn -main [& [token]]
  (when-not token
    (println "usage: clojure -M:dev -m real-advisor-check <murakumo-bearer-token>")
    (System/exit 1))
  (let [pf (realmodel/preflight {:provider "anthropic"
                                 :url "https://api.murakumo.cloud/v1/messages"
                                 :model "murakumo-main"
                                 :api-key token})]
    (println "== preflight ==" (pr-str (dissoc pf :api-key?)) "| ok?" (:ok? pf)))
  (let [advisor (realmodel/real-advisor {:provider "anthropic"
                                         :url "https://api.murakumo.cloud/v1/messages"
                                         :model "murakumo-main"
                                         :api-key token
                                         :max-tokens 3000})
        db (store/seed-db)
        actor (op/build db {:advisor advisor})
        req {:op :opportunity/transition-stage
             :subject "opp-100"
             :opportunity-id "opp-100"
             :rep-id "rep-100"
             :to-stage :qualified
             :source {:class :call-notes :ref "2026-07-17 discovery call"}}
        result (g/run* actor {:request req :context {:actor-id "rep-1" :actor-role :sales-rep :phase 0}}
                       {:thread-id "e2e-check-1"})
        proposal (get-in result [:state :proposal])]
    (println "== real advisor call (murakumo-main / qwen3.6) ==")
    (println "  summary:   " (:summary proposal))
    (println "  rationale: " (:rationale proposal))
    (println "  effect:    " (:effect proposal))
    (println "  confidence:" (:confidence proposal))
    (println "  disposition:" (get-in result [:state :disposition]))
    (check! (some? proposal) "advisor returned a parseable proposal (not the parse-failure noop)")
    (check! (not= :noop (:effect proposal)) "proposal effect is not :noop (real EDN was parsed)")
    (println "\nALL CHECKS PASSED — crm.llm-realmodel/real-advisor verified end-to-end against a live model.")))
