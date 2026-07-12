# cloud-itonami-isic-5820

Open Business Blueprint for **ISIC Rev.4 5820**: software publishing,
narrowed to a **commercial CRM / subscription-commerce SaaS platform**
business — the Salesforce/HubSpot class of business — published as an
OSS business that any qualified operator can fork, deploy, run, improve
and sell.

Sales opportunities move through a governed pipeline and get
transitioned/closed by reps whose discount authority and whose accounts'
subscription entitlements are checked before anything commits. Built on
this workspace's [`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime and [`kotoba-lang/crm`](https://github.com/kotoba-lang/crm)'s
technical commons — the same actor pattern as
[`cloud-itonami-isic-6209`](https://github.com/cloud-itonami/cloud-itonami-isic-6209)
and [`cloud-itonami-isic-6920`](https://github.com/cloud-itonami/cloud-itonami-isic-6920).

> **Why an actor layer at all?** A RevOps-LLM is great at normalizing
> incoming deal activity and drafting stage-transition proposals — but it
> has **no notion of discount-authority limits, subscription entitlement
> scope, pipeline stage-sequence validity, or revenue-recognition
> correctness**. Letting it commit directly invites an unauthorized
> discount reaching booked revenue, a deal close silently granting
> subscription features the account never paid for, a pipeline stage
> being skipped to fabricate momentum, or a booked amount that disagrees
> with ASC 606/IFRS 15 straight-line recognition going unnoticed. This
> project seals the RevOps-LLM into a single node and wraps it with an
> independent **SubscriptionGovernor**, a human **review workflow**, and
> an immutable **audit ledger**.

## Scope (deliberately narrow — read this before anything else)

This actor governs **sales-pipeline stage transitions and subscription
entitlement enforcement**. It never provides marketing automation
(campaigns, email sequences, lead scoring) or customer-service ticketing
(support cases, SLAs) — those are planned as separate sibling
`cloud-itonami-*` actors sharing `kotoba-lang/crm`'s technical commons
(see `docs/business-model.md`'s roadmap section), never folded into this
one. Revenue recognition is modeled as straight-line ASC 606/IFRS 15
only (`kotoba-lang/crm`'s `kotoba.crm.revrec`) — never a bare "the LLM
decided this amount looks right".

## The core contract

```
request + injected role/rep/phase context
        │
        ▼
   ┌─────────────────┐  proposal      ┌──────────────────────────┐
   │ RevOps-LLM       │ ─────────────▶ │ SubscriptionGovernor      │  (independent system)
   │ (sealed)         │  draft +       │  discount-authority ·     │
   └─────────────────┘  source         │  entitlement-scope ·      │
                                        │  stage-sequence · revrec  │
                                        └──────────────────────────┘
                                              │
                                   commit / disclose only if allowed
                                              ▼
                                    append-only audit ledger
```

**Single invariant**: RevOps-LLM never transitions, discloses, or
resolves a dispute the SubscriptionGovernor would reject.

## Run

```bash
clojure -M:dev:test
clojure -M:dev:run
```

## Dashboard (pipeline funnel + revenue rollup)

`src/crm/dashboard.cljc` is a book-wide, cross-record aggregate view —
distinct from `crm.report`'s GOVERNED render of ONE opportunity's
disclosure-tier-gated columns for ONE account-holder. It answers "how is
the whole pipeline doing", not "what does this one customer see":

- **Pipeline funnel** — a point-in-time snapshot distribution
  (`stage-counts`) and cumulative "how far did opportunities get"
  (`reached-counts`) across every opportunity in the store, keyed by this
  actor's own pipeline stage order (`crm.facts/pipeline-stage-order` /
  `exit-stages` — the same stage vocabulary `stage-sequence-gate`
  already enforces).
- **Conversion rates** — stage-to-stage conversion between every
  consecutive pair of ordered stages.
- **Revenue rollup** — a ground-truth ASC 606/IFRS 15 straight-line
  recompute (`kotoba.crm.revrec/recognized-revenue-to-date`), summed
  across every ACTIVE subscription — never a cached or proposal-trusting
  sum.

This is gated as a new op, `:pipeline/dashboard-query`, RBAC-restricted
to `:sales-manager` only (see `docs/adr/0001-architecture.md`'s addendum
for the reasoning). **Honest scope**: this is a snapshot view only — no
time-series/trending, no cohort tracking, no stage-history log. It
inherits `kotoba.crm.funnel`'s own documented R0 limits verbatim
(entities currently in an exit stage, e.g. `:closed-lost`, are excluded
from `reached-counts`/conversion rates unless they carry an explicit
`:reached-stage` fact — this is never guessed) and `kotoba.crm.revrec`'s
own R0 limits (straight-line recognition only).

## Documentation

- `docs/business-model.md` — the OSS open-business blueprint
- `docs/DESIGN.md` — actor architecture (Japanese)
- `docs/operator-guide.md` — fork/run/production checklist
- `docs/adr/0001-architecture.md` — the authoritative architecture record

## License

AGPL-3.0 — see `LICENSE`.
