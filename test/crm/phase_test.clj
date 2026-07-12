(ns crm.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [crm.store :as store]
            [crm.operation :as op]))

(def rep     {:actor-id "rep-1" :actor-role :rep})
(def manager {:actor-id "mg-1" :actor-role :sales-manager})

(def clean-transition
  {:op :opportunity/transition-stage :subject "opp-100" :opportunity-id "opp-100"
   :to-stage :qualification :rep-id "rep-100"
   :source {:class :crm-activity-log :ref "demo"}})

(def clean-disclosure
  {:op :disclosure/query :subject "acct-acme" :account-id "acct-acme"})

(def dispute-req
  {:op :dispute/request :subject "opp-100" :disputed-field :stage :claim :qualification})

(defn- run [phase req ctx]
  (let [s (store/seed-db)
        actor (op/build s)]
    [s (g/run* actor {:request req :context (assoc ctx :phase phase)}
               {:thread-id (str "ph-" phase "-" (:op req))})]))

(deftest phase0-holds-all-writes
  (let [[s res] (run 0 clean-transition rep)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))))

(deftest phase0-allows-governed-reads
  (let [[_ res] (run 0 clean-disclosure {:actor-id "sub-1" :actor-role :account-holder :account-id "acct-acme"})]
    (is (= :commit (get-in res [:state :disposition])))))

(deftest phase1-forces-approval-on-clean-transition
  (let [[_ res] (run 1 clean-transition rep)]
    (is (= :interrupted (:status res)))
    (is (= :phase-approval (-> res :state :audit last :reason)))))

(deftest phase3-auto-commits-clean-transition
  (let [[s res] (run 3 clean-transition rep)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :qualification (:stage (store/opportunity s "opp-100"))))))

(deftest governor-hold-beats-phase
  (testing "a hard governor violation (excessive discount) holds even in the most permissive phase"
    (let [[_ res] (run 3 {:op :opportunity/transition-stage :subject "opp-300" :opportunity-id "opp-300"
                          :to-stage :closed-won :rep-id "rep-100" :discount-pct 25
                          :source {:class :crm-activity-log :ref "demo"}}
                       rep)]
      (is (= :hold (get-in res [:state :disposition]))))))

(deftest dispute-request-never-auto-commits-at-any-phase
  (doseq [ph [0 1 2 3]]
    (let [[_ res] (run ph dispute-req manager)]
      (is (not= :commit (get-in res [:state :disposition]))
          (str "phase " ph " must not auto-commit a dispute")))))
