(ns crm.facts
  "R0 provenance/authority catalog for the commercial CRM/subscription-
  commerce actor (Salesforce/HubSpot-class SaaS platform business,
  ISIC Rev.4 5820 narrowed to sales-pipeline + subscription entitlement
  governance) — the ONLY source classes, discount-authority tiers, and
  subscription feature tiers the SubscriptionGovernor will accept
  (honesty over coverage, same discipline as sibling actors' facts
  catalogs).

  Four closed sets/tables:
    1. `allowed-source-classes` — where a stage-transition proposal's
       evidence came from (a logged CRM activity, an e-signature system's
       webhook, or the billing system's own webhook). A transition citing
       anything outside this set is rejected outright.
    2. `discount-authority-tiers` + `max-discount-pct` — an ordered
       pricing-authority scale (least-privilege framing for discounting,
       the same NIST SP 800-53 AC-6 discipline `cloud-itonami-isic-6209`
       applies to technical access) mapping a rep's tier to the maximum
       discount percentage they may apply without escalation.
    3. `subscription-feature-tiers` + `feature-catalog` — an ordered SaaS
       entitlement scale; a feature/seat activation must be licensed by
       the account's active subscription tier.
    4. `pipeline-stage-order` / `exit-stages` — the sales pipeline shape,
       delegated to the shared `kotoba.crm.pipeline` technical commons.
    5. `lead-status-order` / `lead-exit-statuses` — the lead-qualification
       lifecycle shape, same `kotoba.crm.pipeline` commons reused for a
       second ordered-stage domain (its own docstring names 'marketing
       lifecycle stages' as an intended reuse, not just opportunity
       stages). Converting a lead into a Contact + Opportunity is a
       SEPARATE, more restrictive gate than 'is this the next lead
       status' — see `lead-convertible?` — a lead must be exactly
       `:qualified`, converting is not itself a pipeline-shaped forward
       step.")

(def allowed-source-classes
  #{:crm-activity-log :e-signature-system :billing-system-webhook})

(def discount-authority-tiers
  "Ordered pricing-authority scale, low to high."
  [:tier/rep :tier/senior-rep :tier/director])

(def max-discount-pct
  {:tier/rep 10 :tier/senior-rep 20 :tier/director 40})

(def subscription-feature-tiers
  "Ordered SaaS entitlement scale, low to high."
  [:tier/basic :tier/pro :tier/enterprise])

(def feature-catalog
  "Which features each subscription tier unlocks, cumulative by tier
  rank (a `:tier/pro` account is entitled to everything `:tier/basic`
  is, plus its own additions)."
  {:tier/basic      #{:contacts :opportunities}
   :tier/pro        #{:contacts :opportunities :forecasting :api-access}
   :tier/enterprise #{:contacts :opportunities :forecasting :api-access
                       :sso :custom-objects}})

(def pipeline-stage-order
  [:prospecting :qualification :proposal :negotiation :closed-won])

(def exit-stages #{:closed-lost})

(def lead-status-order
  "Ordered lead-qualification lifecycle, low to high. `:qualified` is the
  terminal ordered stage — `:converted` is reached only via
  `lead-convertible?`'s dedicated gate, never as a `pipeline`-shaped
  forward step (see namespace docstring point 5)."
  [:new :working :qualified])

(def lead-exit-statuses
  "Reachable from any non-terminal lead status, same escape-hatch shape
  as `exit-stages`."
  #{:disqualified})

(def ^:private discount-rank
  (into {} (map-indexed (fn [i t] [t i])) discount-authority-tiers))

(def ^:private feature-tier-rank
  (into {} (map-indexed (fn [i t] [t i])) subscription-feature-tiers))

(defn discount-authority-at-least? [rep-tier required-tier]
  (>= (get discount-rank rep-tier -1) (get discount-rank required-tier 0)))

(defn feature-tier-at-least? [account-tier required-tier]
  (>= (get feature-tier-rank account-tier -1) (get feature-tier-rank required-tier 0)))

(defn class-allowed? [source-class]
  (contains? allowed-source-classes source-class))

(defn discount-authorized?
  "True iff `rep-tier` may apply `discount-pct` without escalation."
  [rep-tier discount-pct]
  (<= (or discount-pct 0) (get max-discount-pct rep-tier 0)))

(defn feature-entitled?
  "True iff `account-tier`'s feature-catalog includes `feature`."
  [account-tier feature]
  (contains? (get feature-catalog account-tier #{}) feature))

(defn lead-convertible?
  "True iff `lead-status` may be converted into a Contact + Opportunity.
  Deliberately narrower than 'is this a valid forward lead-status step':
  only the single terminal ordered status `:qualified` converts — a
  `:new` or `:working` lead skipping straight to a won deal is exactly
  the kind of unauthorized-skip this fleet's stage-sequence gates exist
  to block."
  [lead-status]
  (= lead-status :qualified))

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers."
  []
  {:source-classes allowed-source-classes
   :discount-authority-tier-count (count discount-authority-tiers)
   :subscription-feature-tier-count (count subscription-feature-tiers)
   :pipeline-stage-count (count pipeline-stage-order)
   :lead-status-count (count lead-status-order)
   :note (str "R0 scope: 3 provenance classes, "
              (count discount-authority-tiers)
              "-level discount-authority scale, "
              (count subscription-feature-tiers)
              "-level subscription feature-tier scale, "
              (count pipeline-stage-order)
              "-stage linear sales pipeline plus 1 exit stage, "
              (count lead-status-order)
              "-status lead-qualification lifecycle plus 1 exit status. "
              "Extend only by appending a documented provenance class, "
              "authority tier, feature, or status — never fabricate any.")})
