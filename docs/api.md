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
- **Escalated proposals resume in-process only — interrupted threads do
  not survive a restart.** `crm.operation`'s graph has a real
  human-in-the-loop interrupt (`:request-approval`) for low-confidence/
  revenue-mismatch/dispute cases; `POST /propose` surfaces that as
  `202 escalated` with a `thread-id`, and `POST /approve` resumes it
  (see below). The interrupted thread lives in the in-memory checkpointer
  on the single `actor` instance built at server-start: resumable for
  THIS process, lost on restart (no durable thread store).

If you need multi-tenant isolation, TLS, rate limiting, or a DURABLE
escalation thread store (surviving process restarts), that is future
work — do not assume this service already has it.

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

### Running via Docker

See the README's **[Running via Docker](../README.md#running-via-docker)**
section for the exact `docker build`/`docker run` commands (verified
end-to-end: real build, real container, real `curl` against `/health`
and `/dashboard`, real cleanup). Env vars above are read from the
container's environment unchanged — nothing is baked into the image.

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
    "approve": "/approve",
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
- `202` — the graph interrupted before human approval (SOFT/always-escalate: low confidence, revenue-mismatch-imminent, or any `dispute/request`, or a phase-approval gate): `{"decision": "escalated", "op": .., "subject": .., "thread-id": "...", "reason": "...", "confidence": .., "note": "..."}`. Resume it via `POST /approve` with this `thread-id` (see below).
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

### `POST /approve`

Auth required. Resumes an interrupted (`202 escalated`) graph run by feeding
the human decision back into the SAME OperationActor graph via
`langgraph.graph/run*` with `:resume? true` — the identical resume path
`crm.sim`'s `-main` exercises in-process (`run-op!`). This endpoint contains
no governance logic of its own.

**Honest scope**: the interrupted thread lives in the **in-memory checkpointer**
on the single `actor` instance built at server-start. It is resumable for the
lifetime of THIS process; it is **lost on restart** (there is no durable thread
store). A resumed run that re-escalates (multi-gate) returns `202` again with
the new reason — POST `/approve` again with the same `thread-id`.

**Body** (JSON): `{"thread-id": "<id from the 202>", "decision": "approve"|"reject", "by": "<optional approver id>"}`.

**Responses**:

- `200` — the resumed graph ran to completion (`:done`).
  - Committed (on `"approve"`): `{"decision": "committed", "thread-id": .., "approval": "approved", "record": {...}}`
  - Held (on `"reject"`, or `"approve"` that still fails a HARD gate): `{"decision": "held", "thread-id": .., "approval": "approved"|"rejected", "violations": [...], "confidence": 0.0-1.0}`
- `202` — the resumed run re-escalated (multi-gate): `{"decision": "escalated", "thread-id": .., "approval": .., "reason": "..", "note": ".."}`.
- `400` — missing `thread-id`/`decision`, or `decision` not `"approve"`/`"reject"`.
- `401` — missing/incorrect bearer token.
- `404` — `thread-id` is not a resumable interrupted run on this process (unknown, or already final, or lost to a restart).

**curl example**:

```bash
# Resume a dispute that escalated out of POST /propose with thread-id <T>
curl -s -X POST http://localhost:8080/approve \
  -H "Authorization: Bearer $ISIC5820_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"thread-id":"<T>","decision":"approve","by":"manager-1"}'
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

## Real-model RevOps-LLM advisor (`src/crm/llm_realmodel.clj`)

**Honest gap, and what closes it.** `src/crm/llm.cljc`'s RevOps-LLM
advisor is a SEALED, deterministic mock (`crm.llm/mock-advisor` /
`crm.llm/infer`) — it never calls a real language model. That was a
genuine, known gap toward real production operation. `crm.llm-realmodel`
adds the ADAPTER so an operator who supplies real model API credentials
gets a genuinely wired real-model advisor — **it does not itself supply
those credentials, and no real model call has been exercised anywhere in
this build** (this sandbox has none of `ANTHROPIC_API_KEY`/
`OPENAI_API_KEY`/etc.). See "What is verified vs. unverified" below
before relying on this in production.

`crm.llm.cljc` already had exactly the seam this needed:
`crm.llm/llm-advisor` wraps ANY `langchain.model/ChatModel` in the same
`crm.llm/Advisor` protocol (`-advise`) `crm.operation/build`'s `:advise`
node calls — same proposal shape in, same proposal shape out. This file
does not change that graph-facing contract at all; it only supplies a
second, real `ChatModel` implementation (`langchain.model/openai-model`/
`anthropic-model`, both already generic/already-tested upstream in
`kotoba-lang/langchain`) for `llm-advisor` to wrap.

### Env vars (mirrors this org's own `ITO_MODEL_*` convention)

This repo follows the SAME convention `orgs/gftdcojp/cloud-itonami`'s
`cloud_itonami.runtime` namespace already established for this lineage —
adapted with an `ISIC5820_`-prefix so sibling `cloud-itonami-isic-*`
actors on the same host/CI never collide on one another's model config:

| Env var | Required? | Meaning |
|---|---|---|
| `ISIC5820_MODEL_API_KEY` | **the sole trigger** | If unset/blank, `crm.http` runs the sealed mock advisor (unchanged default behavior). If set and non-blank, `crm.http` runs the real-model advisor instead. |
| `ISIC5820_MODEL_PROVIDER` | optional (default `openai`) | `openai` \| `anthropic` \| `openclaw` (any OpenAI-compatible endpoint at a custom URL — self-hosted gateway, Ollama, vLLM, etc.) |
| `ISIC5820_MODEL_URL` | required for `openclaw`, optional override for openai/anthropic | Chat-completions endpoint URL. `openai`/`anthropic` already have a public default hardcoded in `langchain.model`. |
| `ISIC5820_MODEL` | optional | Model name (default `gpt-4o-mini` for openai/openclaw, `claude-opus-4-8` for anthropic). |

```bash
ISIC5820_API_TOKEN=<token> \
ISIC5820_MODEL_API_KEY=<real key> \
ISIC5820_MODEL_PROVIDER=openai \
ISIC5820_MODEL=gpt-4o-mini \
  clojure -M:serve
```

### Startup log / `preflight`

`crm.http/-main`/`start-server!` always prints which advisor mode it
picked (`resolve-advisor!`) plus `crm.llm-realmodel/preflight`'s report —
**the same fail-visible discipline `warn-ephemeral-store!` already
established for storage**. `preflight` never attempts a live network
call and never prints the API key value (only `:api-key?`, a boolean):

```clojure
(crm.llm-realmodel/preflight {:provider "openclaw"})
;=> {:provider :openclaw, :url nil, :api-key? false, :model "gpt-4o-mini",
;    :ok? false, :missing [:ISIC5820_MODEL_URL :ISIC5820_MODEL_API_KEY]}
```

### What is verified vs. genuinely unverified

- **Verified**: `preflight`'s missing/present reporting across the full
  permutation matrix (openai/anthropic/openclaw, present/absent url,
  present/absent key, unknown provider, blank-string env values) — see
  `test/crm/llm_realmodel_test.clj`'s `preflight-*` tests.
- **Verified**: the exact JSON request this adapter sends (method,
  bearer header, model field, message shape) and its parsing of a
  well-formed OpenAI-compatible response — against a **real local
  `org.httpkit.server` stub** bound to an ephemeral port (a real HTTP
  round-trip over a real socket, not an in-process function fake) —
  including a full round-trip through `crm.llm/llm-advisor` ->
  `crm.llm/parse-proposal` into the exact proposal shape
  `crm.operation/build` expects, and the fallback path for a
  non-EDN-parseable model response.
- **Genuinely UNVERIFIED, and cannot be verified in this sandbox**:
  whether any ACTUAL target model API (OpenAI, Anthropic, or a real
  OpenAI-compatible gateway) accepts this exact request shape, or how it
  actually behaves — there are no model API credentials available here,
  and fabricating a fake endpoint would not prove anything about a real
  one. An operator who sets `ISIC5820_MODEL_API_KEY` for real gets a
  genuinely wired adapter, but its real-call behavior is unverified
  until they exercise it themselves.
