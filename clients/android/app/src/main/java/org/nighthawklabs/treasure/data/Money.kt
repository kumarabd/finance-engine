package org.nighthawklabs.treasure.data

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

object Money {
    /** "$1,234.50" style text for integer minor units, using the currency's own fraction digits (JPY has none). */
    fun format(minor: Long, currency: String, locale: Locale = Locale.getDefault()): String {
        val cur = runCatching { Currency.getInstance(currency) }.getOrNull() ?: return "$minor $currency"
        val digits = cur.defaultFractionDigits.coerceAtLeast(0)
        val f = NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = cur
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }
        return f.format(BigDecimal.valueOf(minor, digits))
    }

    /** Minor units as a plain number for drawing; display text always goes through [format]. */
    fun toDouble(minor: Long, currency: String): Double {
        val digits = runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
        return minor / Math.pow(10.0, digits.toDouble())
    }

    /** An amount as plain editable text ("12.50", "1200" for yen): no symbol, no grouping. */
    fun plain(minor: Long, currency: String): String {
        val digits = org.nighthawklabs.treasure.ingest.AmountParse.fractionDigits(currency)
        return BigDecimal.valueOf(minor, digits).toPlainString()
    }

    /** Text typed into an amount field ("12.50", "1,200", "5") as minor units of [currency]; null when it isn't a number. */
    fun parseMinor(text: String, currency: String): Long? =
        org.nighthawklabs.treasure.ingest.AmountParse.parseCell(text, org.nighthawklabs.treasure.ingest.AmountParse.fractionDigits(currency))?.minor

    /** Signed minor units: refunds flow back in, expenses out; transfers are neither. */
    fun signed(s: Spend): Long = when (s.kind) {
        "refund" -> s.amountMinor
        "expense" -> -s.amountMinor
        else -> 0
    }
}

/** Cash-register entry: digits are typed as minor units, so 1,2,5,0 is 12.50 (or 1,250 in a currency with no cents). */
data class AmountEntry(val digits: String = "") {
    val minor: Long get() = digits.toLongOrNull() ?: 0

    fun press(d: String): AmountEntry {
        if (d.isEmpty() || !d.all { it.isDigit() } || digits.length + d.length > MAX_DIGITS) return this
        if (digits.isEmpty() && d.all { it == '0' }) return this // no leading zeros
        return copy(digits = digits + d)
    }

    fun backspace() = copy(digits = digits.dropLast(1))

    companion object {
        const val MAX_DIGITS = 13 // well under the engine's 9007199254740991 ceiling
        fun of(minor: Long) = AmountEntry(if (minor > 0) minor.toString() else "")
    }
}
