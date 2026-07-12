(ns crm.file-store-test
  "Fast, in-process regression coverage for `crm.file-store`'s persistence
  contract: mutate a `FileStore`, then construct a BRAND NEW `FileStore`
  instance (a fresh record wrapping a fresh atom, not the same object —
  the closest thing to 'restart' reachable inside one JVM/test run) at the
  SAME path, and confirm the new instance sees the old instance's writes.

  This test does NOT itself kill and restart an OS process — it proves
  the snapshot-to-disk/load-from-disk logic is correct, not that a real
  `clojure -M:serve` process survives a real restart. That end-to-end
  claim was verified separately and manually (real `-main` process,
  real HTTP POSTs, real `kill`, real restart, real HTTP GETs) — see the
  commit/PR description for that transcript; it is not automated here
  because spawning a JVM subprocess per test run is slow and this file's
  job is fast regression coverage of the persistence LOGIC."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [crm.store :as store]
            [crm.file-store :as file-store])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-path
  "A filesystem path that does not yet exist -- `Files/createTempFile`
  actually creates the (empty) file to reserve a unique name, so it is
  deleted immediately; `crm.file-store/file-store!` should treat a
  nonexistent path as 'seed fresh', not try to `edn/read-string` an
  empty file."
  []
  (let [f (Files/createTempFile "crm-file-store-test" ".edn" (make-array FileAttribute 0))]
    (Files/delete f)
    (str f)))

(deftest fresh-path-seeds-demo-data-and-writes-it
  (let [path (temp-path)
        s (file-store/file-store! path)]
    (testing "seeded like seed-db"
      (is (= 3 (count (store/all-reps s))))
      (is (= 2 (count (store/all-accounts s))))
      (is (= 3 (count (store/all-opportunities s))))
      (is (= :tier/pro (:subscription-tier (store/account s "acct-acme")))))
    (testing "the seed was actually written to disk, not just held in memory"
      (let [on-disk (edn/read-string (slurp path))]
        (is (= 3 (count (:reps on-disk))))
        (is (= [] (:ledger on-disk)))))))

(deftest writes-survive-a-fresh-instance-at-the-same-path
  (let [path (temp-path)
        s1 (file-store/file-store! path)]
    ;; Mutate through s1: a stage transition, a correction, and two ledger
    ;; entries -- the same operations crm.operation's :commit node performs.
    (store/commit-record! s1 {:effect :stage-transition-upsert
                               :value {:opportunity-id "opp-100" :to-stage :qualification
                                       :discount-pct 0 :closed? false}})
    (store/commit-record! s1 {:effect :correction-apply
                               :value {:patch {:amount 20000.0}}
                               :path ["opp-100"]})
    (store/append-ledger! s1 {:op :a :disposition :commit})
    (store/append-ledger! s1 {:op :b :disposition :hold})

    ;; A BRAND NEW FileStore record/atom at the same path -- not s1, not
    ;; sharing any Clojure object with it. If this sees s1's writes, the
    ;; state genuinely round-tripped through the file on disk.
    (let [s2 (file-store/file-store! path)]
      (testing "stage transition persisted"
        (is (= :qualification (:stage (store/opportunity s2 "opp-100")))))
      (testing "correction persisted"
        (is (= 20000.0 (:amount (store/opportunity s2 "opp-100")))))
      (testing "ledger persisted, order-preserving"
        (is (= [:commit :hold] (mapv :disposition (take-last 2 (store/ledger s2))))))
      (testing "unmodified fields also carried over"
        (is (= :tier/rep (:discount-tier (store/rep s2 "rep-100"))))))))

(deftest with-accounts-persists-and-is-visible-to-a-fresh-instance
  (let [path (temp-path)
        s1 (file-store/file-store! path)]
    (store/with-accounts s1 (assoc (into {} (map (juxt :id identity) (store/all-accounts s1)))
                                    "acct-new" {:id "acct-new" :name "New Co" :subscription-tier :tier/basic :active? true}))
    (let [s2 (file-store/file-store! path)]
      (is (some? (store/account s2 "acct-new")))
      (is (= "New Co" (:name (store/account s2 "acct-new")))))))
