# HTTP API (`src/crm/http.clj`)

This is the first HTTP service layer over the `cloud-itonami-isic-5820`
actor. It is a **thin adapter**: it does not reimplement any governance
logic. Every governed decision is produced by the exact same
`crm.operation`/`crm.policy`/`crm.dashboard` code the library entry
points (`clojure -M:dev:run`, the test suite) already use.

## Honest scope (read this first)

This is a **real, network-callable service** — not a mock, not a demo
stub. It is also **not yet production-hardened**:

- **Single process, single tenant.** One JVM process, one in-memory or
  Datomic-backed `Store` injected at startup. There is no multi-tenant
  isolation — every caller with a valid token shares the same store.
- **No TLS termination.** This binds plain HTTP. Put a reverse proxy
  (nginx, Caddy, Cloudflare, an ALB, …) in front for HTTPS/TLS — that is
  explicitly the reverse proxy's job, not this process's.
- **No rate limiting.** Any caller with a valid bearer token can call
  `/propose` or `/dashboard` as fast as they like.
- **No request logging/observability** beyond whatever the operator adds
  externally (this file adds none beyond what `httpkit`/the JVM already
  emit on stdout/stderr).
- **No HTTP endpoint for human approval/rejection of an escalated
  proposal.** `crm.operation`'s graph has a real human-in-the-loop
  interrupt (`:request-approval`) for low-confidence/revenue-mismatch/
  dispute cases; `POST /propose` surfaces that as `202 escalated` with a
  `thread-id`, but there is currently no HTTP route to submit the
  approval/rejection that would resume that graph run. That resume path
  today only exists in-process (`langgraph.graph/run*` with
  `:resume? true`) — a follow-up task, not part of this first HTTP layer.

If you need multi-tenant isolation, TLS, rate limiting, or the approval
resume endpoint, that is future work — do not assume this service
already has it.

## Auth

All endpoints except `GET /` and `GET /health` require:

```
Authorization: Bearer <token>
```

The token is whatever value the server was started with — see
"Running the server" below. **Auth is fail-closed**:

- `crm.http/start-server!` throws (refuses to start) if given a
  nil/blank token.
- `clojure -M:serve` (`crm.http/-main`) reads `$ISIC5820_API_TOKEN` at
  startup; if it is unset or blank, it prints a fatal error to stderr
  and exits `1` **without starting the server at all**. There is no
  "runs with auth disabled" fallback anywhere in this code.
- Every request to a protected endpoint that doesn't present the exact
  matching bearer token gets `401 {"error": "unauthorized"}`.
- The token match itself is a **constant-time comparison**
  (`java.security.MessageDigest/isEqual` over UTF-8 bytes, not `=`) —
  closes the timing-attack gap recorded as an unmitigated finding in
  ADR-2607124600.

There is no built-in default/fallback token anywhere in `crm.http` —
you must supply one.

## Running the server

```bash
ISIC5820_API_TOKEN=<your-token> clojure -M:serve
# optional: ISIC5820_HTTP_PORT=9000 (default 8080)
# optional: ISIC5820_STORE_FILE=/path/to/db.edn  -- see "Persistence" below
```

If `$ISIC5820_STORE_FILE` is **unset**, `-main` starts the server against
a fresh `crm.store/seed-db` — the same small fictitious demo dataset
`crm.sim` uses (reps `rep-100`/`rep-200`/`rep-300`, accounts
`acct-acme`/`acct-basic`, three opportunities) — **and prints a WARNING
to stderr** that all state will be lost when the process exits. There is
no silent/default path into that mode.

### Persistence

`crm.http/-main` picks its `Store` backend from `$ISIC5820_STORE_FILE`:

- **Set** (e.g. `ISIC5820_STORE_FILE=/var/lib/isic5820/db.edn`) — runs
  against `crm.file-store/FileStore`: a full EDN snapshot of every
  rep/account/opportunity/subscription and the audit ledger is written to
  that path after every mutating call (write-then-rename, so a crash
  mid-write can't leave a truncated snapshot), and loaded back from that
  path the next time the process starts. **This is disk-durable and has
  been verified end-to-end**: a real `clojure -M:serve` process was
  started against a temp `ISIC5820_STORE_FILE`, a real `POST /propose`
  committed a stage transition over real HTTP, the process was killed
  (`kill`, not a graceful shutdown), restarted against the same file, and
  `GET /dashboard` showed the committed change was still there. See
  `crm.file-store`'s ns docstring for what this backend is NOT (not
  multi-writer-safe — one path, one process at a time; no query engine;
  no transaction history).
- **Unset** — runs against `crm.store/seed-db` (ephemeral, in-memory,
  discarded on exit) and prints a stderr WARNING every time.

**`crm.store/datomic-store`/`DatomicStore` is deliberately NOT wired into
`-main` at all**, despite this file's prior revision describing it as "a
real/persistent backend" — that description was inaccurate for how
`DatomicStore` is actually implemented in this repo. Its constructor
(`(->DatomicStore (langchain.db/create-conn schema))`) builds a plain
`(atom {:db ... :log []})` via `langchain.db` (a pure, dependency-free,
**in-process** EAV emulation — see that ns's docstring) — there is no
connection URI, no socket, no file, nothing that outlives the JVM heap.
It is Datomic-API-*shaped* (which is what makes
`test/crm/store_contract_test.clj`'s `MemStore ≡ DatomicStore` parity
test meaningful for a *future* backend swap), not Datomic-*backed*. As
shipped, selecting `DatomicStore` for `-main` would be exactly as
ephemeral as `seed-db` — just with a name that implies otherwise — so
this fix does not offer it as an `-main` option under any env var name
(e.g. an `ISIC5820_DATOMIC_URI`), to avoid exactly the "silently
substitute an in-memory store relabeled as persistent" trap.

Making `DatomicStore` genuinely durable is real follow-up work, not done
here: `crm.store` would need to accept an injected `:db-api` map (the
shape `langchain.db/api` already documents) instead of hardcoding calls
to `langchain.db` directly, pointed at either a real Datomic Local
process or a live kotoba-server pod via
`langchain.kotoba-db/kotoba-api` — both require infrastructure (a running
server, credentials) this sandboxed build environment does not have, so
that path was not attempted here rather than faked.

## Endpoints

### `GET /`

No auth. Info/discovery page (JSON, not HTML — this is a headless
service).

```bash
curl -s http://localhost:8080/
```

```json
{
  "actor": "cloud-itonami-isic-5820",
  "isic-code": "5820",
  "version": "0.1.0",
  "links": {
    "health": "/health",
    "propose": "/propose",
    "dashboard": "/dashboard",
    "api-docs": "docs/api.md"
  }
}
```

### `GET /health`

No auth. Liveness + a cheap store-connectivity check (calls
`crm.store/all-reps` against the injected store and reports whether it
threw).

```bash
curl -s http://localhost:8080/health
```

```json
{"status": "ok", "store": "reachable"}
```

Returns `503 {"status": "degraded", "store": "unreachable"}` if the
store call throws.

### `POST /propose`

Auth required. Runs a request through the **existing**
`crm.operation/build` OperationActor graph exactly as `crm.sim`/the test
suite already do: RevOps-LLM (`crm.llm`) drafts a proposal ->
SubscriptionGovernor (`crm.policy/check`) censors it -> the phase gate
(`crm.phase/gate`) applies rollout-phase restrictions -> commit / hold /
escalate. This endpoint reimplements none of that — it is a JSON
adapter over one `langgraph.graph/run*` call with a fresh thread-id.

**Request body** — top-level fields map 1:1 onto `crm.operation`'s
existing `request` map (see `crm.llm`'s ns docstring / `crm.sim` for the
canonical shapes this actually accepts), plus a nested `"context"` for
the caller's role/phase:

```json
{
  "op": "opportunity/transition-stage",
  "subject": "opp-100",
  "opportunity-id": "opp-100",
  "to-stage": "qualification",
  "rep-id": "rep-100",
  "discount-pct": 0,
  "source": {"class": "crm-activity-log", "ref": "op1"},
  "context": {"actor-id": "rep-1", "actor-role": "rep", "phase": 3}
}
```

Field notes:

- `"op"` — one of `"opportunity/transition-stage"`,
  `"disclosure/query"`, `"dispute/request"` (namespaced — the `/` is
  significant, it becomes `:opportunity/transition-stage` etc.).
- `"to-stage"`, `"disputed-field"`, `"claim"`, `"source".class`,
  `"activate-feature-tier"` (e.g. `"tier/enterprise"`),
  `"context".actor-role` — coerced from JSON strings to Clojure
  keywords (namespaced where the domain vocabulary is namespaced, e.g.
  `"tier/pro"` -> `:tier/pro`). Everything else (ids, amounts, dates,
  booleans, numbers) passes through as-is.
- `"context"` fields mirror what `crm.operation`'s `context` map already
  expects: `actor-id` (string), `actor-role` (one of `"rep"`,
  `"sales-manager"`, `"account-holder"`), `phase` (integer 0-3, see
  `crm.phase`; omitted = `crm.phase/default-phase` = `1`, the most
  conservative phase, same fail-closed default the library itself uses).

**Disclosure query example**:

```json
{
  "op": "disclosure/query",
  "subject": "acct-basic",
  "account-id": "acct-basic",
  "context": {"actor-id": "sub-1", "actor-role": "account-holder"}
}
```

**Dispute example**:

```json
{
  "op": "dispute/request",
  "subject": "opp-100",
  "disputed-field": "stage",
  "claim": "qualification",
  "context": {"actor-id": "mg-1", "actor-role": "sales-manager"}
}
```

**Responses**:

- `200` — the graph ran to completion (`:done`). Body:
  - Committed: `{"decision": "committed", "op": .., "subject": .., "record": {...}}`
  - Held (a HARD governor violation, or phase-disabled): `{"decision": "held", "op": .., "subject": .., "violations": [{"rule": "...", "detail": "..."}], "confidence": 0.0-1.0}`
- `202` — the graph interrupted before human approval (SOFT/always-escalate: low confidence, revenue-mismatch-imminent, or any `dispute/request`, or a phase-approval gate): `{"decision": "escalated", "op": .., "subject": .., "thread-id": "...", "reason": "...", "confidence": .., "note": "..."}`. See "Honest scope" above — there is no HTTP endpoint yet to submit the approval for this `thread-id`.
- `400` — missing/invalid JSON body, or missing required `"op"`.
- `401` — missing/incorrect bearer token.
- `500` — unexpected error (includes the exception message; this is a bug if it happens for a documented request shape).

**curl examples**:

```bash
# Clean transition -> commit
curl -s -X POST http://localhost:8080/propose \
  -H "Authorization: Bearer $ISIC5820_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"op":"opportunity/transition-stage","subject":"opp-100",
       "opportunity-id":"opp-100","to-stage":"qualification","rep-id":"rep-100",
       "source":{"class":"crm-activity-log","ref":"op1"},
       "context":{"actor-id":"rep-1","actor-role":"rep","phase":3}}'

# Discount-authority exceeded (rep-100 is :tier/rep, max 10%) -> held
curl -s -X POST http://localhost:8080/propose \
  -H "Authorization: Bearer $ISIC5820_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"op":"opportunity/transition-stage","subject":"opp-300",
       "opportunity-id":"opp-300","to-stage":"closed-won","rep-id":"rep-100",
       "discount-pct":25,"source":{"class":"crm-activity-log","ref":"op3"},
       "context":{"actor-id":"rep-1","actor-role":"rep","phase":3}}'
```

### `GET /dashboard`

Auth required, **plus** the exact same RBAC check `crm.policy`'s
`:pipeline/dashboard-query` rule already enforces for any caller
(restricted to `:sales-manager`, see `docs/adr/0001-architecture.md`'s
addendum) — this endpoint calls `crm.policy/check` directly with the
caller's role and 403s on a non-`:ok?` verdict, then (only if
authorized) calls `crm.dashboard/render`. The RBAC decision is never
bypassed or reimplemented here.

**Query params**: `role` (required — `sales-manager`/`rep`/
`account-holder`), `year` (required, integer), `month` (required,
integer) — `year`/`month` are the `as-of-date` threaded into
`crm.dashboard/revenue-rollup`'s ASC 606/IFRS 15 straight-line recompute.

**Responses**:

- `200` — `crm.dashboard/render`'s result as JSON (`stage-counts`,
  `reached-counts`, `conversion-rates`, `revenue`).
- `400` — missing/invalid `role`/`year`/`month`.
- `401` — missing/incorrect bearer token.
- `403` — `{"error": "forbidden", "violations": [...]}` — the role is
  not `:sales-manager`.

**curl examples**:

```bash
# sales-manager -> real data
curl -s "http://localhost:8080/dashboard?role=sales-manager&year=2026&month=7" \
  -H "Authorization: Bearer $ISIC5820_API_TOKEN"

# rep -> 403 forbidden (book-wide rollup is not a per-rep view)
curl -s "http://localhost:8080/dashboard?role=rep&year=2026&month=7" \
  -H "Authorization: Bearer $ISIC5820_API_TOKEN"
```

## Testing

`test/crm/http_test.clj` starts the real `crm.http` server on an
ephemeral port (`:port 0`) inside the test JVM via `start-server!`,
makes real HTTP requests against it with `java.net.http` (no mocked
handler shortcut), and stops the server in teardown. See that file for
the exact scenarios covered (health with no auth; `/propose` without a
token -> 401; a clean transition -> committed; a discount-authority
violation reusing `crm.sim`'s own op3 scenario -> held with violations;
`/dashboard` without the right role -> 403; `/dashboard` as
`sales-manager` -> real data).
