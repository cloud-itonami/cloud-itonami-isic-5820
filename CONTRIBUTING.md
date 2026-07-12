# Contributing

`cloud-itonami-isic-5820` accepts contributions to the OSS actor, governor
tests, documentation, examples and open business blueprint.

## Development

```bash
clojure -M:dev:test
clojure -M:lint
```

Keep changes small and include tests for governor, audit, store or
disclosure behavior.

## Rules

- Do not commit real customer/account data, real rep PII, or credentials.
- Keep production stage transitions and disclosures behind
  SubscriptionGovernor.
- Treat every new pipeline stage or subscription tier as high-risk: add
  tests for discount-authority-gate, entitlement-scope-gate, stage-
  sequence-gate, double-close-gate, licensed-disclosure, confidence
  floor, revenue-mismatch and audit logging.
- Never fabricate a spec-basis citation (FASB ASC 606 / IASB IFRS 15) to
  expand apparent coverage.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which governor invariant is affected
- how it was tested
- whether operator or certification docs need updates
