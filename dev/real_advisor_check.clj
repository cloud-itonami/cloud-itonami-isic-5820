(ns real-advisor-check
  "Live-model verification of crm.llm-realmodel/real-advisor against the
  murakumo fleet main model (ADR-2607173100/2607173300) -- extended
  (ADR-2607173400) to cover the new :lead/qualify / :lead/convert ops.
  Mirrors cloud-itonami-isic-6399's dev/real_advisor_check.clj pattern.

  NOT part of the CI suite: needs a live api.murakumo.cloud token. Run:
    clojure -M:dev -m real-advisor-check <token>"
  (:require [langgraph.graph :as g]
            [crm.llm-realmodel :as realmodel]
            [crm.operation :as op]
            [crm.store :as store]))

(defn- check! [ok? msg]
  (if ok? (println "ok  " msg) (do (println "FAIL" msg) (System/exit 1))))

(defn- run-scenario [actor tid req ctx]
  (let [result (g/run* actor {:request req :context ctx} {:thread-id tid})
        proposal (get-in result [:state :proposal])]
    (println "  op:         " (:op req))
    (println "  summary:    " (:summary proposal))
    (println "  effect:     " (:effect proposal))
    (println "  confidence: " (:confidence proposal))
    (println "  disposition:" (get-in result [:state :disposition]))
    (check! (some? proposal) (str (:op req) ": advisor returned a parseable proposal"))
    (check! (not= :noop (:effect proposal)) (str (:op req) ": effect is not :noop (real EDN parsed)"))
    proposal))

(defn -main [& [token]]
  (when-not token
    (println "usage: clojure -M:dev -m real-advisor-check <murakumo-bearer-token>")
    (System/exit 1))
  (let [advisor (realmodel/real-advisor {:provider "anthropic"
                                         :url "https://api.murakumo.cloud/v1/messages"
                                         :model "murakumo-main"
                                         :api-key token
                                         :max-tokens 3000})
        db (store/seed-db)
        actor (op/build db {:advisor advisor})
        ctx {:actor-id "rep-100" :actor-role :rep :phase 0}]
    (println "== real advisor / :lead/qualify (lead-100, new -> working) ==")
    (run-scenario actor "e2e-lead-qualify"
                  {:op :lead/qualify :subject "lead-100" :lead-id "lead-100" :to-status :working}
                  ctx)
    (println "\n== real advisor / :lead/convert (lead-200, already qualified) ==")
    (run-scenario actor "e2e-lead-convert"
                  {:op :lead/convert :subject "lead-200" :lead-id "lead-200"}
                  ctx)
    (println "\nALL CHECKS PASSED — :lead/qualify and :lead/convert verified end-to-end against a live model.")))
