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
      (is (= 3 (count (store/all-opportunities s)))))))

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

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/rep s "nope")))
    (is (= [] (store/all-reps s)))
    (is (= [] (store/ledger s)))))
