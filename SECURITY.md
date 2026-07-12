# Security Policy

This project handles account/subscription data, rep discount-authority
claims, and booked-revenue figures. Treat vulnerabilities as potentially
high impact even when the demo data is synthetic — an unauthorized
discount, an over-provisioned entitlement, or a mis-booked revenue figure
has direct financial consequences.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- SubscriptionGovernor bypass (discount-authority-gate, entitlement-
  scope-gate, double-close-gate, stage-sequence-gate, licensed-
  disclosure)
- audit-ledger tampering
- over-disclosure beyond an account's subscription tier
- tenant/account isolation failures
- closing an opportunity without the required discount-authority or
  entitlement check

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

## Production Guidance

- Store secrets outside Git.
- Run governor tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for reps and service accounts.
- Alert on any discount-authority-gate, entitlement-scope-gate, or
  revenue-mismatch HOLD/escalate spike.
