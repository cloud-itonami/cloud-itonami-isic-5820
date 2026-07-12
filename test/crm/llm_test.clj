(ns crm.llm-test
  (:require [clojure.test :refer [deftest is]]
            [crm.store :as store]
            [crm.llm :as llm]))

(deftest transition-proposal-carries-source-and-cites
  (let [db (store/seed-db)
        p (llm/infer db {:op :opportunity/transition-stage :subject "opp-100"
                         :opportunity-id "opp-100" :to-stage :qualification
                         :rep-id "rep-100"
                         :source {:class :crm-activity-log :ref "demo"}})]
    (is (= :stage-transition-upsert (:effect p)))
    (is (= {:class :crm-activity-log :ref "demo"} (:source p)))
    (is (>= (:confidence p) 0.9))))

(deftest unsourced-transition-proposal-carries-nil-source
  (let [db (store/seed-db)
        p (llm/infer db {:op :opportunity/transition-stage :subject "opp-100"
                         :opportunity-id "opp-100" :to-stage :qualification
                         :rep-id "rep-100"
                         :source {:class :crm-activity-log :ref "demo"}
                         :unsourced? true})]
    (is (nil? (:source p)))
    (is (>= (:confidence p) 0.85) "still high-confidence — proves source-provenance cannot rely on confidence")))

(deftest closed-stage-marks-closed?-true
  (let [db (store/seed-db)
        p (llm/infer db {:op :opportunity/transition-stage :subject "opp-300"
                         :opportunity-id "opp-300" :to-stage :closed-won
                         :rep-id "rep-300"
                         :source {:class :crm-activity-log :ref "demo"}})]
    (is (true? (get-in p [:value :closed?])))))

(deftest disclosure-proposal-greedy-adds-extra-columns
  (let [db (store/seed-db)
        clean (llm/infer db {:op :disclosure/query :subject "acct-acme" :account-id "acct-acme"})
        greedy (llm/infer db {:op :disclosure/query :subject "acct-acme" :account-id "acct-acme" :greedy? true})]
    (is (< (count (:columns clean)) (count (:columns greedy))))))

(deftest dispute-proposal-never-marks-high-confidence
  (let [db (store/seed-db)
        p (llm/infer db {:op :dispute/request :subject "opp-100" :disputed-field :stage :claim :qualification})]
    (is (= :correction-apply (:effect p)))
    (is (< (:confidence p) 0.9))))
