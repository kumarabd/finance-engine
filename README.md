# Finance engine

A PostgreSQL backend for understanding personal spending. Spends are the primary records; categories, tags, merchants, account references, and receipt or statement evidence provide context. HTTP clients and agents using MCP share the same operations and finance rules.

## Implemented scope

- Expenses, refunds, and excluded transfers; exact amounts and category splits.
- Create, read, correct, delete, restore, and audit records; atomic bulk operations.
- Categories, tags, and merchants, including rename, merge, and reassignment.
- Supporting evidence references with attach/detach operations.
- Filtered search, pagination, CSV export, and spending totals, trends, and period comparisons.
- All **42 operations** available through both HTTP and MCP, with owner isolation, retry protection, and version checks.

This iteration builds the backend. Parsing uploads belongs to the existing agent system. Web, iOS, and Android clients, budgeting, balances, bank synchronization, and currency conversion are outside this iteration.

## Run

Requires Go 1.26.1 or later and PostgreSQL with `timescaledb` and `vector` already enabled. The service verifies both extensions and applies embedded migrations on startup. The database role must be able to create the finance schema, tables, indexes, and Timescale hypertables.

Copy `.env.example` to `.env`, configure `DATABASE_URL`, and export its values in your shell. The binary does not load `.env` automatically. For local development, set `DEV_VERIFIED_USER` to a development identity; this override requires a loopback listener.

```sh
make run
```

The default address is `127.0.0.1:8091`. With the development identity configured:

```sh
curl http://127.0.0.1:8091/healthz

curl http://127.0.0.1:8091/api/v1/operations/spends_create \
  -H 'Content-Type: application/json' \
  -d '{"idempotency_key":"coffee-001","spend":{"occurred_on":"2026-10-04","kind":"expense","amount_minor":450,"currency":"USD","description":"Coffee"}}'

curl http://127.0.0.1:8091/api/v1/operations/spending_analyze \
  -H 'Content-Type: application/json' \
  -d '{"from":"2026-10-01","to":"2026-10-31","group_by":"category"}'
```

## Interfaces

| Interface | Purpose |
| --- | --- |
| `POST /api/v1/operations/{operation}` | Typed JSON input and result for every operation |
| `/mcp` | MCP Streamable HTTP; the same operation names, schemas, and results |
| `GET /api/v1/capabilities` | Discover operation names, descriptions, and schemas |
| `GET /api/v1/openapi.json` | Generated OpenAPI 3.1 contract |
| `GET /healthz`, `GET /readyz` | Process health and database reachability |

See [operations and examples](docs/operations.md), the [generated contract](api/openapi.json), and [architecture](docs/architecture.md). Every write requires an `idempotency_key`; editing an existing record also requires its `expected_version`.

Production access goes through the trusted identity router. It verifies the caller's bearer token, removes client-supplied identity headers, then sets `X-Nighthawk-Verified-User` and optionally `X-Nighthawk-Verified-Actor`. The private engine trusts these headers and does not verify JWTs itself. The router should forward `/finance/*` to the engine with `/finance` removed, including `/finance/mcp`. Restrict direct network access to the router. `TRUSTED_ORIGINS` configures allowed browser origins; it does not configure CORS response headers or authentication.

The earlier prototype's read-only `/api/v1/accounts` and `/api/v1/transactions` routes remain for compatibility with existing data. They use separate legacy tables, do not reflect new spend writes, and are described in [the legacy contract](api/legacy.openapi.yaml). New integrations should use the operation API. No client integration with that API is implemented here.

## Development checks

```sh
make check                         # Format, unit tests, vet, build, contract drift
make contract                      # Regenerate api/openapi.json after API changes
TEST_DATABASE_URL='postgres://...' make integration
```

Integration tests require a dedicated disposable database with both extensions enabled. They apply migrations and create records under unique test owners; they do not clean up those records. The integration target runs the complete suite with the race detector and refuses to run without `TEST_DATABASE_URL`. Ordinary unit tests skip database-dependent tests when that variable is absent.

## Code structure

| Path | Responsibility |
| --- | --- |
| `cmd/finance-api` | Configuration, database startup, server lifecycle |
| `cmd/contract` | Generate the HTTP contract without a database |
| `internal/engine` | Records, validation, operations, transactions, queries, audit |
| `internal/transport` | HTTP and MCP adapters, identity and request boundaries |
| `internal/config` | Environment configuration |
| `internal/store/postgres` | Database connection helpers and ordered migrations |
| `internal/finance`, `internal/httpapi` | Legacy prototype models and read-only routes |
| `api`, `docs` | Generated contracts and integration semantics |

Add a numbered migration for schema changes. Do not edit migrations already applied to a database.

## Deploying

- **Image:** `docker build -t finance-engine .` (distroless, non-root, about 5 MB). `.github/workflows/docker-publish.yml`
  publishes `ghcr.io/<owner>/finance-engine` on pushes to `main`. Set `DATABASE_URL`; `LISTEN_ADDR` defaults to
  `0.0.0.0:8091` in the image. Never set `DEV_VERIFIED_USER` outside local development.
- **Database:** its own Postgres database with `timescaledb` and `vector` enabled. The role only needs to own that
  database: migrations run as that role (verified, including the hypertables). Keep it separate from the older
  finance-mcp's `finance` database.
- **Cluster:** the `agent-harness` repo's tenant chart has a `financeEngine` block (off by default) that renders a
  Deployment, Service named `finance-engine`, Secret and NetworkPolicy, and its init-roles hook creates the
  `financeengine` role, database and extensions. The shared router forwards `/finance/` to that Service with the verified
  user stamped on (and strips any client-sent `X-Nighthawk-Verified-Actor`).
- **Contract fixtures:** `clients/fixtures/` holds real engine responses, decoded by both apps' tests.

## Errors

Every failure is `{"error":{"code","message"}}`. Clients decide what to do from the code:

| Code | HTTP | Meaning | Client action |
| --- | --- | --- | --- |
| `invalid_input` | 400 | The request is wrong | Show the message; don't retry |
| `unauthorized` | 401 | No verified identity | Sign in again |
| `not_found` | 404 | No such record for this user | |
| `conflict` | 409 | A stale `expected_version`, a reused idempotency key with different input, or a state rule | Refetch and reapply |
| `duplicate` | 409 | The name, alias or source identity already exists | Use the existing record, or skip the row (an import it already has) |
| `busy` | 503 | A transient database conflict | Retry with the **same** idempotency key |
| `internal` | 500 | Unexpected | Retry a few times with the same key, then give up |
