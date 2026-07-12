(ns crm.policy
  "SubscriptionGovernor — the independent compliance layer that earns the
  RevOps-LLM the right to transition an opportunity's stage, disclose an
  account's data, or resolve a dispute. The LLM has no notion of
  discount-authority limits, subscription entitlement scope, pipeline
  stage-sequence validity, or a subscriber's disclosure entitlement, so
  this MUST be a separate system able to *reject* a proposal and fall
  back to HOLD.

  Eight checks, in priority order. The first six are HARD violations: a
  human approver CANNOT override them. The last two are SOFT/always-
  escalate: they route to a human, who may approve.

    1. rbac                        — does actor-role have permission?
    2. discount-authority-gate     — this actor's domain-unique analog of
                                      a least-privilege gate: a rep's
                                      discount-authority tier must permit
                                      the requested discount percentage
                                      (NIST SP 800-53 AC-6 framing applied
                                      to pricing authority rather than
                                      technical access).
    3. entitlement-scope-gate      — a genuinely new check kind for this
                                      fleet: closing an opportunity cannot
                                      itself activate a subscription
                                      feature tier the account's ACTIVE
                                      subscription does not already
                                      license — feature/seat upgrades must
                                      go through the billing system first,
                                      never be conjured by a deal close.
    4. double-close-gate           — guards a dedicated `:closed?` boolean
                                      fact rather than a `:stage` value
                                      (a design choice deliberately
                                      informed by ADR-2607071320's
                                      status-lifecycle bug, not merely
                                      reused by analogy — the exact
                                      failure mode that caused that bug).
    5. stage-sequence-gate         — another new check kind: an
                                      opportunity stage transition must be
                                      a valid forward step (or an exit)
                                      per `kotoba.crm.pipeline` — no
                                      skipping ahead to `:closed-won`.
    6. source-provenance-gate      — stage-transition/disclosure source
                                      class must be in the R0 catalog.
    7. licensed-disclosure         — active subscription, columns within
                                      tier.
    8. confidence floor            — low confidence → escalate.
    9. revenue-mismatch-imminent   — a `:closed-won` transition's booked
                                      amount disagrees with the ASC 606 /
                                      IFRS 15 straight-line recompute →
                                      ALWAYS escalate, regardless of
                                      confidence (this actor's analog of
                                      an SLA-breach-imminent gate).
   10. dispute-request             — never auto-resolves, any phase.

  `crm.dashboard`'s book-wide pipeline funnel/revenue rollup is gated
  the same way as any other op: a new rbac-only key,
  `:pipeline/dashboard-query`, in `permissions` below (see ADR-0001's
  addendum for why it is restricted to `:sales-manager` — a book-wide
  aggregate is not a per-tier column disclosure, so none of checks 2-9
  apply to it, but it aggregates every account's pipeline/revenue,
  which no single `:account-holder` is entitled to, and no `:rep`
  permission in this table is book-wide today)."
  (:require [clojure.set :as set]
            [crm.facts :as facts]
            [crm.store :as store]
            [kotoba.crm.pipeline :as pipeline]
            [kotoba.crm.revrec :as revrec]))

;; ───────────────────────── policy tables ─────────────────────────

(def revenue-mismatch-tolerance-usd
  "A `:closed-won` booked amount further than this from the recomputed
  ASC 606/IFRS 15 straight-line figure always escalates to a human,
  regardless of confidence — this actor's analog of a halted-instrument/
  high-stakes gate."
  0.01)

(def confidence-floor 0.6)

(def permissions
  {:rep            #{:opportunity/transition-stage}
   :sales-manager  #{:opportunity/transition-stage :dispute/request
                     :pipeline/dashboard-query}
   :account-holder #{:disclosure/query}})

(def tier-columns
  (let [base #{:id :account-id :stage :amount}
        pro-extra #{:discount-pct}
        ent-extra #{:closed?}]
    {:tier/basic      base
     :tier/pro        (into base pro-extra)
     :tier/enterprise (into base (into pro-extra ent-extra))}))

;; ───────────────────────── checks ─────────────────────────

(defn- rbac-violations [{:keys [op]} {:keys [actor-role]}]
  (when-not (contains? (get permissions actor-role #{}) op)
    [{:rule :rbac :detail (str actor-role " は " op " の権限を持たない")}]))

(defn- discount-authority-violations
  [{:keys [op]} proposal st]
  (when (= op :opportunity/transition-stage)
    (let [{:keys [rep-id discount-pct]} (:value proposal)
          r (store/rep st rep-id)]
      (when (and r (pos? (or discount-pct 0))
                 (not (facts/discount-authorized? (:discount-tier r) discount-pct)))
        [{:rule :discount-authority-gate
          :detail (str "rep discount-tier " (:discount-tier r) " は割引率 "
                       discount-pct "% を承認する権限を持たない")}]))))

(defn- entitlement-scope-violations
  [{:keys [op]} proposal st]
  (when (= op :opportunity/transition-stage)
    (let [{:keys [opportunity-id activate-feature-tier]} (:value proposal)
          opp (store/opportunity st opportunity-id)
          acct (when opp (store/account st (:account-id opp)))]
      (when (and acct activate-feature-tier
                 (not (facts/feature-tier-at-least? (:subscription-tier acct) activate-feature-tier)))
        [{:rule :entitlement-scope-gate
          :detail (str "account subscription tier " (:subscription-tier acct)
                       " は feature tier " activate-feature-tier
                       " をまだライセンスしていない — 先に billing system で"
                       "サブスクリプションを更新すること")}]))))

(defn- double-close-violations
  [{:keys [op]} proposal st]
  (when (= op :opportunity/transition-stage)
    (let [opp (store/opportunity st (get-in proposal [:value :opportunity-id]))]
      (when (:closed? opp)
        [{:rule :double-close-gate
          :detail (str "opportunity " (:id opp) " は既に closed 済み")}]))))

(defn- stage-sequence-violations
  [{:keys [op]} proposal st]
  (when (= op :opportunity/transition-stage)
    (let [{:keys [opportunity-id to-stage]} (:value proposal)
          opp (store/opportunity st opportunity-id)]
      (when (and opp (not (:closed? opp))
                 (not (pipeline/valid-transition?
                       facts/pipeline-stage-order facts/exit-stages
                       (:stage opp) to-stage)))
        [{:rule :stage-sequence-gate
          :detail (str "opportunity " opportunity-id " の現stage " (:stage opp)
                       " から " to-stage " への遷移は無効(スキップまたは逆行)")}]))))

(defn- source-provenance-violations
  [{:keys [op]} proposal]
  (when (= op :opportunity/transition-stage)
    (let [src (:source proposal)]
      (when (or (nil? src) (not (facts/class-allowed? (:class src))))
        [{:rule :source-provenance-gate
          :detail (str "出典が無いか許可された出典クラスでない: " (pr-str src))}]))))

(defn- licensed-disclosure-violations
  [{:keys [op]} {:keys [account-id]} proposal st]
  (when (= op :disclosure/query)
    (let [acct (when account-id (store/account st account-id))]
      (if (or (nil? acct) (not (:active? acct)))
        [{:rule :licensed-disclosure :detail (str "有効な subscription が無い: account=" account-id)}]
        (let [allowed (get tier-columns (:subscription-tier acct) #{})
              cols    (set (:columns proposal))
              extra   (set/difference cols allowed)]
          (when (seq extra)
            [{:rule :licensed-disclosure
              :detail (str "subscription tier " (:subscription-tier acct) " に対し過剰な列: " (vec extra))}]))))))

(defn- revenue-mismatch-imminent?
  [st {:keys [op]} proposal]
  (when (= op :opportunity/transition-stage)
    (let [{:keys [opportunity-id to-stage booked-amount-usd as-of-date]} (:value proposal)
          opp (store/opportunity st opportunity-id)]
      (when (and opp (= to-stage :closed-won) (some? booked-amount-usd) (some? as-of-date))
        (let [sub (store/subscription st (:account-id opp))]
          (boolean (and sub (revrec/mismatch (assoc sub :as-of-date as-of-date)
                                              booked-amount-usd revenue-mismatch-tolerance-usd))))))))

(defn check
  "Censors a RevOps-LLM proposal against the policy tables. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool
    :revenue-mismatch-imminent? bool :hard? bool :correction? bool}."
  [request context proposal st]
  (let [hard    (into []
                      (concat (rbac-violations request context)
                              (discount-authority-violations request proposal st)
                              (entitlement-scope-violations request proposal st)
                              (double-close-violations request proposal st)
                              (stage-sequence-violations request proposal st)
                              (source-provenance-violations request proposal)
                              (licensed-disclosure-violations request context proposal st)))
        conf         (:confidence proposal 0.0)
        low?         (< conf confidence-floor)
        rev-urgent?  (revenue-mismatch-imminent? st request proposal)
        correction?  (= :dispute/request (:op request))
        hard?        (boolean (seq hard))]
    {:ok?                        (and (not hard?) (not low?) (not rev-urgent?) (not correction?))
     :violations                 hard
     :confidence                 conf
     :hard?                      hard?
     :escalate?                  (and (not hard?) (or low? rev-urgent? correction?))
     :revenue-mismatch-imminent? rev-urgent?
     :correction?                correction?}))

(defn hold-fact
  [request context verdict]
  {:t          :policy-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
