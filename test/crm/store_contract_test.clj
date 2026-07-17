(ns crm.store-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [crm.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= :tier/rep (:discount-tier (store/rep s "rep-100"))))
      (is (= :tier/director (:discount-tier (store/rep s "rep-300"))))
      (is (= :tier/pro (:subscription-tier (store/account s "acct-acme"))))
      (is (= :negotiation (:stage (store/opportunity s "opp-300"))))
      (is (= 3 (count (store/all-reps s))))
      (is (= 3 (count (store/all-opportunities s))))
      (is (= 2 (count (store/all-accounts s)))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "stage-transition upsert commits"
        (store/commit-record! s {:effect :stage-transition-upsert
                                 :value {:opportunity-id "opp-100" :to-stage :qualification
                                         :discount-pct 0 :closed? false}})
        (is (= :qualification (:stage (store/opportunity s "opp-100")))))
      (testing "correction-apply patches the opportunity"
        (store/commit-record! s {:effect :correction-apply
                                 :value {:patch {:amount 20000.0}}
                                 :path ["opp-100"]})
        (is (= 20000.0 (:amount (store/opportunity s "opp-100")))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (take-last 2 (store/ledger s)))))))))

(deftest subscription-lookup
  (doseq [[label s] (backends)]
    (testing label
      (is (= :tier/pro (:product-tier (store/subscription s "acct-acme"))))
      (is (nil? (store/subscription s "acct-ghost"))))))

(deftest lead-and-contact-read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= :new (:status (store/lead s "lead-100"))))
      (is (= :qualified (:status (store/lead s "lead-200"))))
      (is (= "acct-acme" (:account-id (store/lead s "lead-200"))))
      (is (nil? (:account-id (store/lead s "lead-100"))))
      (is (= 2 (count (store/all-leads s))))
      (is (= 0 (count (store/all-contacts s))))
      (is (nil? (store/contact s "nope"))))))

(deftest lead-status-upsert-and-convert-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "lead-status-upsert commits"
        (store/commit-record! s {:effect :lead-status-upsert
                                 :value {:lead-id "lead-100" :to-status :working}})
        (is (= :working (:status (store/lead s "lead-100")))))
      (testing "lead-convert-upsert mints a Contact + Opportunity and marks the lead :converted"
        (store/commit-record! s {:effect :lead-convert-upsert
                                 :value {:lead-id "lead-200"
                                         :contact {:id "contact-lead-200" :name "Sam Okafor (demo)"
                                                   :email "sam@acme.example" :role "primary"}
                                         :opportunity {:id "opp-lead-200" :amount 0.0}}})
        (let [ld (store/lead s "lead-200")
              ct (store/contact s "contact-lead-200")
              opp (store/opportunity s "opp-lead-200")]
          (is (= :converted (:status ld)))
          (is (= "contact-lead-200" (:converted-to-contact-id ld)))
          (is (= "opp-lead-200" (:converted-to-opportunity-id ld)))
          (is (= "acct-acme" (:account-id ct))
              "the Contact inherits the lead's already-matched account-id, not a caller-supplied one")
          (is (= "acct-acme" (:account-id opp)))
          (is (= :prospecting (:stage opp)))
          (is (false? (:closed? opp))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/rep s "nope")))
    (is (= [] (store/all-reps s)))
    (is (= [] (store/all-accounts s)))
    (is (= [] (store/all-leads s)))
    (is (= [] (store/all-contacts s)))
    (is (= [] (store/ledger s)))))
