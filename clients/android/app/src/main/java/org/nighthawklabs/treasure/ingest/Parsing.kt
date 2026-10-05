package org.nighthawklabs.treasure.ingest

import java.time.LocalDate
import java.util.Currency
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/** One transaction read from a receipt, statement or CSV, waiting for the user's review. */
data class ParsedItem(
    val id: String = UUID.randomUUID().toString(),
    val date: String,                    // yyyy-MM-dd
    val description: String,
    val amountMinor: Long,               // positive; kind carries direction
    val kind: String,                    // expense | refund | transfer
    val currency: String,
    val sourceLine: String,
    val merchantId: String? = null,      // matched to an existing merchant
    val merchantName: String? = null,    // display name; for an unmatched row, the cleaned name a new merchant would get
    val categoryId: String? = null,
    val include: Boolean = true,
    val duplicateOf: String? = null,     // an existing spend that looks identical
    val fingerprint: String = "",        // stable across re-uploads; becomes the spend's source_record_id
    val externalId: String? = null,      // the bank's own transaction id, when the file has one
    val possibleDuplicateOf: String? = null, // might match an entry you already have; only a hint, the row stays selected
)

data class ParseOptions(
    val dayFirst: Boolean = false,
    /** Bank convention: money out is negative. Card convention (false): charges are positive, payments and credits negative. */
    val negativeIsSpend: Boolean = true,
    val currency: String = "USD",
    val today: LocalDate = LocalDate.now(),
) {
    val fractionDigits: Int get() = AmountParse.fractionDigits(currency)

    companion object {
        /** Whether the user's region writes the day before the month (04/10/2026 is 4 October). */
        fun localeDayFirst(locale: Locale = Locale.getDefault()): Boolean {
            val p = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
            val d = p.indexOf('d'); val m = p.indexOf('M')
            return d >= 0 && m >= 0 && d < m
        }
    }
}

object AmountParse {
    data class Value(val minor: Long, val negative: Boolean, val credit: Boolean = false, val debit: Boolean = false)
    data class Found(val value: Value, val range: IntRange)

    /** Decimal places a currency uses: 2 for most, 0 for yen and won, 3 for dinars. Unknown codes count as 2. */
    fun fractionDigits(currency: String): Int =
        runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrDefault(2).let { if (it < 0) 2 else it }

    // With decimals (2, 3), a token must have exactly that many, so store numbers, years and day numbers are never read as
    // money. Currencies without decimals (yen) have no such tell: see the trailing-run rule in `find`.
    private val tokens = java.util.concurrent.ConcurrentHashMap<Int, Regex>()
    private fun token(digits: Int) = tokens.getOrPut(digits) {
        Regex("""(?<![\w/.,])\(?-?[$€£₹¥￥₩]?\s?-?(?:\d{1,3}(?:[,.]\d{3})+|\d+)[.,]\d{$digits}\)?-?(?:\s?(?:CR|DR))?(?![\w/]|[.,]\d)""", RegexOption.IGNORE_CASE)
    }
    private val wholeToken = Regex("""^\(?-?[$€£₹¥￥₩]?-?\d[\d,]*\)?-?(?:CR|DR)?$""", RegexOption.IGNORE_CASE)

    /**
     * Every money amount in a line, left to right, with where it sits.
     * For a currency with no decimals an amount can't be told from a store number by its shape, so only the last
     * [trailing] numeric tokens at the end of the line count (the amount, plus a running balance when there is one).
     */
    fun find(line: String, digits: Int = 2, trailing: Int = 1): List<Found> =
        if (digits == 0) findWhole(line, trailing)
        else token(digits).findAll(line).mapNotNull { m -> parseToken(m.value, digits)?.let { Found(it, m.range) } }.toList()

    private fun findWhole(line: String, trailing: Int): List<Found> {
        val run = ArrayDeque<Found>()
        var end = line.length
        while (true) {
            var tokenEnd = end
            while (tokenEnd > 0 && line[tokenEnd - 1].isWhitespace()) tokenEnd--
            var tokenStart = tokenEnd
            while (tokenStart > 0 && !line[tokenStart - 1].isWhitespace()) tokenStart--
            if (tokenStart >= tokenEnd) break
            val text = line.substring(tokenStart, tokenEnd)
            val v = if (wholeToken.matches(text)) parseToken(text, 0) else null
            if (v == null) break
            run.addFirst(Found(v, tokenStart until tokenEnd))
            end = tokenStart
        }
        return run.toList().takeLast(trailing)
    }

    /** "$1,234.56", "-12.34", "(12.34)", "12.34-", "12.34 CR", "1.234,56". The last separator is the decimal point. */
    fun parseToken(raw: String, digits: Int = 2): Value? {
        val t = raw.trim()
        val all = t.filter { it.isDigit() }
        if (all.length < maxOf(digits + 1, 1)) return null
        val minor = all.toLongOrNull() ?: return null
        val upper = t.uppercase()
        val negative = t.contains("(") || t.startsWith("-") || t.endsWith("-") || t.contains("$-") || t.contains("-$")
        return Value(minor, negative, credit = upper.endsWith("CR"), debit = upper.endsWith("DR"))
    }

    /**
     * A lenient parse for a single CSV cell, where "12", "12.5", "-1,234.50" and "1.234,50" are all plausible, scaled to the
     * currency's own minor unit: "500" is 500 yen but 5.00 in dollars once typed as "500.00".
     */
    fun parseCell(raw: String, digits: Int = 2): Value? {
        val t0 = raw.trim()
        if (t0.isEmpty()) return null
        val negative = t0.contains("(") || t0.startsWith("-") || t0.endsWith("-")
        val t = t0.filter { it.isDigit() || it == '.' || it == ',' }
        if (t.isEmpty()) return null
        var whole = t
        var frac = ""
        val i = t.indexOfLast { it == '.' || it == ',' }
        if (digits > 0 && i >= 0) {
            val after = t.substring(i + 1)
            val separators = t.count { it == '.' || it == ',' }
            // One separator followed by exactly three digits is a thousands mark ("1,234"), anything else a decimal point.
            // In a three-decimal currency a lone ".345" is a fraction.
            val thousands = after.length == 3 && separators == 1 && !(digits == 3 && t[i] == '.')
            if (!thousands) { whole = t.substring(0, i); frac = after }
        }
        val w = whole.filter { it.isDigit() }.ifEmpty { "0" }
        val f = frac.take(digits).padEnd(digits, '0')
        val minor = (w + f).toLongOrNull() ?: return null
        return Value(minor, negative)
    }
}

/** Which currency a document is in: an explicit code or symbol, else the user's default. */
object CurrencyDetect {
    private val codes = listOf("USD", "EUR", "GBP", "INR", "JPY", "KRW", "CNY", "CAD", "AUD", "NZD", "CHF", "SEK", "NOK", "DKK", "SGD", "HKD", "MXN", "BRL", "ZAR", "KWD", "BHD")

    fun detect(text: String, default: String): String {
        for (code in codes) if (Regex("(?<![A-Za-z])$code(?![A-Za-z])").containsMatchIn(text)) return code
        if (text.contains("€")) return "EUR"
        if (text.contains("£")) return "GBP"
        if (text.contains("₹")) return "INR"
        if (text.contains("₩")) return "KRW"
        if (text.contains("¥") || text.contains("￥") || text.contains("円")) return "JPY"
        if (text.contains("$") && default !in listOf("USD", "CAD", "AUD", "NZD", "SGD", "HKD", "MXN")) return "USD"
        return default
    }
}

object DateParse {
    private const val MONTHS = "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec"
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val iso = rx("""(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)""")
    private val numeric = rx("""(?<![\d/.])(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(\d{4}|\d{2})(?!\d)""") // OCR often adds a space: "10/04/ 2026"
    private val numericNoYear = rx("""^\s*(\d{1,2})[/.](\d{1,2})(?![\d/.])""")
    private val dayMonth = rx("""(?<!\w)(\d{1,2})(?:st|nd|rd|th)?\s+($MONTHS)[a-z]*\.?,?(?:\s+(\d{4}))?(?!\d)""")
    private val monthDay = rx("""(?<!\w)($MONTHS)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?(?!\d)""")

    data class Found(val date: String, val range: IntRange)

    /**
     * The first date in a line: yyyy-MM-dd text and where it sits. A missing year is the current one, or last year when that
     * would put the date more than 45 days in the future (a December transaction on a January statement).
     */
    fun find(line: String, o: ParseOptions, startOnly: Boolean = false): Found? {
        val found = mutableListOf<Found>()
        fun add(m: MatchResult, d: String?) { if (d != null) found += Found(d, m.range) }

        iso.find(line)?.let { m -> val (y, mo, d) = m.destructured; add(m, make(y.toInt(), mo.toInt(), d.toInt(), o)) }
        numeric.find(line)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); var y = m.groupValues[3].toInt()
            if (y < 100) y += 2000
            val dayFirst = if (a > 12) true else if (b > 12) false else o.dayFirst
            add(m, make(y, if (dayFirst) b else a, if (dayFirst) a else b, o))
        }
        numericNoYear.find(line)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            val dayFirst = if (a > 12) true else if (b > 12) false else o.dayFirst
            add(m, make(null, if (dayFirst) b else a, if (dayFirst) a else b, o))
        }
        dayMonth.find(line)?.let { m -> monthIndex(m.groupValues[2])?.let { mi -> add(m, make(m.groupValues[3].toIntOrNull(), mi, m.groupValues[1].toInt(), o)) } }
        monthDay.find(line)?.let { m -> monthIndex(m.groupValues[1])?.let { mi -> add(m, make(m.groupValues[3].toIntOrNull(), mi, m.groupValues[2].toInt(), o)) } }

        val best = found.minByOrNull { it.range.first } ?: return null
        if (startOnly && best.range.first > 12) return null
        return best
    }

    private fun monthIndex(s: String): Int? =
        listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(s.lowercase().take(3)).let { if (it < 0) null else it + 1 }

    private fun make(year: Int?, month: Int, day: Int, o: ParseOptions): String? {
        fun valid(y: Int) = runCatching { LocalDate.of(y, month, day) }.getOrNull()
        var y = year ?: o.today.year
        var d = valid(y) ?: return null
        if (year == null && d.isAfter(o.today.plusDays(45))) valid(y - 1)?.let { d = it; y -= 1 }
        return "%04d-%02d-%02d".format(y, month, day)
    }
}

object ReceiptParser {
    private val strong = listOf("grand total", "amount due", "balance due", "total due", "amount paid", "total")
    private val weak = listOf("subtotal", "sub total", "sub-total", "tax", "change", "cash", "tender", "tip", "gratuity", "visa", "mastercard", "card", "savings", "discount")
    private val skipMerchant = listOf("receipt", "invoice", "welcome", "thank", "tel", "phone", "www.", "http", "order", "table", "server", "cashier")

    /** One receipt is one spend: the total, the date, and a guess at the merchant from the top of the page. */
    fun parse(text: String, o: ParseOptions): List<ParsedItem> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val currency = CurrencyDetect.detect(text, o.currency)
        val digits = AmountParse.fractionDigits(currency)
        val total = total(lines, digits) ?: return emptyList()
        val date = lines.firstNotNullOfOrNull { DateParse.find(it, o)?.date } ?: o.today.toString()
        return listOf(ParsedItem(
            date = date, description = merchantLine(lines, o, digits), amountMinor = total, kind = "expense", currency = currency,
            sourceLine = lines.firstOrNull { l -> AmountParse.find(l, digits).any { it.value.minor == total } } ?: "",
        ))
    }

    private fun total(lines: List<String>, digits: Int): Long? {
        var strongHit: Long? = null
        var best: Long? = null
        for (l in lines) {
            val low = l.lowercase()
            val last = AmountParse.find(l, digits).lastOrNull()?.value ?: continue
            val isWeak = weak.any { low.contains(it) }
            if (strong.any { low.contains(it) } && !low.contains("subtotal") && !low.contains("sub total") && !low.contains("sub-total")) strongHit = last.minor
            else if (!isWeak) best = maxOf(best ?: 0, last.minor)
        }
        return strongHit ?: best
    }

    private fun merchantLine(lines: List<String>, o: ParseOptions, digits: Int): String {
        for (l in lines.take(6)) {
            val low = l.lowercase()
            if (l.count { it.isLetter() } < 3 || skipMerchant.any { low.contains(it) }) continue
            if (l.first().isDigit()) continue // street addresses, phone numbers, order numbers
            if (DateParse.find(l, o) != null || AmountParse.find(l, digits).isNotEmpty()) continue
            return l
        }
        return ""
    }
}

object StatementParser {
    private val skipStarts = listOf("opening balance", "closing balance", "balance forward", "balance brought", "brought forward", "carried forward",
        "previous balance", "new balance", "minimum payment", "payment due", "credit limit", "statement", "account number", "summary", "page ", "total")
    private val refundWords = listOf("refund", "reversal", "return", "chargeback", "cashback", "cash back")
    private val transferWords = listOf("transfer", "xfer", "payment", "autopay", "thank you", "zelle", "venmo cashout", "withdrawal to savings")

    /**
     * Guesses the sign convention: when most amounts are negative, money out is negative (a bank account); otherwise charges
     * are positive (a card). The user can flip it in the review screen.
     */
    fun detectNegativeIsSpend(text: String, o: ParseOptions): Boolean {
        var neg = 0; var pos = 0
        for (l in lines(text)) {
            if (DateParse.find(l, o, startOnly = true) == null) continue
            val v = AmountParse.find(l, o.fractionDigits, 1).firstOrNull()?.value ?: continue
            if (v.credit || v.debit) continue
            if (v.negative) neg++ else pos++
        }
        return neg > pos
    }

    fun parse(text: String, o: ParseOptions): List<ParsedItem> {
        val all = lines(text)
        val hasBalanceColumn = all.any { l ->
            val low = l.lowercase()
            low.contains("balance") && listOf("debit", "credit", "withdraw", "deposit", "amount").any { low.contains(it) } && AmountParse.find(l, o.fractionDigits).isEmpty()
        }
        val out = mutableListOf<ParsedItem>()
        for (line in all) {
            val first = DateParse.find(line, o, startOnly = true) ?: continue
            val amounts = AmountParse.find(line, o.fractionDigits, if (hasBalanceColumn) 2 else 1).filter { it.range.first > first.range.last }.toMutableList()
            var balance: IntRange? = null
            if (hasBalanceColumn && amounts.size >= 2) balance = amounts.removeAt(amounts.lastIndex).range // the running balance
            val a = pick(amounts.map { it.value }) ?: continue

            // Remove the date(s), amounts and balance; what remains is the description.
            val spans = (amounts.map { it.range } + listOf(first.range) + listOfNotNull(balance)).sortedByDescending { it.first }
            val sb = StringBuilder(line)
            for (r in spans) sb.delete(r.first, r.last + 1)
            var rest = sb.toString()
            DateParse.find(rest, o, startOnly = true)?.let { rest = rest.removeRange(it.range) } // posting date next to transaction date
            val desc = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").trim(' ', '-', '–', '*')
            val low = desc.lowercase()
            if (desc.isEmpty() || skipStarts.any { low.startsWith(it) }) continue

            val outflow = direction(a, o.negativeIsSpend)
            val kind = if (outflow) (if (transferWords.any { low.contains(it) }) "transfer" else "expense")
                       else (if (refundWords.any { low.contains(it) }) "refund" else "transfer") // deposits and card payments are not spending
            out += ParsedItem(date = first.date, description = desc, amountMinor = a.minor, kind = kind, currency = o.currency, sourceLine = line, include = kind != "transfer")
        }
        return out
    }

    /** With two amounts left (separate debit and credit columns) the flagged or nonzero one is the transaction. */
    private fun pick(values: List<AmountParse.Value>): AmountParse.Value? {
        val nonzero = values.filter { it.minor != 0L }
        return nonzero.firstOrNull { it.credit || it.debit } ?: nonzero.firstOrNull()
    }

    fun direction(v: AmountParse.Value, negativeIsSpend: Boolean): Boolean = when {
        v.debit -> true
        v.credit -> false
        else -> if (negativeIsSpend) v.negative else !v.negative
    }

    private fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
}

object CSVParser {
    /** RFC 4180-ish: quoted fields, doubled quotes, and commas, semicolons or tabs as the separator. */
    fun rows(text: String): List<List<String>> {
        val first = text.lineSequence().firstOrNull().orEmpty()
        var sep = ','
        var most = 0
        for (c in listOf(',', ';', '\t')) { val n = first.count { it == c }; if (n > most) { most = n; sep = c } }
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted -> if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false } else field.append(c)
                c == '"' -> quoted = true
                c == sep -> { row.add(field.toString()); field.clear() }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(field.toString()); field.clear()
                    if (row.any { it.isNotEmpty() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        row.add(field.toString())
        if (row.any { it.isNotEmpty() }) rows.add(row)
        return rows
    }

    /** Reads a bank export by its header names. Returns null when there is no recognizable date and amount column. */
    fun items(text: String, o: ParseOptions): List<ParsedItem>? {
        val all = rows(text)
        val header = all.firstOrNull()?.map { it.lowercase() } ?: return null
        fun col(names: List<String>) = header.indexOfFirst { h -> names.any { h.contains(it) } }.takeIf { it >= 0 }
        val dateCol = col(listOf("date", "posted")) ?: return null
        val amountCol = col(listOf("amount"))
        val debitCol = col(listOf("debit", "withdraw", "paid out", "money out"))
        val creditCol = col(listOf("credit", "deposit", "paid in", "money in"))
        if (amountCol == null && debitCol == null && creditCol == null) return null
        val descCol = col(listOf("description", "memo", "payee", "details", "merchant", "name", "narrative"))
        // An exact "id" only, so a "paid in" column is not mistaken for a transaction id.
        val idCol = header.indexOfFirst { h -> h == "id" || listOf("transaction id", "transaction no", "transaction ref", "txn id", "reference", "fitid").any { h.contains(it) } }.takeIf { it >= 0 }

        val out = mutableListOf<ParsedItem>()
        for (r in all.drop(1)) {
            fun cell(i: Int?) = i?.let { r.getOrNull(it) }.orEmpty()
            val date = DateParse.find(cell(dateCol), o)?.date ?: continue
            val digits = o.fractionDigits
            val amount = AmountParse.parseCell(cell(amountCol), digits); val debit = AmountParse.parseCell(cell(debitCol), digits); val credit = AmountParse.parseCell(cell(creditCol), digits)
            val desc = cell(descCol).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            val minor: Long; val outflow: Boolean
            when {
                debit != null && debit.minor != 0L -> { minor = debit.minor; outflow = true }
                credit != null && credit.minor != 0L -> { minor = credit.minor; outflow = false }
                amount != null && amount.minor != 0L -> { minor = amount.minor; outflow = StatementParser.direction(amount, o.negativeIsSpend) }
                else -> continue
            }
            val low = desc.lowercase()
            val kind = if (outflow) (if (listOf("transfer", "xfer", "payment", "autopay").any { low.contains(it) }) "transfer" else "expense")
                       else (if (listOf("refund", "reversal", "return", "chargeback").any { low.contains(it) }) "refund" else "transfer")
            out += ParsedItem(date = date, description = desc, amountMinor = minor, kind = kind, currency = o.currency, sourceLine = r.joinToString(","), include = kind != "transfer", externalId = cell(idCol).trim().ifEmpty { null })
        }
        return out
    }

    /** Sign convention for an amount column: most negative means money out is negative. */
    fun detectNegativeIsSpend(text: String, digits: Int = 2): Boolean {
        val all = rows(text)
        val i = all.firstOrNull()?.map { it.lowercase() }?.indexOfFirst { it.contains("amount") } ?: return true
        if (i < 0) return true
        val values = all.drop(1).mapNotNull { r -> r.getOrNull(i)?.let { AmountParse.parseCell(it, digits) } }
        return values.count { it.negative } > values.count { !it.negative }
    }
}
