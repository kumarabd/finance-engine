package org.nighthawklabs.treasure.data

/** Everything the Spends list can be narrowed by, in one value the filter sheet edits and the chips summarise. */
data class SpendFilter(
    val search: String = "",
    val from: String? = null,
    val to: String? = null,
    val kind: String? = null,
    val currency: String? = null,
    val merchantId: String? = null,
    val categoryId: String? = null,
    val uncategorized: Boolean = false,
    val tagIds: List<String> = emptyList(),
    val accountRef: String = "",
    val minAmountMinor: Long? = null,
    val maxAmountMinor: Long? = null,
    val evidenceId: String? = null,
    val state: String = "active", // active | deleted | all
    val sort: String = "date_desc",
) {
    /** True for the unfiltered, newest-first list: the one whose first page is cached for offline. */
    val isDefault: Boolean get() = this == SpendFilter()

    /** Narrowing conditions only (not the view onto trash, and not the sort order). Drives the chips and the badge. */
    val activeCount: Int
        get() = listOf(
            search.isNotBlank(), from != null || to != null, kind != null, currency != null, merchantId != null,
            categoryId != null || uncategorized, tagIds.isNotEmpty(), accountRef.isNotBlank(),
            minAmountMinor != null || maxAmountMinor != null, evidenceId != null,
        ).count { it }

    /** Amount sorting is only defined within one currency. */
    val sortNeedsCurrency: Boolean get() = sort.startsWith("amount") && currency == null

    fun input(limit: Int = 50, offset: Int = 0) = SearchInput(
        limit = limit, offset = offset, from = from, to = to, currency = currency, search = search.trim().ifEmpty { null }, kind = kind,
        merchantId = merchantId, categoryId = if (uncategorized) null else categoryId, uncategorized = if (uncategorized) true else null,
        tagIds = tagIds.ifEmpty { null }, accountRef = accountRef.trim().ifEmpty { null }, evidenceId = evidenceId,
        minAmountMinor = minAmountMinor, maxAmountMinor = maxAmountMinor,
        state = if (state == "active") null else state, sort = if (sort == "date_desc") null else sort,
    )

    companion object { val SORTS = listOf("date_desc", "date_asc", "amount_desc", "amount_asc") }
}
