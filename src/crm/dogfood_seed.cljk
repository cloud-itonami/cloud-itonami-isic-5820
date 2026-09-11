(ns crm.dogfood-seed
  "Reproducible dogfood seed for the 5820 CRM.

  The live deploy (ADR-2607199950) hosts cloud-itonami's OWN sales funnel
  in this CRM. That funnel state was initially written by hand to the
  ISIC5820_STORE_FILE; this namespace makes it reproducible —
  `clojure -M:dev:dogfood-seed <path>` writes the exact dogfood snapshot
  (cloud-itonami's real funnel) in the SAME `(pr-str db)` format
  `crm.file-store/persist!` uses, so `file-store!` on that path loads it
  verbatim on the next service start.

  Honest state (matches the live portfolio, externalPaid=0):
  - 4 external free tenants (externalTotal=4) at :prospecting (Lead/Trial)
  - 1 self-referential 5820 deal at :negotiation (INTENDED Managed CRM
    Starter — NOT a real charge; no subscription, no booked revenue)
  - 0 closed-won, $0 recognized revenue, 0 active subscriptions
  Companion data spec: 90-docs/business/cloud-itonami-5820-dogfood-seed.edn.

  Idempotent: re-running overwrites with the same dogfood snapshot (the
  ledger is reset to the seed fact; governed transitions applied AFTER
  seeding live in the store and persist normally)."
  (:require [clojure.java.io :as io])
  (:import [java.io File]))

(defn dogfood-data
  "cloud-itonami's own 4-vertical funnel as a 5820 store snapshot. Mirrors
  crm.store/demo-data's shape (so file-store! / MemStore read it unchanged),
  retargeted to cloud-itonami's real accounts + the 5820's stage vocabulary."
  []
  {:reps
   {"rep-itonami-ops" {:id "rep-itonami-ops"
                       :name "cloud-itonami ops (dogfood)"
                       :discount-tier :tier/director}}
   :accounts
   {"acct-6399" {:id "acct-6399" :name "6399 Job Board (external free tenant)"
                 :subscription-tier :tier/free :active? true}
    "acct-6310" {:id "acct-6310" :name "6310 Talent Board (external free tenant)"
                 :subscription-tier :tier/free :active? true}
    "acct-7810" {:id "acct-7810" :name "7810 Placement Desk (external free tenant)"
                 :subscription-tier :tier/free :active? true}
    "acct-5820" {:id "acct-5820" :name "5820 CRM (self-referential dogfood)"
                 :subscription-tier :tier/free :active? true}}
   :opportunities
   {"opp-6399-a" {:id "opp-6399-a" :account-id "acct-6399" :stage :prospecting
                  :amount 0.0 :discount-pct 0 :closed? false}
    "opp-6399-b" {:id "opp-6399-b" :account-id "acct-6399" :stage :prospecting
                  :amount 0.0 :discount-pct 0 :closed? false}
    "opp-6310" {:id "opp-6310" :account-id "acct-6310" :stage :prospecting
                :amount 0.0 :discount-pct 0 :closed? false}
    "opp-7810" {:id "opp-7810" :account-id "acct-7810" :stage :prospecting
                :amount 0.0 :discount-pct 0 :closed? false}
    "opp-5820-self" {:id "opp-5820-self" :account-id "acct-5820" :stage :negotiation
                     :amount 0.0 :discount-pct 0 :closed? false}}
   :subscriptions {}
   :leads {}
   :contacts {}
   :ledger
   [{:t :dogfood-seed
     :note "cloud-itonami own-funnel seeded: 4 external free tenants (externalTotal=4), 0 closed-won (externalPaid=0), 0 booked revenue. opp-5820-self :negotiation = intended self-referential Managed CRM Starter (NOT a real charge)."}]})

(defn -main
  "Writes the dogfood snapshot to `path` in the crm.file-store format
  (pr-str + atomic rename). Usage: clojure -M:dev:dogfood-seed <path>
  (typically $ISIC5820_STORE_FILE)."
  [path]
  (let [db (dogfood-data)
        tmp (File. (str path ".tmp"))]
    (io/make-parents path)
    (spit tmp (pr-str db))
    (.renameTo tmp (File. (str path)))
    (println (str "wrote dogfood seed -> " path
                  " (4 accounts, 5 opportunities, 0 closed-won, $0 revenue)"))))
