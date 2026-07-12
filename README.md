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

## Running as a service

`src/crm/http.clj` wraps this actor in a minimal, real HTTP service
(http-kit) — a governed, bearer-token-authenticated `POST /propose` +
RBAC-gated `GET /dashboard` + `GET /health`/`GET /` — so it can actually
run as a live process instead of only being invoked as a library.
**Auth is fail-closed**: the server refuses to start at all without an
explicit token.

```bash
ISIC5820_API_TOKEN=<your-token> clojure -M:serve   # port: $ISIC5820_HTTP_PORT, default 8080
# optional: ISIC5820_STORE_FILE=/path/to/db.edn -- disk-durable store (see docs/api.md's Persistence section)
```

**Persistence**: without `$ISIC5820_STORE_FILE`, `-main` runs against an
ephemeral in-memory store and prints a stderr WARNING — all state is
lost on restart. Set `ISIC5820_STORE_FILE` to a path to run against
`crm.file-store/FileStore` instead, a disk-durable store verified
end-to-end (real process, real HTTP commit, real kill, real restart,
data still there). See **[`docs/api.md`](docs/api.md)**'s Persistence
section for the full explanation, including why `crm.store/DatomicStore`
— despite its name — is *not* wired in as a durable option (it is an
in-process EAV atom with no connection URI, exactly as ephemeral as the
default store).

See **[`docs/api.md`](docs/api.md)** for the full endpoint reference
(request/response shapes, auth header, error codes, curl examples) and
its explicit honest-scope statement — this is a real network endpoint,
not yet production-hardened (single-process/single-tenant, no TLS
termination built in, no rate limiting).

### Running via Docker

The `Dockerfile` is a multi-stage build: a builder stage (JDK + Clojure
CLI) clones this repo's `:local/root` sibling deps
(`kotoba-lang/{crm,langgraph,langchain}` — public repos; no uberjar/
`tools.build` alias exists in this repo, so the builder just resolves
the same classpath `clojure -M:dev:serve` would use) and records it to
a file; the runtime stage is a minimal `eclipse-temurin:21-jre-alpine`
image that replays that classpath with a plain `java` invocation as a
non-root user — no Clojure CLI, build tool, or network access needed to
run the container. All secrets/config
(`ISIC5820_API_TOKEN`/`ISIC5820_STORE_FILE`/`ISIC5820_MODEL_API_KEY`
etc.) are read from the container's environment only, never baked into
the image; `ISIC5820_API_TOKEN` has no default, matching `crm.http`'s
own fail-closed contract. A `HEALTHCHECK` polls `GET /health`.

Build and run it (commands actually exercised end-to-end — real
build+run+curl+cleanup, not merely believed to work):

```bash
docker build -t cloud-itonami-isic-5820 .

mkdir -p /tmp/isic5820-store
docker run -d --name isic5820 \
  -p 18080:8080 \
  -e ISIC5820_API_TOKEN=test-token-abc123 \
  -e ISIC5820_STORE_FILE=/data/db.edn \
  -v /tmp/isic5820-store:/data \
  cloud-itonami-isic-5820

curl -s http://localhost:18080/health
# {"status":"ok","store":"reachable"}

curl -s "http://localhost:18080/dashboard?role=sales-manager&year=2026&month=7" \
  -H "Authorization: Bearer test-token-abc123"
# {"stage-counts":{...},"reached-counts":{...},"conversion-rates":{...},"revenue":{...}}

docker stop isic5820 && docker rm isic5820
```

`ISIC5820_STORE_FILE` is bind-mounted so `crm.file-store` snapshots
survive container restarts (see "Persistence" above) —
`/tmp/isic5820-store/db.edn` is written on the host after the first
request.

**Not included on purpose**: no `docker push`/registry step and no
cloud-deploy automation — CI (`.github/workflows/ci.yml`) only runs
`docker build .` as a build-breakage smoke test. Pushing to a registry
and deploying need registry credentials and a target-infrastructure
decision that are out of scope here.

### Real-model RevOps-LLM advisor (optional)

By default the RevOps-LLM advisor (`crm.llm`) is a SEALED, deterministic
mock — no real language model is ever called. `src/crm/llm_realmodel.clj`
adds a real OpenAI-compatible/Anthropic HTTP adapter, wired in via
`crm.http/resolve-advisor!`: set `ISIC5820_MODEL_API_KEY` and the server
uses it instead of the mock (unset/blank = unchanged sealed-mock default).

```bash
ISIC5820_API_TOKEN=<token> ISIC5820_MODEL_API_KEY=<real key> clojure -M:serve
# optional: ISIC5820_MODEL_PROVIDER=openai|anthropic|openclaw (default openai)
# optional: ISIC5820_MODEL_URL (required for openclaw), ISIC5820_MODEL
```

**Honest caveat**: this adapter's real-call behavior against an actual
model API has never been exercised in this build (no credentials are
available in the environment it was built in) — it is verified only
against `preflight`'s reporting logic and a local `org.httpkit.server`
stub standing in for the model API. See **[`docs/api.md`](docs/api.md)**'s
"Real-model RevOps-LLM advisor" section for exactly what is/isn't proven.

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
