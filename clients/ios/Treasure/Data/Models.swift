import Foundation

struct Allocation: Codable, Hashable {
    var categoryId: String?
    var amountMinor: Int64
    enum CodingKeys: String, CodingKey { case categoryId = "category_id", amountMinor = "amount_minor" }
}

/// `occurredOn` is a calendar date (yyyy-MM-dd) with no timezone; keep it a string, never round-trip it through Date.
struct Spend: Codable, Identifiable, Hashable {
    var id: String
    var version: Int64
    var occurredOn: String
    var kind: String            // expense | refund | transfer
    var amountMinor: Int64      // always positive; `kind` carries the direction
    var currency: String
    var merchantId: String?
    var description: String?
    var allocations: [Allocation]?
    var tagIds: [String]?
    var deletedAt: String?
    // Carried so an edit (a full replacement) never clears what this app doesn't show yet.
    var accountRef: String?
    var originalSpendId: String?
    var source: String?
    var sourceRecordId: String?
    var evidenceIds: [String]?
    enum CodingKeys: String, CodingKey {
        case id, version, kind, currency, description, allocations, source
        case occurredOn = "occurred_on", amountMinor = "amount_minor", merchantId = "merchant_id"
        case tagIds = "tag_ids", deletedAt = "deleted_at", accountRef = "account_ref"
        case originalSpendId = "original_spend_id", sourceRecordId = "source_record_id", evidenceIds = "evidence_ids"
    }
}

/// The editable fields of a spend. Updates replace all of them: anything left nil is cleared on the server.
struct SpendInput: Codable, Equatable {
    var occurredOn: String
    var kind: String
    var amountMinor: Int64
    var currency: String
    var merchantId: String?
    var description: String?
    var accountRef: String?
    var originalSpendId: String?
    var source: String?
    var sourceRecordId: String?
    var allocations: [Allocation]?
    var tagIds: [String]?
    var evidenceIds: [String]?
    enum CodingKeys: String, CodingKey {
        case kind, currency, description, allocations, source
        case occurredOn = "occurred_on", amountMinor = "amount_minor", merchantId = "merchant_id", accountRef = "account_ref"
        case originalSpendId = "original_spend_id", sourceRecordId = "source_record_id", tagIds = "tag_ids", evidenceIds = "evidence_ids"
    }

    init(occurredOn: String, kind: String, amountMinor: Int64, currency: String, merchantId: String? = nil, description: String? = nil,
         accountRef: String? = nil, originalSpendId: String? = nil, source: String? = nil, sourceRecordId: String? = nil,
         allocations: [Allocation]? = nil, tagIds: [String]? = nil, evidenceIds: [String]? = nil) {
        self.occurredOn = occurredOn; self.kind = kind; self.amountMinor = amountMinor; self.currency = currency
        self.merchantId = merchantId; self.description = description; self.accountRef = accountRef
        self.originalSpendId = originalSpendId; self.source = source; self.sourceRecordId = sourceRecordId
        self.allocations = allocations; self.tagIds = tagIds; self.evidenceIds = evidenceIds
    }

    init(_ s: Spend) {
        self.init(occurredOn: s.occurredOn, kind: s.kind, amountMinor: s.amountMinor, currency: s.currency, merchantId: s.merchantId,
                  description: s.description, accountRef: s.accountRef, originalSpendId: s.originalSpendId, source: s.source,
                  sourceRecordId: s.sourceRecordId, allocations: s.allocations, tagIds: s.tagIds, evidenceIds: s.evidenceIds)
    }
}

// Write operations. `idempotency_key` is reused only to retry the exact same operation.
struct CreateSpendInput: Encodable {
    var idempotencyKey: String
    var spend: SpendInput
    enum CodingKeys: String, CodingKey { case idempotencyKey = "idempotency_key", spend }
}
struct UpdateSpendInput: Encodable {
    var idempotencyKey: String
    var id: String
    var expectedVersion: Int64
    var spend: SpendInput
    enum CodingKeys: String, CodingKey { case idempotencyKey = "idempotency_key", id, expectedVersion = "expected_version", spend }
}
struct LifecycleInput: Encodable {
    var idempotencyKey: String
    var id: String
    var expectedVersion: Int64
    enum CodingKeys: String, CodingKey { case idempotencyKey = "idempotency_key", id, expectedVersion = "expected_version" }
}
struct CreateDimensionInput: Encodable {
    var idempotencyKey: String
    var name: String
    enum CodingKeys: String, CodingKey { case idempotencyKey = "idempotency_key", name }
}

struct Page<T: Decodable>: Decodable {
    var items: [T]
    var total: Int
    var nextOffset: Int?
    enum CodingKeys: String, CodingKey { case items, total, nextOffset = "next_offset" }
}

/// `spends_search` and `spends_export` (which takes the same filter). Every field is optional and omitted when unset.
struct SearchInput: Encodable {
    var limit = 50
    var offset = 0
    var from: String?
    var to: String?
    var currency: String?
    var search: String?
    var kind: String?
    var merchantId: String?
    var categoryId: String?
    var uncategorized: Bool?
    var tagIds: [String]?
    var accountRef: String?
    var evidenceId: String?
    var originalSpendId: String?
    var minAmountMinor: Int64?
    var maxAmountMinor: Int64?
    var state: String?   // active (default) | deleted | all
    var sort: String?    // date_desc (default) | date_asc | amount_desc | amount_asc (amount sorting needs a currency)
    enum CodingKeys: String, CodingKey {
        case limit, offset, from, to, currency, search, kind, uncategorized, state, sort
        case merchantId = "merchant_id", categoryId = "category_id", tagIds = "tag_ids", accountRef = "account_ref"
        case evidenceId = "evidence_id", originalSpendId = "original_spend_id"
        case minAmountMinor = "min_amount_minor", maxAmountMinor = "max_amount_minor"
    }
}

typealias ExportInput = SearchInput

struct Versioned: Encodable {
    var id: String
    var expectedVersion: Int64
    enum CodingKeys: String, CodingKey { case id, expectedVersion = "expected_version" }
}

/// What a bulk edit changes on every selected spend. Unset fields are left alone; `categoryId: ""` means uncategorized and
/// `merchantId: ""` removes the merchant. Tags can be replaced outright, or added and removed (not both).
struct SpendPatch: Encodable, Equatable {
    var kind: String?
    var merchantId: String?
    var description: String?
    var accountRef: String?
    var categoryId: String?
    var tagIds: [String]?
    var addTagIds: [String]?
    var removeTagIds: [String]?
    enum CodingKeys: String, CodingKey {
        case kind, description
        case merchantId = "merchant_id", accountRef = "account_ref", categoryId = "category_id"
        case tagIds = "tag_ids", addTagIds = "add_tag_ids", removeTagIds = "remove_tag_ids"
    }
}

struct BulkUpdateInput: Encodable {
    var idempotencyKey: String
    var records: [Versioned]
    var patch: SpendPatch
    enum CodingKeys: String, CodingKey { case idempotencyKey = "idempotency_key", records, patch }
}

struct BulkLifecycleInput: Encodable {
    var idempotencyKey: String
    var records: [Versioned]
    enum CodingKeys: String, CodingKey { case idempotencyKey = "idempotency_key", records }
}

struct Dimension: Codable, Identifiable, Equatable, Hashable {
    var id: String
    var name: String
    var aliases: [String]?
    var version: Int64?
}

struct PageInput: Encodable {
    var limit = 200
    var offset = 0
}

struct GetInput: Encodable { var id: String }

struct HistoryInput: Encodable {
    var entityType: String
    var id: String
    var limit = 50
    var offset = 0
    enum CodingKeys: String, CodingKey { case entityType = "entity_type", id, limit, offset }
}

struct Change: Decodable, Identifiable {
    var id: String
    var occurredAt: String
    var actor: String
    var operation: String
    enum CodingKeys: String, CodingKey { case id, actor, operation, occurredAt = "occurred_at" }
}
