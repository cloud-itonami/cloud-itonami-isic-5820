(ns crm.policy-contract-test
  "The governor contract as executable tests. Single invariant under
  test: RevOps-LLM never transitions/discloses/resolves a record the
  SubscriptionGovernor would reject."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [crm.store :as store]
            [crm.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def rep     {:actor-id "rep-1" :actor-role :rep :phase 3})
(def manager {:actor-id "mg-1" :actor-role :sales-manager :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest authorized-transition-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :opportunity/transition-stage :subject "opp-100" :opportunity-id "opp-100"
                   :to-stage :qualification :rep-id "rep-100"
                   :source {:class :crm-activity-log :ref "demo"}}
                  rep)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :qualification (:stage (store/opportunity db "opp-100"))))
    (is (= 1 (count (store/ledger db))))))

(deftest unauthorized-role-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t2"
                  {:op :opportunity/transition-stage :subject "opp-100" :opportunity-id "opp-100"
                   :to-stage :qualification :rep-id "rep-100"
                   :source {:class :crm-activity-log :ref "demo"}}
                  {:actor-id "sub-1" :actor-role :account-holder :phase 3})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= [:rbac] (-> (store/ledger db) first :basis)))))

(deftest excessive-discount-is-held
  (testing "a rep proposing a discount beyond their authority tier → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :opportunity/transition-stage :subject "opp-300" :opportunity-id "opp-300"
                     :to-stage :closed-won :rep-id "rep-100" :discount-pct 25
                     :source {:class :crm-activity-log :ref "demo"}}
                    rep)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:discount-authority-gate} (-> (store/ledger db) first :basis)))
      (is (= :negotiation (:stage (store/opportunity db "opp-300")))))))

(deftest entitlement-scope-violation-is-held
  (testing "closing an opportunity cannot itself activate a feature tier beyond the account's active subscription"
    (let [[db actor] (fresh)
          res (exec-op actor "t4"
                    {:op :opportunity/transition-stage :subject "opp-200" :opportunity-id "opp-200"
                     :to-stage :closed-won :rep-id "rep-300" :activate-feature-tier :tier/enterprise
                     :source {:class :e-signature-system :ref "demo"}}
                    rep)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:entitlement-scope-gate} (-> (store/ledger db) first :basis)))
      (is (= :negotiation (:stage (store/opportunity db "opp-200")))))))

(deftest stage-sequence-violation-is-held
  (testing "skipping ahead in the pipeline (prospecting straight to closed-won) → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :opportunity/transition-stage :subject "opp-100" :opportunity-id "opp-100"
                     :to-stage :closed-won :rep-id "rep-100"
                     :source {:class :crm-activity-log :ref "demo"}}
                    rep)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:stage-sequence-gate} (-> (store/ledger db) first :basis)))
      (is (= :prospecting (:stage (store/opportunity db "opp-100")))))))

(deftest double-close-violation-is-held
  (testing "a second transition proposal on an already-closed opportunity → HOLD"
    (let [[db actor] (fresh)
          _close (exec-op actor "t6a"
                       {:op :opportunity/transition-stage :subject "opp-300" :opportunity-id "opp-300"
                        :to-stage :closed-won :rep-id "rep-300"
                        :source {:class :crm-activity-log :ref "demo"}}
                       rep)
          _ (is (true? (:closed? (store/opportunity db "opp-300"))))
          res (exec-op actor "t6b"
                    {:op :opportunity/transition-stage :subject "opp-300" :opportunity-id "opp-300"
                     :to-stage :closed-lost :rep-id "rep-300"
                     :source {:class :crm-activity-log :ref "demo"}}
                    rep)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:double-close-gate} (-> (store/ledger db) last :basis))))))

(deftest uncontracted-disclosure-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t7"
                  {:op :disclosure/query :subject "acct-ghost" :account-id "acct-ghost"}
                  {:actor-id "sub-2" :actor-role :account-holder :account-id "acct-ghost" :phase 3})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis)))))

(deftest over-disclosure-beyond-tier-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t8"
                  {:op :disclosure/query :subject "acct-basic" :account-id "acct-basic" :greedy? true}
                  {:actor-id "sub-1" :actor-role :account-holder :account-id "acct-basic" :phase 3})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis)))))

(deftest revenue-mismatch-escalates-then-human-decides
  (testing "an otherwise-clean close whose booked amount disagrees with the ASC606/IFRS15 recompute interrupts for human approval"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t9"
                   {:op :opportunity/transition-stage :subject "opp-300" :opportunity-id "opp-300"
                    :to-stage :closed-won :rep-id "rep-300" :discount-pct 5
                    :booked-amount-usd 12000.0 :as-of-date {:year 2026 :month 7}
                    :source {:class :billing-system-webhook :ref "demo"}}
                   manager)]
      (is (= :interrupted (:status r1)))
      (is (= :revenue-mismatch-imminent (-> r1 :state :audit last :reason)))
      (let [r2 (g/run* actor {:approval {:status :approved :by "manager-1"}}
                       {:thread-id "t9" :resume? true})]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :closed-won (:stage (store/opportunity db "opp-300"))))
        (is (true? (:closed? (store/opportunity db "opp-300"))))))))

(deftest lead-qualify-authorized-commits
  (testing "a valid forward lead-status step (new → working) commits"
    (let [[db actor] (fresh)
          res (exec-op actor "t11"
                    {:op :lead/qualify :subject "lead-100" :lead-id "lead-100" :to-status :working}
                    rep)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= :working (:status (store/lead db "lead-100")))))))

(deftest lead-status-skip-is-held
  (testing "skipping ahead in the lead lifecycle (new straight to qualified) → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t12"
                    {:op :lead/qualify :subject "lead-100" :lead-id "lead-100" :to-status :qualified}
                    rep)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:lead-status-gate} (-> (store/ledger db) first :basis)))
      (is (= :new (:status (store/lead db "lead-100")))))))

(deftest unqualified-lead-convert-is-held
  (testing "converting a lead that is not yet :qualified → HOLD, no Contact/Opportunity minted"
    (let [[db actor] (fresh)
          res (exec-op actor "t13"
                    {:op :lead/convert :subject "lead-100" :lead-id "lead-100"}
                    rep)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:lead-convertible-gate} (-> (store/ledger db) first :basis)))
      (is (empty? (store/all-contacts db))))))

(deftest qualified-lead-convert-escalates-then-mints-contact-and-opportunity-on-approval
  (testing ":lead/convert never auto-commits (phase.cljc: it mints new records, always approval-gated) — a governor-clean, :qualified, account-matched lead escalates, then commits and mints on human approval"
    (let [[db actor] (fresh)
          before-opps (count (store/all-opportunities db))
          r1 (exec-op actor "t14"
                   {:op :lead/convert :subject "lead-200" :lead-id "lead-200"}
                   rep)]
      (is (= :interrupted (:status r1)))
      (is (= :phase-approval (-> r1 :state :audit last :reason)))
      (is (empty? (store/all-contacts db)) "nothing minted before approval")
      (let [r2 (g/run* actor {:approval {:status :approved :by "manager-1"}}
                       {:thread-id "t14" :resume? true})
            ld (store/lead db "lead-200")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :converted (:status ld)))
        (is (= 1 (count (store/all-contacts db))))
        (is (= "acct-acme" (:account-id (first (store/all-contacts db))))
            "the new Contact inherits the lead's already-matched account-id")
        (is (= (inc before-opps) (count (store/all-opportunities db))))
        (is (= :prospecting (:stage (store/opportunity db (:converted-to-opportunity-id ld))))
            "a freshly-converted opportunity always starts at the first pipeline stage")))))

(deftest dispute-request-always-escalates-regardless-of-confidence
  (let [[_db actor] (fresh)
        r1 (exec-op actor "t10"
                 {:op :dispute/request :subject "opp-100" :disputed-field :stage :claim :qualification}
                 manager)]
    (is (= :interrupted (:status r1)))
    (is (= :dispute-request (-> r1 :state :audit last :reason)))
    (let [r2 (g/run* actor {:approval {:status :approved :by "manager-1"}}
                     {:thread-id "t10" :resume? true})]
      (is (= :commit (get-in r2 [:state :disposition]))))))
