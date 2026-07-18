(ns crm.dossier-import
  "Pure mapper: a `cloud-itonami-isic-8291` (\"dossier\") canonical company
  record -> a 5820 `crm.store` Account -- the DUNS-Number-analog wiring
  ADR-2607182200 named as a next step. `.cljc`, no I/O, no dependency on
  `dossier.*` code at all (this actor fleet's 'one blueprint = one actor'
  discipline: 5820 never imports another actor's namespace — it only
  agrees on a DATA SHAPE, the same shape `dossier.store`'s `Store`
  protocol's `company` already returns: `{:id :legal-name :jurisdiction
  :registration-no :status :source :flags}`). Any caller that already has
  such a map — from a real `dossier.houjin-bangou`/`dossier.companies-
  house` live lookup, from `dossier.store/company`, or from a hand-typed
  test fixture with the same keys — can feed it straight into
  `dossier-company->account` without this repo ever touching 8291's code.

  Two deliberate correctness guards, not just convenience:

    1. `:id` passes through UNCHANGED (`jpn-<corporateNumber>`,
       `gbr-<company_number>`, ...) rather than minting a new local
       `acct-*` id — this IS the whole point: one canonical id, shared
       across every cloud-itonami actor that references the same real
       company, instead of each actor inventing its own ad-hoc identity
       for the same real-world entity.

    2. `:active?` on a 5820 Account means 'has an active SUBSCRIPTION'
       (see `crm.policy/licensed-disclosure-violations`), NOT 'is the
       legal entity currently operating' — those are different facts. A
       freshly-imported prospect has no subscription yet regardless of
       whether the company itself is a live, operating business, so this
       mapper NEVER sets `:active? true` and NEVER invents a
       `:subscription-tier` — conflating 'real, active company' with
       'paying customer' would be exactly the kind of fabrication this
       fleet's governed actors exist to prevent. A dossier company whose
       own `:status` is anything other than `:active` (e.g. `:dissolved`)
       is refused outright — never imported as a live prospect."
  (:require [clojure.string :as str]))

(def ^:private known-registry-prefixes
  "The id-namespace prefixes `dossier.live-store` mints (ADR-2607110400 +
  addenda + ADR-2607182200) — kept in sync by hand since 5820 does not
  depend on 8291's code to look them up programmatically."
  #{"jpn-" "gbr-" "usa-" "lei-"})

(defn registry-id?
  "True iff `id` looks like a dossier-canonical id (one of the known
  registry prefixes) rather than a purely local, ad-hoc id like
  \"acct-acme\". A best-effort recognition check, not a validity proof —
  `dossier-company->account` does the real acceptance work below."
  [id]
  (boolean (and (string? id) (some #(str/starts-with? id %) known-registry-prefixes))))

(defn dossier-company->account
  "`dossier-company` is a `dossier.store` Company shape: `{:id :legal-name
  :jurisdiction :registration-no :status ...}`. Returns a 5820 Account map
  ready for `crm.store/with-accounts`, or `nil` if `dossier-company` is
  nil/unrecognized/not a live entity (never a guess, same fail-safe
  discipline `crm.llm/parse-proposal` uses for a malformed LLM response).

  `:subscription-tier` and `:active?` are deliberately NOT copied from
  anything on `dossier-company` — see ns docstring point 2. Callers that
  actually close a deal for this account do so through the EXISTING
  `:opportunity/transition-stage` -> `:closed-won` governed flow (and,
  operationally, the real billing/Stripe integration), never through this
  import step."
  [dossier-company]
  (when (and (map? dossier-company)
             (registry-id? (:id dossier-company))
             (seq (:legal-name dossier-company))
             (= :active (:status dossier-company)))
    {:id (:id dossier-company)
     :name (:legal-name dossier-company)
     :subscription-tier nil
     :active? false}))

(defn dossier-company->lead
  "Builds a 5820 Lead already account-matched to `dossier-company`'s
  canonical id (`:account-id`, ready for `crm.policy/lead-convertible-
  gate` once the lead reaches `:qualified` -- see `crm.facts/lead-
  convertible?`). `lead-id`/`owner-rep-id` are caller-supplied (this
  actor's own id scheme, not dossier's). `:status` always starts `:new`
  -- an imported company record is not itself evidence anyone has
  actually contacted or qualified this lead yet, so this function never
  starts a lead anywhere past the beginning of `crm.facts/lead-status-
  order`.

  IMPORTANT: dossier is a COMPANY registry, never a contact-PERSON
  directory (see `dossier.store`'s own ns docstring: no field for
  private-life data, no individual contact records at all) -- `:name`/
  `:email` here are the LEAD's own optional human-contact fields
  (`nil` if not yet known), never invented from the company record.
  `:company` is filled from the dossier legal name for display; `:source`
  is required from the caller and should be a real, honest provenance
  value (e.g. `:official-registry-lookup`), never fabricated.

  Returns `nil` under the same conditions `dossier-company->account`
  does (dossier-company nil/unrecognized/not a live legal entity) --
  never a lead pointed at a company id nothing can actually resolve."
  [dossier-company {:keys [lead-id owner-rep-id source name email]}]
  (when (dossier-company->account dossier-company)
    {:id lead-id
     :name name
     :email email
     :company (:legal-name dossier-company)
     :source source
     :status :new
     :account-id (:id dossier-company)
     :owner-rep-id owner-rep-id}))
