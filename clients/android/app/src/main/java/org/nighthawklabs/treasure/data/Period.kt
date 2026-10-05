package org.nighthawklabs.treasure.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** A reporting window and the earlier window it is compared with. Dates are calendar dates (yyyy-MM-dd). */
enum class Period(val title: String, val trendGroup: String, val comparedWith: String) {
    Week("Week", "day", "last week"),
    Month("Month", "day", "last month"),
    Quarter("3 Months", "week", "the 3 months before"),
    Year("Year", "month", "last year");

    data class Window(val from: String, val to: String, val compareFrom: String, val compareTo: String)

    /**
     * The window runs from the start of the period to today. The comparison is the same stretch shifted back one period,
     * so a half-finished month is compared with the first half of last month, not all of it.
     */
    fun window(today: LocalDate = LocalDate.now(), firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY): Window {
        val (start, shift) = when (this) {
            Week -> today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek)) to { d: LocalDate -> d.minusDays(7) }
            Month -> today.withDayOfMonth(1) to { d: LocalDate -> d.minusMonths(1) }
            Quarter -> today.withDayOfMonth(1).minusMonths(2) to { d: LocalDate -> d.minusMonths(3) }
            Year -> today.withDayOfYear(1) to { d: LocalDate -> d.minusYears(1) }
        }
        return Window(start.toString(), today.toString(), shift(start).toString(), shift(today).toString())
    }
}

data class TrendPoint(val key: String, val minor: Long)

object Trend {
    /**
     * Every bucket key in [from, to], so days with no spending appear as zero bars. Weeks start on Monday and months on the
     * 1st, matching the engine's date_trunc.
     */
    fun keys(group: String, from: String, to: String): List<String> {
        var d = runCatching { LocalDate.parse(from) }.getOrNull() ?: return emptyList()
        val end = runCatching { LocalDate.parse(to) }.getOrNull() ?: return emptyList()
        d = when (group) {
            "week" -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            "month" -> d.withDayOfMonth(1)
            else -> d
        }
        val out = mutableListOf<String>()
        while (!d.isAfter(end) && out.size < 400) {
            out += d.toString()
            d = when (group) { "week" -> d.plusDays(7); "month" -> d.plusMonths(1); else -> d.plusDays(1) }
        }
        return out
    }

    /** Net spend per bucket for one currency, with gaps filled with zero. */
    fun series(buckets: List<Bucket>, currency: String, group: String, from: String, to: String): List<TrendPoint> {
        val byKey = buckets.filter { it.currency == currency }.associate { it.key to it.net }
        return keys(group, from, to).map { TrendPoint(it, byKey[it] ?: 0) }
    }
}

data class BreakdownRow(val key: String, val label: String, val minor: Long, val share: Double)

/** What tapping a breakdown row shows: the spends behind it, for the same window and currency. */
object Drill {
    enum class Dimension { Category, Merchant, Tag }

    /** Null for rows the list can't be narrowed to: the folded "Other", and merchants/tags that are simply absent. */
    fun filter(row: BreakdownRow, dimension: Dimension, window: Period.Window, currency: String): SpendFilter? {
        val base = SpendFilter(from = window.from, to = window.to, currency = currency)
        return when {
            row.key == "other" -> null
            dimension == Dimension.Merchant && row.key == "unknown" -> null
            dimension == Dimension.Tag && row.key == "untagged" -> null
            dimension == Dimension.Category && row.key == "uncategorized" -> base.copy(uncategorized = true)
            dimension == Dimension.Category -> base.copy(categoryId = row.key)
            dimension == Dimension.Merchant -> base.copy(merchantId = row.key)
            else -> base.copy(tagIds = listOf(row.key))
        }
    }
}

object Breakdown {
    /** Largest groups first, anything beyond [limit] folded into "Other". Groups with no net spend are left out. */
    fun top(buckets: List<Bucket>, currency: String, limit: Int = 5): List<BreakdownRow> {
        val rows = buckets.filter { it.currency == currency && it.net > 0 }.sortedByDescending { it.net }
        val total = rows.sumOf { it.net }
        if (total <= 0) return emptyList()
        val out = rows.take(limit).map { BreakdownRow(it.key, it.label, it.net, it.net.toDouble() / total) }.toMutableList()
        val rest = rows.drop(limit).sumOf { it.net }
        if (rest > 0) out += BreakdownRow("other", "Other", rest, rest.toDouble() / total)
        return out
    }
}

object Delta {
    /** "12% more than last month", or null when there is nothing to compare with. Neutral wording: spending more is not an error. */
    fun text(current: Long, previous: Long?, against: String): String? {
        if (previous == null || previous <= 0) return null
        val pct = Math.round(Math.abs(current - previous).toDouble() * 100 / previous).toInt()
        if (pct == 0) return "Same as $against"
        return "$pct% ${if (current > previous) "more" else "less"} than $against"
    }
}
