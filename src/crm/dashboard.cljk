(ns crm.dashboard
  "Pipeline funnel + ground-truth revenue rollup — an AGGREGATE,
  cross-record view distinct from `crm.report` (a GOVERNED render of ONE
  opportunity's disclosure-tier-gated columns for ONE account-holder).
  `crm.dashboard` never renders or discloses any single opportunity's or
  account's own fields to that account's holder; it only aggregates
  counts/rates/dollars across EVERY opportunity and subscription in the
  store for a sales-ops/management rollup — the 'how is the whole book
  doing' view, not the 'what does this one customer see' view.

  Built on this workspace's `kotoba.crm.funnel` (aggregate pipeline
  funnel/conversion analytics) over THIS actor's own
  `crm.facts/pipeline-stage-order` + `crm.facts/exit-stages` — the same
  stage vocabulary `crm.policy`'s `stage-sequence-gate` already enforces,
  never a redefined/parallel stage list. The revenue rollup reuses
  `kotoba.crm.revrec/recognized-revenue-to-date`, the exact same ASC 606
  / IFRS 15 straight-line ground-truth recompute `crm.policy`'s
  `revenue-mismatch-imminent?` gate already relies on (via
  `kotoba.crm.revrec/mismatch`) — this is a genuine recompute across
  every active subscription, never a cached or proposal-trusting sum.

  Governance: this is a new op, `:pipeline/dashboard-query`, added to
  `crm.policy`'s `permissions` RBAC table restricted to `:sales-manager`
  only (see `docs/adr/0001-architecture.md`'s addendum for the reasoning
  — a book-wide rollup aggregates every account's pipeline/revenue,
  which no single `:account-holder` is entitled to see, and no `:rep`
  permission in this actor's table is book-wide today). Callers MUST
  route a `{:op :pipeline/dashboard-query ...}` request through
  `crm.policy/check` (directly, or via `crm.operation`'s full
  OperationActor graph, since `:pipeline/dashboard-query` is registered
  in `crm.phase/read-ops`) before calling anything in this namespace —
  exactly as `crm.report/render-opportunity` assumes its caller already
  holds a governor-approved column set; neither renderer re-checks
  permission internally, both are pure functions over an already-
  authorized `db`."
  (:require [crm.facts :as facts]
            [crm.store :as store]
            [kotoba.crm.funnel :as funnel]
            [kotoba.crm.revrec :as revrec]))

(defn pipeline-funnel
  "Point-in-time snapshot distribution (`stage-counts`) and cumulative
  reached-counts across every opportunity in `db`, keyed by this actor's
  own `crm.facts/pipeline-stage-order`. See `kotoba.crm.funnel`'s own
  docstrings/`coverage` for the exit-stage policy: an opportunity
  currently in `crm.facts/exit-stages` (e.g. `:closed-lost`) is EXCLUDED
  from `:reached-counts` unless it carries an explicit `:reached-stage`
  fact recording the last ordered stage it passed through before
  exiting — this namespace never guesses that fact, it only reads it
  if the caller's own data already has it."
  [db]
  (let [opps (store/all-opportunities db)]
    {:stage-counts   (funnel/stage-counts opps facts/pipeline-stage-order)
     :reached-counts (funnel/reached-counts opps facts/pipeline-stage-order)}))

(defn conversion-rates
  "Stage-to-stage conversion rate for every consecutive pair in
  `crm.facts/pipeline-stage-order`, over every opportunity in `db`. A
  `[from to]` pair nobody ever reached `from` for yields `nil` (no
  data), never a fabricated 0% or a crash — see
  `kotoba.crm.funnel/conversion-rate`."
  [db]
  (funnel/conversion-rate (store/all-opportunities db) facts/pipeline-stage-order))

(defn revenue-rollup
  "Ground-truth ASC 606/IFRS 15 straight-line recognized-revenue-to-date,
  summed across every ACTIVE subscription in `db` as of `as-of-date`
  (`{:year Y :month M}`-shaped, the same shape `crm.policy` already
  threads through `kotoba.crm.revrec`). Recomputes from each
  subscription's own `:contract-value-usd`/`:term-months`/`:start-date`
  — it never trusts a cached or proposal-supplied revenue figure, the
  same ground-truth-recompute discipline `crm.policy`'s
  `revenue-mismatch-imminent?` gate already relies on via
  `kotoba.crm.revrec/mismatch`. An inactive subscription (`:active?`
  false) contributes nothing and is not even counted — a closed/lapsed
  subscription's contract value is not this actor's revenue. A
  subscription for which `recognized-revenue-to-date` has no opinion
  (e.g. a non-positive `:term-months`) also contributes nothing rather
  than a fabricated 0 — `:subscriptions-excluded` reports how many
  active subscriptions were skipped for that reason, so the aggregate
  is never silently wrong."
  [db as-of-date]
  (let [active-subs (->> (store/all-accounts db)
                          (keep #(store/subscription db (:id %)))
                          (filter :active?))
        recognized  (mapv #(revrec/recognized-revenue-to-date (assoc % :as-of-date as-of-date))
                           active-subs)]
    {:as-of-date                as-of-date
     :active-subscription-count (count active-subs)
     :subscriptions-excluded    (count (filter nil? recognized))
     :recognized-revenue-usd    (reduce + 0.0 (remove nil? recognized))}))

(defn render
  "The full dashboard view: pipeline funnel + conversion rates + ground-
  truth revenue rollup, in one map. `as-of-date` gates the revenue
  rollup only — the funnel/conversion views are a point-in-time
  snapshot of each opportunity's current `:stage` alone, per
  `kotoba.crm.funnel`'s own scope, with no date dependence at all."
  [db as-of-date]
  (merge (pipeline-funnel db)
         {:conversion-rates (conversion-rates db)
          :revenue          (revenue-rollup db as-of-date)}))

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers — this
  namespace inherits `kotoba.crm.funnel`'s own R0 scope (point-in-time
  snapshot only; no stage-history log, no time dimension, no cohort/
  trend tracking; exit-stage entities excluded from reached-counts/
  conversion-rates unless they carry an explicit `:reached-stage` fact)
  and `kotoba.crm.revrec`'s own R0 scope (straight-line recognition
  only; no usage-based billing, contract modification, or multi-element
  allocation)."
  []
  {:model       :point-in-time-pipeline-and-revenue-rollup
   :governed-op :pipeline/dashboard-query
   :note (str "R0 scope: a single point-in-time snapshot only, "
              "inherited directly from kotoba.crm.funnel's own coverage "
              "(no stage-history log, no time dimension, no cohort/"
              "trend tracking; an opportunity currently in an exit "
              "stage is excluded from reached-counts/conversion-rates "
              "unless it carries an explicit :reached-stage fact -- "
              "never guessed) and kotoba.crm.revrec's own coverage "
              "(straight-line recognition only -- no usage-based "
              "billing, contract modification, or multi-element "
              "allocation). Access is gated as :pipeline/dashboard-query, "
              "RBAC-restricted to :sales-manager only (see ADR-0001's "
              "addendum). Extend only by adding a genuinely new, "
              "documented metric or widening RBAC via an explicit ADR, "
              "never by guessing or silently loosening either.")})
