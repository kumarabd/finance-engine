package org.nighthawklabs.treasure.ingest

import org.nighthawklabs.treasure.data.Dimension
import org.nighthawklabs.treasure.data.Spend
import java.security.MessageDigest

/**
 * Learns from the user's own history, with no model and no network: it matches a description to an existing merchant,
 * suggests the category that merchant usually has, and flags rows that already exist.
 */
class Enricher(private val merchants: List<Dimension>, private val spends: List<Spend>) {

    /** [documentHash] is the file's hash for a receipt (one document, one spend), and null for a statement or CSV. */
    fun enrich(items: List<ParsedItem>, documentHash: String? = null): List<ParsedItem> {
        data class Name(val norm: String, val merchant: Dimension)
        val names = merchants.flatMap { m -> (listOf(m.name) + m.aliases.orEmpty()).map { Name(normalize(it), m) } }.filter { it.norm.length >= 3 }

        val merchantByDescription = HashMap<String, HashMap<String, Int>>()
        val categoryByMerchant = HashMap<String, HashMap<String, Int>>()
        val categoryByDescription = HashMap<String, HashMap<String, Int>>()
        fun HashMap<String, HashMap<String, Int>>.bump(a: String, b: String) { getOrPut(a) { HashMap() }.merge(b, 1, Int::plus) }
        for (s in spends) {
            if (s.deletedAt != null) continue
            val d = normalize(s.description.orEmpty())
            val cat = s.allocations?.takeIf { it.size == 1 }?.first()?.categoryId
            if (s.merchantId != null) {
                if (d.isNotEmpty()) merchantByDescription.bump(d, s.merchantId)
                if (cat != null) categoryByMerchant.bump(s.merchantId, cat)
            } else if (cat != null && d.isNotEmpty()) categoryByDescription.bump(d, cat)
        }
        fun top(counts: Map<String, Int>?): String? = counts?.maxByOrNull { it.value }?.key

        val merchantNameById = merchants.associate { it.id to it.name }
        // Rows already imported (same source identity): the engine would refuse them anyway.
        val importedBySource = HashMap<String, String>()
        for (s in spends) if (s.deletedAt == null && s.source == "import" && s.sourceRecordId != null) importedBySource[s.sourceRecordId] = s.id
        // Existing spends by date, amount, currency and kind. One-to-one: two identical coffees on the same day need two
        // existing rows to both count as duplicates.
        val existing = HashMap<String, MutableList<Spend>>()
        for (s in spends) if (s.deletedAt == null) existing.getOrPut("${s.occurredOn}|${s.amountMinor}|${s.currency}|${s.kind}") { mutableListOf() }.add(s)
        val occurrence = HashMap<String, Int>()

        return items.map { item ->
            var i = item
            val norm = normalize(i.description)
            val padded = " $norm "
            val byName = names.filter { padded.contains(" ${it.norm} ") }.maxByOrNull { it.norm.length }?.merchant
            val mid = byName?.id ?: top(merchantByDescription[norm])
            val matched = mid?.let { id -> merchants.firstOrNull { it.id == id } }
            i = if (matched != null) i.copy(merchantId = mid, merchantName = matched.name)
                else i.copy(merchantName = cleanName(i.description).ifEmpty { null })
            i = i.copy(categoryId = mid?.let { top(categoryByMerchant[it]) } ?: top(categoryByDescription[norm]))

            val base = "${i.date}|${i.amountMinor}|${i.currency}|${i.kind}|$norm"
            val n = occurrence.getOrDefault(base, 0); occurrence[base] = n + 1
            i = i.copy(fingerprint = fingerprint(i, norm, documentHash, n))

            // Duplicates. Only a match that agrees on WHAT it was (merchant or description) is trusted enough to start
            // unchecked. A coffee and a bus fare for the same price on the same day are two purchases.
            val key = "${i.date}|${i.amountMinor}|${i.currency}|${i.kind}"
            val already = importedBySource[i.fingerprint]
            val candidates = existing[key]
            if (already != null) i = i.copy(duplicateOf = already, include = false)
            else if (!candidates.isNullOrEmpty()) {
                val likely = candidates.indexOfFirst { likelySame(it, i, norm, merchantNameById) }
                if (likely >= 0) i = i.copy(duplicateOf = candidates.removeAt(likely).id, include = false)
                else {
                    val bare = candidates.indexOfFirst { it.merchantId == null && it.description.isNullOrEmpty() }
                    // An entry with no merchant or note: can't tell, so only hint.
                    if (bare >= 0) i = i.copy(possibleDuplicateOf = candidates.removeAt(bare).id)
                }
            }
            i
        }
    }

    /** Same date, amount and kind are given; this asks whether the two also agree on what was bought. */
    private fun likelySame(e: Spend, item: ParsedItem, norm: String, merchantNameById: Map<String, String>): Boolean {
        if (e.merchantId != null && e.merchantId == item.merchantId) return true
        val existingText = normalize(e.description.orEmpty()) + " " + (e.merchantId?.let { merchantNameById[it] }?.let(::normalize) ?: "")
        return overlap(norm, existingText)
    }

    companion object {
        /** At least half of the shorter side's words (3+ letters) appear on the other side. */
        fun overlap(a: String, b: String): Boolean {
            val x = a.split(" ").filter { it.length >= 3 }.toSet(); val y = b.split(" ").filter { it.length >= 3 }.toSet()
            if (x.isEmpty() || y.isEmpty()) return false
            val common = x.intersect(y).size
            return common > 0 && common * 2 >= minOf(x.size, y.size)
        }

        /**
         * Stable across re-uploads, and distinct for different transactions:
         * - a bank's own transaction id, when the file has one, is the identity;
         * - otherwise date, amount, currency, TYPE (an expense and a refund are different), description, and which repeat it is;
         * - a receipt also carries its file's hash, so two separate receipts for the same coffee never collapse into one. A
         *   statement does not, so overlapping statements still recognise the rows they share.
         */
        fun fingerprint(i: ParsedItem, norm: String, documentHash: String?, occurrence: Int): String {
            if (!i.externalId.isNullOrEmpty()) return hash("txn|${i.externalId}|${i.currency}")
            var base = "${i.date}|${i.amountMinor}|${i.currency}|${i.kind}|$norm"
            if (documentHash != null) base += "|doc:$documentHash"
            return hash("$base|$occurrence")
        }

        private val processor = Regex("""\b(sq|tst|pp|paypal|sp|pos|ach|pmt)\s*\*""")
        private val prefix = Regex("""^(pos|debit card purchase|card purchase|checkcard|purchase authorized on|recurring payment|online payment)\b""")
        private val notWord = Regex("""[^\p{L}\p{N}'&]+""")

        /** "SQ *BLUE BOTTLE #1234 OAKLAND CA" -> "blue bottle oakland ca". Processor prefixes, store numbers and any token with a digit go. */
        fun normalize(s: String): String =
            s.lowercase().replace(processor, " ").replace(prefix, " ")
                .split(notWord).filter { it.isNotEmpty() && it.none(Char::isDigit) }.joinToString(" ")

        /** A short display name for a new merchant: the first two words, capitalized. Imperfect on purpose; rename or merge later. */
        fun cleanName(s: String): String = normalize(s).split(" ").filter { it.isNotEmpty() }.take(2).joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

        fun hash(s: String): String = hash(s.toByteArray())
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
