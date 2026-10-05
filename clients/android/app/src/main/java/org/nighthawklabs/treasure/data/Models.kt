package org.nighthawklabs.treasure.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Nulls are omitted both ways: an omitted optional field on an update is cleared by the engine, so edits resend every field. */
val ApiJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

@Serializable
data class Allocation(
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("amount_minor") val amountMinor: Long,
)

/** `occurredOn` is a calendar date (yyyy-MM-dd) with no timezone. Amounts are positive minor units; `kind` carries direction. */
@Serializable
data class Spend(
    val id: String,
    val version: Long,
    @SerialName("occurred_on") val occurredOn: String,
    val kind: String, // expense | refund | transfer
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    @SerialName("merchant_id") val merchantId: String? = null,
    val description: String? = null,
    val allocations: List<Allocation>? = null,
    @SerialName("tag_ids") val tagIds: List<String>? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
    // Carried so an edit (a full replacement) never clears what this app doesn't show yet.
    @SerialName("account_ref") val accountRef: String? = null,
    @SerialName("original_spend_id") val originalSpendId: String? = null,
    val source: String? = null,
    @SerialName("source_record_id") val sourceRecordId: String? = null,
    @SerialName("evidence_ids") val evidenceIds: List<String>? = null,
)

/** The editable fields of a spend. Updates replace all of them. */
@Serializable
data class SpendInput(
    @SerialName("occurred_on") val occurredOn: String,
    val kind: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    @SerialName("merchant_id") val merchantId: String? = null,
    val description: String? = null,
    @SerialName("account_ref") val accountRef: String? = null,
    @SerialName("original_spend_id") val originalSpendId: String? = null,
    val source: String? = null,
    @SerialName("source_record_id") val sourceRecordId: String? = null,
    val allocations: List<Allocation>? = null,
    @SerialName("tag_ids") val tagIds: List<String>? = null,
    @SerialName("evidence_ids") val evidenceIds: List<String>? = null,
) {
    constructor(s: Spend) : this(
        s.occurredOn, s.kind, s.amountMinor, s.currency, s.merchantId, s.description, s.accountRef,
        s.originalSpendId, s.source, s.sourceRecordId, s.allocations, s.tagIds, s.evidenceIds,
    )
}

@Serializable
data class Page<T>(val items: List<T>, val total: Int = 0, @SerialName("next_offset") val nextOffset: Int? = null)

/** `spends_search` and `spends_export` (which takes the same filter). Every field is optional and omitted when unset. */
@Serializable
data class SearchInput(
    val limit: Int = 50,
    val offset: Int = 0,
    val from: String? = null,
    val to: String? = null,
    val currency: String? = null,
    val search: String? = null,
    val kind: String? = null,
    @SerialName("merchant_id") val merchantId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val uncategorized: Boolean? = null,
    @SerialName("tag_ids") val tagIds: List<String>? = null,
    @SerialName("account_ref") val accountRef: String? = null,
    @SerialName("evidence_id") val evidenceId: String? = null,
    @SerialName("original_spend_id") val originalSpendId: String? = null,
    @SerialName("min_amount_minor") val minAmountMinor: Long? = null,
    @SerialName("max_amount_minor") val maxAmountMinor: Long? = null,
    val state: String? = null, // active (default) | deleted | all
    val sort: String? = null,  // date_desc (default) | date_asc | amount_desc | amount_asc (amount sorting needs a currency)
)

typealias ExportInput = SearchInput

@Serializable
data class Versioned(val id: String, @SerialName("expected_version") val expectedVersion: Long)

/**
 * What a bulk edit changes on every selected spend. Unset fields are left alone; `categoryId = ""` means uncategorized and
 * `merchantId = ""` removes the merchant. Tags can be replaced outright, or added and removed (not both).
 */
@Serializable
data class SpendPatch(
    val kind: String? = null,
    @SerialName("merchant_id") val merchantId: String? = null,
    val description: String? = null,
    @SerialName("account_ref") val accountRef: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("tag_ids") val tagIds: List<String>? = null,
    @SerialName("add_tag_ids") val addTagIds: List<String>? = null,
    @SerialName("remove_tag_ids") val removeTagIds: List<String>? = null,
)

@Serializable
data class BulkUpdateInput(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val records: List<Versioned>,
    val patch: SpendPatch,
)

@Serializable
data class BulkLifecycleInput(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val records: List<Versioned>,
)

@Serializable data class PageInput(val limit: Int, val offset: Int)
@Serializable data class GetInput(val id: String)

@Serializable
data class HistoryInput(
    @SerialName("entity_type") val entityType: String,
    val id: String,
    val limit: Int = 50,
    val offset: Int = 0,
)

@Serializable
data class Change(
    val id: String,
    @SerialName("occurred_at") val occurredAt: String,
    val actor: String,
    val operation: String,
)

@Serializable
data class Dimension(
    val id: String,
    val name: String,
    val aliases: List<String>? = null,
    val version: Long? = null,
)

// Write operations. `idempotency_key` is reused only to retry the exact same operation.
@Serializable
data class CreateSpendInput(@SerialName("idempotency_key") val idempotencyKey: String, val spend: SpendInput)

@Serializable
data class UpdateSpendInput(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val id: String,
    @SerialName("expected_version") val expectedVersion: Long,
    val spend: SpendInput,
)

@Serializable
data class LifecycleInput(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val id: String,
    @SerialName("expected_version") val expectedVersion: Long,
)

@Serializable
data class CreateDimensionInput(@SerialName("idempotency_key") val idempotencyKey: String, val name: String)

@Serializable
data class UpdateDimensionInput(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val id: String,
    @SerialName("expected_version") val expectedVersion: Long,
    val name: String,
    val aliases: List<String>? = null, // a merchant's aliases must be sent back, or the update clears them
)

@Serializable
data class MergeDimensionInput(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val id: String,
    @SerialName("expected_version") val expectedVersion: Long,
    @SerialName("target_id") val targetId: String,
    @SerialName("target_version") val targetVersion: Long,
)

@Serializable
data class DeleteDimensionInput(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val id: String,
    @SerialName("expected_version") val expectedVersion: Long,
    @SerialName("replacement_id") val replacementId: String? = null,
    @SerialName("replacement_version") val replacementVersion: Long? = null,
)

@Serializable data class MergeResult(@SerialName("changed_spends") val changedSpends: Int = 0)

@Serializable data class ExportResult(val csv: String, @SerialName("next_offset") val nextOffset: Int? = null)

enum class DimensionKind(val plural: String, val singular: String) {
    Category("categories", "category"), Tag("tags", "tag"), Merchant("merchants", "merchant");

    val title get() = plural.replaceFirstChar { it.uppercase() }
}

// Import: one evidence record per source file, then spends in batches.
@Serializable data class EvidenceRecord(val id: String)

@Serializable
data class CreateEvidenceInput(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val title: String,
    @SerialName("source_ref") val sourceRef: String,
    @SerialName("media_type") val mediaType: String,
    val checksum: String,
    val notes: String,
)

@Serializable
data class BulkCreateInput(@SerialName("idempotency_key") val idempotencyKey: String, val spends: List<SpendInput>)

@Serializable data class SpendsResult(val items: List<Spend> = emptyList())
