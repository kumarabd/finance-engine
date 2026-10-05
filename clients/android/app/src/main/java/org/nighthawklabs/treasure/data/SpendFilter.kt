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
    val originalSpendId: String? = null,
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
        tagIds = tagIds.ifEmpty { null }, accountRef = accountRef.trim().ifEmpty { null }, evidenceId = evidenceId, originalSpendId = originalSpendId,
        minAmountMinor = minAmountMinor, maxAmountMinor = maxAmountMinor,
        state = if (state == "active") null else state, sort = if (sort == "date_desc") null else sort,
    )

    companion object { val SORTS = listOf("date_desc", "date_asc", "amount_desc", "amount_asc") }
}

/** One removable summary of an active condition, shown under the search field. */
data class FilterChip(val id: String, val label: String, val clear: (SpendFilter) -> SpendFilter)

enum class DatePreset(val title: String) {
    Any("Any time"), ThisMonth("This month"), LastMonth("Last month"), Last30("Last 30 days"), Custom("Custom");

    /** The calendar-date range for a preset (Custom keeps whatever the user picked, so it has none). */
    fun range(today: java.time.LocalDate = java.time.LocalDate.now()): Pair<String?, String?>? = when (this) {
        Any -> null to null
        ThisMonth -> today.withDayOfMonth(1).toString() to today.toString()
        LastMonth -> today.withDayOfMonth(1).minusMonths(1).toString() to today.withDayOfMonth(1).minusDays(1).toString()
        Last30 -> today.minusDays(29).toString() to today.toString()
        Custom -> null
    }

    companion object {
        /** Which preset a from/to pair is, for showing the picker's current value. */
        fun matching(from: String?, to: String?, today: java.time.LocalDate = java.time.LocalDate.now()): DatePreset {
            if (from == null && to == null) return Any
            return listOf(ThisMonth, LastMonth, Last30).firstOrNull { it.range(today) == (from to to) } ?: Custom
        }
    }
}

/** The active narrowing conditions as chips. Names come from the caller so this stays independent of the directory. */
fun SpendFilter.chips(category: (String) -> String?, merchant: (String) -> String?, tag: (String) -> String?): List<FilterChip> {
    val out = mutableListOf<FilterChip>()
    if (search.isNotBlank()) out += FilterChip("search", "“${search.trim()}”") { it.copy(search = "") }
    if (from != null || to != null) {
        val label = when {
            from != null && to != null -> "${DayGroups.title(from)} – ${DayGroups.title(to)}"
            from != null -> "From ${DayGroups.title(from)}"
            else -> "Until ${DayGroups.title(to!!)}"
        }
        out += FilterChip("dates", label) { it.copy(from = null, to = null) }
    }
    if (kind != null) out += FilterChip("kind", when (kind) { "expense" -> "Expenses"; "refund" -> "Refunds"; else -> "Transfers" }) { it.copy(kind = null) }
    if (currency != null) out += FilterChip("currency", currency) { it.copy(currency = null, sort = if (it.sort.startsWith("amount")) "date_desc" else it.sort, minAmountMinor = null, maxAmountMinor = null) }
    if (uncategorized) out += FilterChip("category", "Uncategorized") { it.copy(uncategorized = false) }
    else if (categoryId != null) out += FilterChip("category", category(categoryId) ?: "Category") { it.copy(categoryId = null) }
    if (merchantId != null) out += FilterChip("merchant", merchant(merchantId) ?: "Merchant") { it.copy(merchantId = null) }
    for (t in tagIds) out += FilterChip("tag-$t", "#" + (tag(t) ?: "tag")) { f -> f.copy(tagIds = f.tagIds - t) }
    if (accountRef.isNotBlank()) out += FilterChip("account", "Account ${accountRef.trim()}") { it.copy(accountRef = "") }
    if (minAmountMinor != null || maxAmountMinor != null) {
        val c = currency ?: "USD"
        val label = when {
            minAmountMinor != null && maxAmountMinor != null -> "${Money.format(minAmountMinor, c)} – ${Money.format(maxAmountMinor, c)}"
            minAmountMinor != null -> "At least ${Money.format(minAmountMinor, c)}"
            else -> "At most ${Money.format(maxAmountMinor!!, c)}"
        }
        out += FilterChip("amount", label) { it.copy(minAmountMinor = null, maxAmountMinor = null) }
    }
    if (evidenceId != null) out += FilterChip("evidence", "Has this receipt") { it.copy(evidenceId = null) }
    return out
}
