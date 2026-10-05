# Architecture

```text
Future web / iOS / Android clients        Existing agent system
               | HTTP                         | MCP or HTTP
               +------------+-----------------+
                            |
                 Trusted identity router
                            |
                 HTTP and MCP adapters
                            |
                  Shared operation registry
                            |
              Finance service and transactions
                            |
              PostgreSQL + TimescaleDB + pgvector
```

## Record model

| Record | Meaning |
| --- | --- |
| Spend | A dated expense, refund, or transfer with a positive amount and currency |
| Allocation | Part of a spend assigned to a category; allocations sum to its amount |
| Category | A spending classification; one spend can have multiple category splits |
| Tag | An independent label; many tags can describe a spend |
| Merchant | An optional normalized payee with searchable aliases |
| Evidence | Metadata and an opaque reference to a receipt, statement, or other document |
| Change | Before/after snapshots, actor, operation, and time for a mutation |
| Request | Durable write retry identity and its original result |

Canonical spends are relational PostgreSQL records. Their UUIDs remain stable when their dates change. Account references are optional text: a spend requires no account setup. Evidence has a many-to-many relationship with spends, so a statement can support many records and a purchase can link a receipt and statement. No receipt line items or document bodies are stored.

TimescaleDB stores the dated change history. Spending trends are computed from the canonical records using SQL grouping; dates alone do not require a separate time-series record or a spend hypertable. pgvector is enabled and the original scaffold includes a reserved semantic table, but there is no embedding generation, vector search, or provider dependency in this iteration.

Categories, tags, and merchants share a typed storage table while remaining distinct API operations. Their foreign references include owner identity. Application validation additionally checks record type, active state, allocation totals, and refund relationships.

## Shared operation boundary

`internal/engine/operations.go` registers every supported operation once. The registry supplies runtime input validation, MCP input/output schemas, capabilities, and the generated OpenAPI contract. Both transports invoke `Service.Execute`; adapters contain no spending rules. Read operations also use HTTP POST so the HTTP and MCP input objects are identical.

Each execution runs in one database transaction. Reads use a repeatable snapshot within that request. Writes acquire an owner-specific transaction advisory lock, which serializes an owner's writes across server replicas; versions reject stale caller edits. Different owners can write concurrently. This favors predictable personal-finance corrections and merges over high write throughput for a single owner.

All mutation results, audit entries, and retry records commit together. An error rolls back the entire operation, including a bulk operation. Keys are scoped to an owner across all write operations; a successful retry returns the original saved result even if the record has since changed. No automatic expiry is configured for retry records or history.

Soft deletion retains records and change history. Restore is an explicit operation. Reassignment and merges update references, including references on deleted spends, and audit those changes. Restoring a merged category or detached evidence does not reverse those later relationships.

## Identity and deployment

The existing router is the authentication boundary. It must remove incoming `X-Nighthawk-Verified-User` and `X-Nighthawk-Verified-Actor` headers before inserting verified values. The engine uses the owner for every query and the actor for audit entries. Clients cannot supply either through operation arguments. Database isolation is enforced by scoped queries and composite foreign keys, not PostgreSQL row-level security; the database role is trusted and must remain private.

`DEV_VERIFIED_USER` overrides identity only for a loopback listener. Production must leave it unset. MCP is stateless Streamable HTTP, with identity established separately on every HTTP request. The service limits request bodies to 4 MiB and operation execution to 30 seconds. Browser-origin checks apply to operation and MCP routes; authentication and any browser CORS configuration remain router responsibilities.

Migrations run once under a database advisory lock. Migration `0001` and its legacy read-only endpoints remain intact; `0002` creates the canonical spending model. There is no implicit import or synchronization between legacy transactions and spends. Existing clients will need an explicit future migration to the new operation API.

## Deliberate scope

The engine stores financial facts, classifies them, supports corrections, and computes views of past spending. Existing agents handle document parsing and call its operations. Budgets, balances, accounting ledgers, bank connections, recurring-payment automation, currency conversion, and client applications remain future product areas. No infrastructure or router deployment is performed by this repository's initialization.
