# Operator Guide

## 1. Fork and Run

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-5820
cd cloud-itonami-isic-5820
clojure -M:dev:test
clojure -M:dev:run
```

## 2. Production Checklist

- replace demo reps/accounts/opportunities/subscriptions with real,
  source-cited data
- configure Datomic Local, kotoba-server or an equivalent durable SSoT
- configure the LLM adapter through environment variables or secret manager
- define discount-authority tiers per rep, subscription feature tiers per
  account, and RBAC rules
- run `clojure -M:dev:test` / `clojure -M:lint`
- verify audit-ledger export
- document backup/restore and incident response
- get written finance/legal review on revenue-recognition policy (ASC 606 /
  IFRS 15) for the jurisdictions and contract types you serve

## 3. Operator Responsibilities

- verify a rep's discount-authority tier against your own approval
  matrix before registering them in the store
- verify an account's subscription tier against the billing system of
  record before registering it in the store — this actor never invents
  entitlement, only enforces what's already provisioned
- secure infrastructure and tenant isolation
- human review workflow for revenue-mismatch and dispute-request
  operations
- data-retention policy
- security updates

The OSS project provides software and an operating blueprint. It does not
verify a rep's employment status, an account's billing status, or
revenue-recognition policy on the operator's behalf.

## 4. Explicitly out of scope for R0

- Marketing automation (campaigns, email sequences, lead scoring) —
  candidate for a sibling `cloud-itonami-*` actor sharing
  `kotoba-lang/crm`'s pipeline commons, not yet built.
- Customer-service/support ticketing (cases, SLAs, knowledge base) —
  candidate for another sibling actor, not yet built.
- Usage-based/consumption billing, contract modifications, and multi-
  element revenue allocation (ASC 606 step 4) — `kotoba.crm.revrec`
  models straight-line recognition only.
