package org.nighthawklabs.treasure.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DayGroup(val day: String, val spends: List<Spend>) {
    /** Net signed total for the day, or null when the day mixes currencies (they are never combined). */
    val net: Pair<Long, String>?
        get() {
            val c = spends.firstOrNull()?.currency ?: return null
            return if (spends.all { it.currency == c }) spends.sumOf { Money.signed(it) } to c else null
        }
}

object DayGroups {
    /** Groups an already date-sorted list into consecutive days, keeping the server's order. */
    fun make(spends: List<Spend>): List<DayGroup> {
        val out = mutableListOf<DayGroup>()
        for (s in spends) {
            if (out.lastOrNull()?.day == s.occurredOn) out[out.lastIndex] = out.last().copy(spends = out.last().spends + s)
            else out += DayGroup(s.occurredOn, listOf(s))
        }
        return out
    }

    /** "Today", "Yesterday", or "Mon, Oct 4" for a calendar date string. */
    fun title(day: String, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String {
        val d = runCatching { LocalDate.parse(day) }.getOrNull() ?: return day
        return when (d) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> d.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))
        }
    }
}

object SpendOrder {
    /** Puts [spend] in place of any older copy, newest day first, and above same-day rows (the server sorts date, then id). */
    fun upsert(list: List<Spend>, spend: Spend): List<Spend> {
        val rest = list.filter { it.id != spend.id }
        val at = rest.indexOfFirst { it.occurredOn <= spend.occurredOn }.let { if (it < 0) rest.size else it }
        return rest.take(at) + spend + rest.drop(at)
    }
}

object CSVJoin {
    /** Each export page repeats the header row; keep it once. */
    fun join(pages: List<String>): String {
        val first = pages.firstOrNull() ?: return ""
        return pages.drop(1).fold(first) { acc, page -> acc + page.substringAfter('\n', "") }
    }
}
