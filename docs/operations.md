# Operation reference

The canonical schemas are generated in [api/openapi.json](../api/openapi.json). The running server exposes the same schemas at `/api/v1/capabilities`, and MCP exposes them through `tools/list`.

## Transport contract

HTTP invokes `POST /api/v1/operations/{name}` with a JSON object and returns the result object directly with status 200. MCP invokes the identical tool name with the same argument object; successful calls return the result as structured content and JSON text. MCP is Streamable HTTP at `/mcp` and requires verified identity on every request through the router.

Every write includes `idempotency_key` (1–128 characters, no surrounding spaces). Reuse it only for an identical retry. Keys span all write operations for an owner. Reusing a key with different input or another operation returns `conflict`; failures roll back and do not reserve the key. A successful retry returns its original saved response, which can be older than the record's current state. Field order and whitespace do not matter; omitted defaults are normalized through typed inputs, but array order and supplied field values still matter.

Mutations to existing records require `id` and `expected_version` from the latest read. A stale version returns `conflict`; fetch the latest record, reconcile the desired change, and submit a new request key. Names, source identities, and active references are also checked. No write accepts owner or actor identity as arguments.

HTTP errors are `{ "error": { "code": "...", "message": "..." } }`: `invalid_input` (400), `unauthorized` (401), `not_found` (404), `conflict` (409), `too_large` (413), or `internal` (500). The browser-origin guard can return HTTP 403 before dispatch. MCP operation failures use `isError: true` and the same error envelope in text content; protocol-level validation may return a JSON-RPC error instead. Internal database details are not returned to callers.

## Complete operation set

| Area | Operations |
| --- | --- |
| Spends | `spends_create`, `spends_get`, `spends_update`, `spends_delete`, `spends_restore` |
| Bulk changes | `spends_bulk_create`, `spends_bulk_update`, `spends_bulk_delete`, `spends_bulk_restore` |
| Exploration | `spends_search`, `spends_export`, `spending_analyze` |
| Categories | `categories_create`, `categories_get`, `categories_list`, `categories_update`, `categories_delete`, `categories_restore`, `categories_merge` |
| Tags | `tags_create`, `tags_get`, `tags_list`, `tags_update`, `tags_delete`, `tags_restore`, `tags_merge` |
| Merchants | `merchants_create`, `merchants_get`, `merchants_list`, `merchants_update`, `merchants_delete`, `merchants_restore`, `merchants_merge` |
| Evidence | `evidence_create`, `evidence_get`, `evidence_list`, `evidence_update`, `evidence_delete`, `evidence_restore`, `evidence_attach`, `evidence_detach` |
| History | `history_list` |

## Spending records

Required fields are `occurred_on` (calendar date `YYYY-MM-DD`), `kind` (`expense`, `refund`, or `transfer`), `amount_minor` (positive integer), and `currency` (three uppercase letters). Currency codes are format-validated; no currency catalogue or conversion is applied. Callers must use the appropriate minor-unit exponent for the currency. Amounts are at most 9,007,199,254,740,991, which is exactly representable by JavaScript integers. A refund is a positive amount with kind `refund`, not a negative expense.

Optional fields are `description`, `merchant_id`, `account_ref`, `original_spend_id`, `source` paired with `source_record_id`, `allocations`, `tag_ids`, and `evidence_ids`. Omitted allocations become one uncategorized allocation. Each allocation is positive, categories cannot repeat, and allocations sum exactly to the spend amount. An omitted `category_id` means uncategorized. Tags and evidence IDs must be unique. Every referenced record belongs to the same owner and must be active.

`original_spend_id` optionally connects a refund to an expense in the same currency. Multiple and partial refunds are allowed; refunds are not capped by the original amount. Refund allocations and tags are explicit, not inherited. A transfer represents a movement excluded from spending totals, such as paying a credit card bill; it is not a two-sided accounting entry. An expense with active linked refunds cannot be deleted or changed to an incompatible kind/currency until those links are resolved.

The optional `(source, source_record_id)` pair prevents repeat ingestion of the same external record for an owner. Deleted records retain that identity; restore them instead of importing them again. Idempotency prevents repeated requests, while source identity prevents duplicate external records. No automatic fuzzy matching or merging of separately submitted spends is performed.

`spends_update` replaces all editable fields. Omitted optional fields are cleared. `spends_bulk_update` applies a shared partial patch to an explicit list of `{id, expected_version}` records; omitted patch fields retain their values. It can replace splits, assign one category, replace/add/remove tags, change date/amount/currency/description/merchant/account/refund relationship, or replace evidence links. An empty string clears an optional scalar and an empty array clears tags/evidence. When changing a split spend's amount, also supply allocations that sum to the new amount. Patches reject conflicting category/split or tag instructions.

All bulk operations accept 1–100 records and are atomic. Any invalid input, stale version, or failed reference rolls back the whole batch. Bulk delete orders linked refunds before their original expenses; bulk restore does the reverse. Single-record reads can retrieve deleted records; ordinary updates require active records. Delete and restore return the new version. Restoring checks that references remain valid.

### Example: create a category, then a split spend

Arguments to `categories_create`:

```json
{"idempotency_key":"category-groceries-001","name":"Groceries"}
```

Use its returned UUID in a call to `spends_create` (replace the example UUID):

```json
{
  "idempotency_key": "purchase-001",
  "spend": {
    "occurred_on": "2026-10-04",
    "kind": "expense",
    "amount_minor": 12500,
    "currency": "INR",
    "description": "Groceries and household supplies",
    "allocations": [
      {"category_id": "11111111-1111-4111-8111-111111111111", "amount_minor": 10000},
      {"amount_minor": 2500}
    ],
    "source": "statement",
    "source_record_id": "document-123:row-4"
  }
}
```

### Example: bulk categorize and add a tag

Arguments to `spends_bulk_update` (replace the UUIDs and version with actual values):

```json
{
  "idempotency_key": "organize-001",
  "records": [{"id": "22222222-2222-4222-8222-222222222222", "expected_version": 1}],
  "patch": {
    "category_id": "11111111-1111-4111-8111-111111111111",
    "add_tag_ids": ["33333333-3333-4333-8333-333333333333"]
  }
}
```

## Categories, tags, and merchants

Create accepts `name`; merchant create/update additionally accepts `aliases`. Update accepts `id`, `expected_version`, and the complete name/alias fields. Names are case-insensitively unique among active records of the same kind and owner. Merchant aliases also cannot collide with another active merchant's name or aliases. Lists support literal substring search, state, and pagination.

Delete of a referenced dimension requires `replacement_id` and `replacement_version`. Merge requires `target_id` and `target_version`. References on active and deleted spends are reassigned atomically; matching category allocations are combined and tags deduplicated. Affected spends receive new versions and audit entries. Merchant merge preserves the source name and aliases on the target. The source becomes deleted. Restoring it does not move references back and may require resolving an active name/alias collision first.

Categories are flat in this iteration. Tags express additional dimensions such as travel, household, or reimbursable without changing the primary category split.

## Evidence

Create/update accepts `title`, `source_ref`, and optional `media_type`, `checksum`, and `notes`. Update replaces those metadata fields. The reference is opaque: the engine never downloads, stores, verifies, or parses the document body. Access to the underlying document remains the document system's responsibility.

Attach/detach takes `id` and `expected_version` of the **spend**, plus `evidence_id`. Evidence deletion fails while linked unless `detach: true` is supplied. Explicit detachment updates and audits all affected spends, including deleted spends. Restore restores metadata only; previously detached links must be attached again.

## Search, pagination, and export

Spending filters support inclusive `from`/`to`, kind, currency, merchant, category or uncategorized, all supplied tags, account reference, evidence, original refund relationship, min/max amount, state, and literal substring search across description, merchant names/aliases, and account reference. `%` and `_` are ordinary search characters, not wildcard operators. A category search matches any allocation and returns the full spend with all its splits.

State is `active` by default, with `deleted` and `all` also available. Pages return `items`, `total`, and nullable `next_offset`. Limit defaults to 50, maximum 200; offset is 0–1,000,000. Sort is `date_desc` by default, with `date_asc`, `amount_desc`, and `amount_asc`; amount sorting requires a currency filter. UUID is the deterministic tie breaker. A page is a consistent database snapshot, but separate requests do not share a snapshot; concurrent edits can shift offset pages.

`spends_export` uses the same filters, order, and pagination and returns CSV text, total, and next offset. Each page has a header. Concatenating pages requires keeping only the first header. It includes descriptive merchant/category/tag names and evidence IDs. Potential spreadsheet formulas receive a leading apostrophe. Export is a human-readable data extract, not a backup/restore format.

## Spending analysis

`spending_analyze` uses the spending filters and `group_by`: `total`, `category`, `merchant`, `tag`, `day`, `week`, or `month`. Transfers are always excluded. Active records are used by default; requesting another state explicitly changes that population.

Each bucket contains currency, key, label, expense, refund, net, and distinct spend count. Aggregate money values are **decimal integer strings**, including zero, because totals can exceed JavaScript's exact integer range. Convert them to a decimal or big-integer type when computing. Currencies always remain separate. No conversion or cross-currency grand total is produced.

Refunds subtract on their own occurrence dates, not the original purchase date. Category filters aggregate only matching portions of split purchases. Tag groups can overlap and report `groups_overlap: true`; never sum them as a grand total. Counts can likewise overlap across category splits or tags. Untagged, uncategorized, and unknown-merchant buckets appear when relevant.

Time buckets are sparse: missing periods are absent, not explicit zero rows. Weeks start on Monday; month keys identify the first day. Dates undergo no timezone conversion. At most 2,000 groups are returned per period; exceeding that returns an error requesting narrower filters, never silently truncated totals.

For a comparison, provide `from`, `to`, `compare_from`, and `compare_to`. Other filters apply to both periods. Results contain `current` and `comparison` arrays, with absolute calendar keys for time groupings. Percentage changes, period alignment, and charts can be computed by clients later. Without comparison dates the comparison array is empty.

## History and limits

`history_list` takes `entity_type` (`spend`, `category`, `tag`, `merchant`, or `evidence`), `id`, and pagination fields. It returns newest-first before/after snapshots with actor, operation, and timestamp. Creation has a null before snapshot. History is readable for deleted records and has no mutation operation.

The engine allows up to 100 splits, tags, and evidence references on each spend and up to 100 records per batch. Requests are capped at 4 MiB and operations at 30 seconds. Merges and evidence detachment are atomic and can therefore time out for unusually large affected populations; they roll back rather than partially apply. There is no permanent-delete or automatic retention policy in this iteration.
