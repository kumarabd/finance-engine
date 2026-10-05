package org.nighthawklabs.treasure.data

import java.util.UUID

/** One line of a split purchase: a category (null = uncategorized) and its share, in minor units. */
data class SplitRow(val categoryId: String?, val amountMinor: Long, val id: String = UUID.randomUUID().toString())

/** The engine's split rules: every share at least 1, a category once, shares add up to the amount exactly. */
object Splits {
    fun remaining(total: Long, rows: List<SplitRow>): Long = total - rows.sumOf { it.amountMinor }

    /** What is wrong with a split, or null when it is valid (or not a split at all). */
    fun problem(total: Long, rows: List<SplitRow>, currency: String): String? {
        if (rows.size < 2) return null
        if (rows.any { it.amountMinor < 1 }) return "Each split needs an amount."
        val cats = rows.map { it.categoryId ?: "" }
        if (cats.toSet().size != cats.size) return "Each category can only be used once."
        val left = remaining(total, rows)
        if (left > 0) return "${Money.format(left, currency)} left to assign."
        if (left < 0) return "Over by ${Money.format(-left, currency)}."
        return null
    }

    fun allocations(rows: List<SplitRow>) = rows.map { Allocation(it.categoryId, it.amountMinor) }

    /** Rows for editing an already-split spend (a single allocation is not a split). */
    fun rows(allocations: List<Allocation>?): List<SplitRow> =
        allocations?.takeIf { it.size > 1 }?.map { SplitRow(it.categoryId, it.amountMinor) } ?: emptyList()

    /** Two even halves, so it opens already adding up. The current category keeps the larger half. */
    fun start(categoryId: String?, total: Long) = listOf(SplitRow(categoryId, total - total / 2), SplitRow(null, total / 2))

    /** A new line takes whatever is left. */
    fun adding(rows: List<SplitRow>, total: Long) = rows + SplitRow(null, maxOf(0, remaining(total, rows)))
}
