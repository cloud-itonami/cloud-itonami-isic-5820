# Governance

`cloud-itonami-isic-5820` is an OSS open-business blueprint. Governance
covers both code and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- RevOps-LLM cannot directly transition an opportunity's stage, disclose
  account data, or resolve a dispute.
- SubscriptionGovernor remains independent of the advisor.
- hard governor violations (discount-authority-gate, entitlement-scope-
  gate, double-close-gate, stage-sequence-gate, source-provenance-gate,
  licensed-disclosure) cannot be overridden by human approval.
- a dispute request never auto-resolves, at any rollout phase.
- a `:closed-won` transition whose booked amount disagrees with the
  ASC 606 / IFRS 15 straight-line recompute always reaches a human,
  regardless of confidence.
- every commit, hold and disclosure event is auditable.
- no schema field exists for marketing-campaign attribution or support-
  ticket SLA — this actor is sales-pipeline + subscription-entitlement
  governance only.

## Decision Records

Architecture decisions live in `docs/adr/`.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit, support and
data-flow review.

Certified operators can lose certification for:

- bypassing governor checks
- applying a discount beyond a rep's authority tier
- activating a subscription feature/seat the account has not paid for
- booking revenue inconsistent with the ASC 606 / IFRS 15 straight-line
  recompute without human review
- disclosing account data to an uncontracted party
- misrepresenting a rep's discount-authority tier
