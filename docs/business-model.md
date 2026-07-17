# Open Business Blueprint: cloud-itonami-isic-5820

This repository publishes an OSS business model for operating a
commercial CRM / subscription-commerce SaaS platform on itonami.cloud —
the Salesforce/HubSpot-class business.

## Classification

- Repository name: `cloud-itonami-isic-5820`
- Primary classification: ISIC Rev.4 5820 (Software publishing), narrowed
  to a specific business model: **licensing and operating a multi-tenant
  sales-pipeline + subscription-entitlement CRM platform for other
  businesses** — not software publishing in general (games, OS,
  enterprise ERP, etc.)
- Served domain: opportunity/deal pipeline management, subscription
  entitlement enforcement, discount-authority governance, governed
  disclosure, dispute handling — never marketing automation, never
  customer-service ticketing (see sibling-actor roadmap below)

## Customer

- SMBs/mid-market sales teams needing a governed, audit-ready CRM without
  building pricing-authority/entitlement logic themselves
- SaaS vendors needing to enforce that a deal close cannot itself grant
  subscription entitlement beyond what billing has actually provisioned
- other `cloud-itonami-{ISIC}` blueprint operators needing sales-pipeline
  governance as a licensed capability

## Problem

Commercial CRM platforms (Salesforce, HubSpot) route pricing/discount
decisions and revenue booking through configurable-but-optional workflow
rules, with no STRUCTURAL guarantee against a rep applying a discount
beyond their authority, a deal close silently over-provisioning
subscription features, a pipeline stage being skipped, an already-closed
deal being re-closed, or a revenue figure being booked inconsistent with
ASC 606 / IFRS 15 straight-line recognition. This platform seals the
RevOps-LLM into a single node and wraps it with an independent
SubscriptionGovernor, a human review workflow, and an immutable audit
ledger — the same discipline `cloud-itonami-isic-6209`, `-6920`, and
every other actor in this fleet apply to their own domain.

## Revenue Model

- Per-seat subscription (mirrors the platform's own product: a CRM
  business licenses seats to its own customers)
- Certification/audit fee for itonami.cloud operator certification
- Optional managed-hosting fee for operators who do not self-host —
  **live now**: Managed CRM (Starter), ¥80,000/月 flat, unlimited seats,
  Stripe-hosted checkout: <https://buy.stripe.com/4gM28q88r4pyaGIdIzbMQ0e>.
  Price point grounded in the 2026-07-17 five-competitor survey
  (`pricing-intel-20260717-01`: Salesforce / HubSpot / Pipedrive /
  Zoho CRM / Dynamics 365 Sales — all per-seat, $14–550/user/mo;
  recommended band ¥50k–150k/月 flat) — see the superproject's
  `90-docs/pricing-intelligence/` and ADR-2607172600.

## Honest scope (R0)

- Sales-pipeline (5-stage linear + 1 exit) and subscription-entitlement
  governance only.
- Straight-line ASC 606 / IFRS 15 revenue recognition only — no usage-
  based billing, contract modifications, or multi-element allocation.
- 3 discount-authority tiers, 3 subscription feature tiers, 3 evidence
  source classes — extend only by adding real, documented tiers/classes.

## Sibling-actor roadmap (not yet built)

Consistent with this fleet's narrowing discipline (one business model per
actor, never a monolith), a full Salesforce/HubSpot-parity suite is
planned as SEPARATE sibling actors sharing `kotoba-lang/crm`'s technical
commons (`kotoba.crm.pipeline`, `kotoba.crm.revrec`) rather than being
folded into this one:

- **Marketing-automation hub** (campaigns, email sequences, lead
  scoring) — HubSpot Marketing Hub / Salesforce Marketing Cloud
  equivalent.
- **Customer-service hub** (support cases, SLAs, knowledge base) —
  HubSpot Service Hub / Salesforce Service Cloud equivalent. Note
  `cloud-itonami-isic-6209` already covers IT-managed-services/helpdesk
  ticket routing specifically; a CRM-integrated customer-service hub
  would be a distinct, account/subscription-aware sibling, not a
  duplicate of 6209's scope.

Each would get its own ISIC-narrowed registry entry and its own ADR,
following this fleet's one-business-model-per-actor discipline.
