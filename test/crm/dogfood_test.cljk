(ns crm.dogfood-test
  "Dogfood Phase 1 validation (ADR-2607189200 / cloud-itonami-5820-dogfood-execution.md
  + 90-docs/business/cloud-itonami-5820-dogfood-seed.edn).

  Proves the 5820 CRM + SubscriptionGovernor can hold cloud-itonami's OWN
  4-vertical funnel (4 free-tenant accounts + one deal each) and drive it
  through the SAME governed transition + human-in-the-loop escalate/resume
  path the HTTP layer exposes — without inventing a paid conversion.

  In-process only: the 5820 CRM is NOT currently deployed anywhere (see
  ADR-2607189200 iter7 finding), so this validates the dogfood's Phase 1
  schema/data against the real governor directly via langgraph.graph/run*
  (the same call path crm.sim and crm.http use), not against a live host.

  Honest state modelled: 4 free-tenant opportunities at :prospecting
  (Lead/Trial), ZERO closed-won (externalPaid=0). The 5820 self-referential
  account sits at :negotiation (intended Managed CRM Starter, NOT a real
  charge). No amounts are booked."
  (:require [clojure.test :refer [deftest is testing]]
            [crm.store :as store]
            [crm.operation :as operation]
            [langgraph.graph :as g]))

(defn- dogfood-data
  "cloud-itonami's own 4-vertical funnel as a 5820 store seed. Mirrors
  cloud-itonami-5820-dogfood-seed.edn's Phase-1 data, retargeted to the
  5820's own stage vocabulary (:prospecting ~= Lead/Trial)."
  []
  {:reps {"rep-itonami-ops" {:id "rep-itonami-ops"
                             :name "cloud-itonami ops (dogfood)"
                             :discount-tier :tier/director}}
   :accounts
   {"acct-6399" {:id "acct-6399" :name "6399 Job Board"
                 :subscription-tier :tier/basic :active? true}
    "acct-6310" {:id "acct-6310" :name "6310 Talent Board"
                 :subscription-tier :tier/basic :active? true}
    "acct-7810" {:id "acct-7810" :name "7810 Placement Desk"
                 :subscription-tier :tier/basic :active? true}
    "acct-5820" {:id "acct-5820" :name "5820 CRM (self-referential dogfood)"
                 :subscription-tier :tier/basic :active? true}}
   :opportunities
   {"opp-6399" {:id "opp-6399" :account-id "acct-6399" :stage :prospecting
                :amount 0.0 :discount-pct 0 :closed? false}
    "opp-6310" {:id "opp-6310" :account-id "acct-6310" :stage :prospecting
                :amount 0.0 :discount-pct 0 :closed? false}
    "opp-7810" {:id "opp-7810" :account-id "acct-7810" :stage :prospecting
                :amount 0.0 :discount-pct 0 :closed? false}
    "opp-5820" {:id "opp-5820" :account-id "acct-5820" :stage :negotiation
                :amount 0.0 :discount-pct 0 :closed? false}}
   :subscriptions {} :leads {} :contacts {}})

(defn- dogfood-store []
  (store/->MemStore (atom (assoc (dogfood-data) :ledger []))))

(def ^:private rep-ctx {:actor-id "rep-itonami-ops" :actor-role :rep :phase 3})
(def ^:private mgr-ctx {:actor-id "rep-itonami-ops" :actor-role :sales-manager :phase 3})

(deftest dogfood-seed-holds-cloud-itonami-4-vertical-funnel
  (testing "seed = 4 free-tenant accounts + 4 deals, zero closed-won (externalPaid=0)"
    (let [s (dogfood-store)]
      (is (= 4 (count (store/all-accounts s))))
      (is (= 4 (count (store/all-opportunities s))))
      (is (every? #(= :prospecting (:stage %))
                  (map #(store/opportunity s %) ["opp-6399" "opp-6310" "opp-7810"])))
      (is (= :negotiation (:stage (store/opportunity s "opp-5820"))))
      (is (empty? (filter :closed? (store/all-opportunities s)))))))

(deftest dogfood-clean-transition-commits-through-governor
  (testing "a tenant deal :prospecting -> :qualification commits (no paid conversion claimed)"
    (let [s (dogfood-store)
          actor (operation/build s)
          res (g/run* actor
                      {:request {:op :opportunity/transition-stage :subject "opp-6399"
                                 :opportunity-id "opp-6399" :to-stage :qualification
                                 :rep-id "rep-itonami-ops"
                                 :source {:class :crm-activity-log :ref "dogfood-op1"}}
                       :context rep-ctx}
                      {:thread-id "dogfood-op1"})]
      (is (= :done (:status res)))
      (is (= :commit (-> res :state :disposition)))
      (is (= :qualification (:stage (store/opportunity s "opp-6399")))))))

(deftest dogfood-dispute-escalates-and-resumes-via-approve
  (testing "a dispute on a tenant deal escalates (:interrupted), then resumes to :done on :approve"
    (let [s (dogfood-store)
          actor (operation/build s)
          r1 (g/run* actor
                     {:request {:op :dispute/request :subject "opp-6310"
                                :disputed-field :stage :claim :qualification}
                      :context mgr-ctx}
                     {:thread-id "dogfood-op7"})
          r2 (when (= :interrupted (:status r1))
               (g/run* actor
                       {:approval {:status :approved :by "dogfood-manager"}}
                       {:thread-id "dogfood-op7" :resume? true}))]
      (is (= :interrupted (:status r1)))
      (is (= :done (:status r2))))))
