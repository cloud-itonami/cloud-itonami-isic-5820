(ns crm.dashboard-test
  "crm.dashboard is a pure aggregation layer over an already-authorized
  `db` (see its ns docstring) — these tests exercise the aggregation
  math directly against a hand-built multi-opportunity/multi-subscription
  fixture, plus the RBAC decision (`:pipeline/dashboard-query`,
  `:sales-manager`-only) as enforced by `crm.policy/check` directly."
  (:require [clojure.test :refer [deftest is testing]]
            [crm.dashboard :as dashboard]
            [crm.policy :as policy]
            [crm.store :as store]))

;; ───────────────────────── fixture ─────────────────────────
;; Four accounts, seven opportunities spanning every ordered pipeline
;; stage plus the :closed-lost exit stage (one WITH an explicit
;; :reached-stage fact, one WITHOUT -- exercising kotoba.crm.funnel's
;; honest exit-stage exclusion policy), and four subscriptions covering
;; every revenue-rollup edge case: two normal active subscriptions, one
;; INACTIVE subscription (must be excluded from the rollup entirely),
;; and one active subscription with an invalid term-months (must be
;; excluded from the sum but still counted as "excluded", never a
;; fabricated 0 silently folded in).

(def accounts
  {"acct-a" {:id "acct-a" :name "A" :subscription-tier :tier/pro :active? true}
   "acct-b" {:id "acct-b" :name "B" :subscription-tier :tier/basic :active? true}
   "acct-c" {:id "acct-c" :name "C" :subscription-tier :tier/enterprise :active? true}
   "acct-d" {:id "acct-d" :name "D" :subscription-tier :tier/basic :active? true}})

(def opportunities
  {"opp-1" {:id "opp-1" :account-id "acct-a" :stage :prospecting  :amount 1000.0 :discount-pct 0 :closed? false}
   "opp-2" {:id "opp-2" :account-id "acct-a" :stage :qualification :amount 2000.0 :discount-pct 0 :closed? false}
   "opp-3" {:id "opp-3" :account-id "acct-b" :stage :proposal      :amount 3000.0 :discount-pct 0 :closed? false}
   "opp-4" {:id "opp-4" :account-id "acct-b" :stage :negotiation   :amount 4000.0 :discount-pct 0 :closed? false}
   "opp-5" {:id "opp-5" :account-id "acct-a" :stage :closed-won    :amount 5000.0 :discount-pct 0 :closed? true}
   ;; closed-lost WITH an explicit :reached-stage fact -> credited up to :negotiation
   "opp-6" {:id "opp-6" :account-id "acct-c" :stage :closed-lost   :amount 6000.0 :discount-pct 0 :closed? true
            :reached-stage :negotiation}
   ;; closed-lost with NO :reached-stage fact -> excluded from reached-counts entirely
   "opp-7" {:id "opp-7" :account-id "acct-b" :stage :closed-lost   :amount 7000.0 :discount-pct 0 :closed? true}})

(def subscriptions
  {"acct-a" {:account-id "acct-a" :product-tier :tier/pro
             :contract-value-usd 12000.0 :term-months 12
             :start-date {:year 2026 :month 1 :day 1} :active? true}
   "acct-b" {:account-id "acct-b" :product-tier :tier/basic
             :contract-value-usd 6000.0 :term-months 12
             :start-date {:year 2026 :month 1 :day 1} :active? true}
   ;; inactive subscription -- must be excluded from the rollup entirely,
   ;; regardless of what its recompute would say
   "acct-c" {:account-id "acct-c" :product-tier :tier/enterprise
             :contract-value-usd 24000.0 :term-months 24
             :start-date {:year 2025 :month 7 :day 1} :active? false}
   ;; active but term-months is non-positive -- recognized-revenue-to-date
   ;; has no opinion; must contribute 0, not be silently dropped uncounted
   "acct-d" {:account-id "acct-d" :product-tier :tier/basic
             :contract-value-usd 5000.0 :term-months 0
             :start-date {:year 2026 :month 1 :day 1} :active? true}})

(defn- fixture-db []
  (-> (store/seed-db)
      (store/with-accounts accounts)
      (store/with-opportunities opportunities)
      (store/with-subscriptions subscriptions)))

(def as-of-date {:year 2026 :month 7})

;; ───────────────────────── pipeline funnel ─────────────────────────

(deftest pipeline-funnel-stage-counts-and-reached-counts
  (let [db (fixture-db)
        {:keys [stage-counts reached-counts]} (dashboard/pipeline-funnel db)]
    (testing "snapshot distribution: every ordered stage present, exit stage counted under its own key"
      (is (= 1 (:prospecting stage-counts)))
      (is (= 1 (:qualification stage-counts)))
      (is (= 1 (:proposal stage-counts)))
      (is (= 1 (:negotiation stage-counts)))
      (is (= 1 (:closed-won stage-counts)))
      (is (= 2 (:closed-lost stage-counts))))
    (testing "reached-counts: cumulative by rank, opp-6 credited via :reached-stage, opp-7 excluded"
      (is (= 6 (:prospecting reached-counts)))   ; opp-1,2,3,4,5,6
      (is (= 5 (:qualification reached-counts))) ; opp-2,3,4,5,6
      (is (= 4 (:proposal reached-counts)))      ; opp-3,4,5,6
      (is (= 3 (:negotiation reached-counts)))   ; opp-4,5,6
      (is (= 1 (:closed-won reached-counts))))))

(deftest conversion-rates-between-consecutive-stages
  (let [db (fixture-db)
        rates (dashboard/conversion-rates db)]
    (is (= (double (/ 5 6)) (get rates [:prospecting :qualification])))
    (is (= (double (/ 4 5)) (get rates [:qualification :proposal])))
    (is (= (double (/ 3 4)) (get rates [:proposal :negotiation])))
    (is (= (double (/ 1 3)) (get rates [:negotiation :closed-won])))))

;; ───────────────────────── revenue rollup ─────────────────────────

(deftest revenue-rollup-sums-only-active-recomputable-subscriptions
  (let [db (fixture-db)
        r (dashboard/revenue-rollup db as-of-date)]
    (testing "acct-c is inactive -> excluded from the active count entirely"
      (is (= 3 (:active-subscription-count r))))   ; a, b, d (not c)
    (testing "acct-d is active but term-months invalid -> counted as excluded, contributes 0"
      (is (= 1 (:subscriptions-excluded r))))
    (testing "ground-truth straight-line recompute: acct-a 6000.0 + acct-b 3000.0, acct-c/d contribute 0"
      (is (= 9000.0 (:recognized-revenue-usd r))))
    (is (= as-of-date (:as-of-date r)))))

(deftest render-combines-funnel-conversion-and-revenue
  (let [db (fixture-db)
        v (dashboard/render db as-of-date)]
    (is (= 1 (:closed-won (:stage-counts v))))
    (is (contains? (:conversion-rates v) [:negotiation :closed-won]))
    (is (= 9000.0 (:recognized-revenue-usd (:revenue v))))))

(deftest coverage-is-honest-not-aspirational
  (let [c (dashboard/coverage)]
    (is (= :pipeline/dashboard-query (:governed-op c)))
    (is (re-find #"sales-manager" (:note c)))
    (is (re-find #"straight-line" (:note c)))))

;; ───────────────────────── governance (RBAC) ─────────────────────────

(deftest dashboard-query-authorized-for-sales-manager-only
  (let [db (fixture-db)
        request {:op :pipeline/dashboard-query :subject "book-wide"}
        proposal {:confidence 1.0}]
    (testing "sales-manager: no rbac violation, ok? true"
      (let [v (policy/check request {:actor-id "mg-1" :actor-role :sales-manager} proposal db)]
        (is (true? (:ok? v)))
        (is (false? (:hard? v)))
        (is (empty? (:violations v)))))
    (testing "rep: rbac violation, held"
      (let [v (policy/check request {:actor-id "rep-1" :actor-role :rep} proposal db)]
        (is (false? (:ok? v)))
        (is (true? (:hard? v)))
        (is (= [:rbac] (mapv :rule (:violations v))))))
    (testing "account-holder: rbac violation, held -- a book-wide rollup is not this actor's per-account disclosure"
      (let [v (policy/check request {:actor-id "sub-1" :actor-role :account-holder} proposal db)]
        (is (false? (:ok? v)))
        (is (true? (:hard? v)))
        (is (= [:rbac] (mapv :rule (:violations v))))))))
