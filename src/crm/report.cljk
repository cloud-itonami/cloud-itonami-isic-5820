(ns crm.report
  "Disclosure rendering — output as a GOVERNED read. The column set is
  whatever the SubscriptionGovernor's licensed-disclosure gate approved
  for the caller's subscription tier."
  (:require [crm.store :as store]))

(defn render-opportunity
  [db opportunity-id columns]
  (let [opp (store/opportunity db opportunity-id)
        cell (fn [col]
               (case col
                 :id            opportunity-id
                 :account-id    (:account-id opp)
                 :stage         (:stage opp)
                 :amount        (:amount opp)
                 :discount-pct  (:discount-pct opp)
                 :closed?       (:closed? opp)
                 nil))]
    (into {} (map (juxt identity cell)) columns)))
