package org.nighthawklabs.treasure.ingest

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.nighthawklabs.treasure.data.*
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.EngineApi
import org.nighthawklabs.treasure.net.call
import org.nighthawklabs.treasure.net.problem
import java.util.Locale
import java.util.UUID

sealed interface ImportPhase {
    data object Reading : ImportPhase
    data object Review : ImportPhase
    data object Saving : ImportPhase
    data class Done(val created: Int, val skipped: Int) : ImportPhase
    data class Failed(val message: String) : ImportPhase
}

data class ImportState(
    val phase: ImportPhase = ImportPhase.Reading,
    val doc: ExtractedDocument? = null,
    val items: List<ParsedItem> = emptyList(),
    val asStatement: Boolean = true,
    val negativeIsSpend: Boolean = true,
    /** Amounts are read in this currency's minor unit, so it is detected from the document and the user can correct it. */
    val currency: String = "USD",
    val progress: Float = 0f,
) {
    val selected get() = items.count { it.include }
    val duplicates get() = items.count { it.duplicateOf != null }
}

/**
 * One import from file or camera to saved spends: read, parse, enrich from the user's history, review, save.
 * Everything up to "save" runs on this device; only the confirmed spends are sent.
 */
class ImportSession(
    private val engine: EngineApi,
    private val directory: Directory,
    private val spends: SpendsStore,
    private val extractor: DocumentReader,
    private val defaultCurrency: () -> String = { java.util.Currency.getInstance(Locale.getDefault()).currencyCode },
) {
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()
    private var history: List<Spend> = emptyList()
    private var historyRange = ""

    // region Read and parse

    suspend fun startFile(uri: Uri) = begin(extractor.extract(uri))
    suspend fun startImages(uris: List<Uri>) = begin(if (uris.isEmpty()) null else extractor.extract(uris, "Scanned receipt"))

    suspend fun begin(doc: ExtractedDocument?) {
        if (doc == null) { _state.update { it.copy(phase = ImportPhase.Failed("Couldn't read that file.")) }; return }
        val currency = CurrencyDetect.detect(doc.text, defaultCurrency())
        val o = options(true, currency)
        val asStatement: Boolean
        val negative: Boolean
        if (doc.isCSV) { asStatement = true; negative = CSVParser.detectNegativeIsSpend(doc.text, o.fractionDigits) }
        else {
            val dated = doc.text.lines().count { DateParse.find(it, o, startOnly = true) != null && AmountParse.find(it).isNotEmpty() }
            asStatement = dated >= 3
            negative = StatementParser.detectNegativeIsSpend(doc.text, o)
        }
        _state.update { it.copy(doc = doc, asStatement = asStatement, negativeIsSpend = negative, currency = currency) }
        reparse()
    }

    private fun options(negativeIsSpend: Boolean, currency: String) = ParseOptions(ParseOptions.localeDayFirst(), negativeIsSpend, currency)

    /** Parse again after the user changes the document type or sign convention. Their per-row edits are not kept. */
    suspend fun reparse() {
        val s = _state.value
        val doc = s.doc ?: return
        val o = options(s.negativeIsSpend, s.currency)
        val raw = if (doc.isCSV) CSVParser.items(doc.text, o) ?: StatementParser.parse(doc.text, o)
                  else if (s.asStatement) StatementParser.parse(doc.text, o) else ReceiptParser.parse(doc.text, o)
        loadHistory(raw)
        val enriched = Enricher(directory.merchants.value.values.toList(), history).enrich(raw, if (doc.isCSV || s.asStatement) null else doc.hash)
        _state.update { it.copy(items = enriched, phase = ImportPhase.Review) }
    }

    fun setAsStatement(v: Boolean) { _state.update { it.copy(asStatement = v) } }
    fun setNegativeIsSpend(v: Boolean) { _state.update { it.copy(negativeIsSpend = v) } }
    fun setCurrency(v: String) { _state.update { it.copy(currency = v) } }

    /** Existing spends over the same dates, for spotting duplicates, plus what's already loaded, for learning categories. */
    private suspend fun loadHistory(raw: List<ParsedItem>) {
        val from = raw.minOfOrNull { it.date }; val to = raw.maxOfOrNull { it.date }
        if (from == null || to == null) { history = spends.state.value.spends; return }
        val range = "$from|$to"
        if (range != historyRange) {
            val found = mutableListOf<Spend>()
            var offset = 0
            while (found.size < 2000) {
                val r = engine.call<SearchInput, Page<Spend>>("spends_search", SearchInput(limit = 200, offset = offset, from = from, to = to))
                if (r !is Api.Ok) break
                found += r.value.items
                offset = r.value.nextOffset ?: break
            }
            history = found; historyRange = range
        }
        val known = history.map { it.id }.toSet()
        history = history + spends.state.value.spends.filter { it.id !in known }
    }

    // endregion

    fun toggle(id: String) = edit(id) { it.copy(include = !it.include) }
    fun setCategory(id: String, categoryId: String?) = edit(id) { it.copy(categoryId = categoryId) }
    fun setKind(id: String, kind: String) = edit(id) { it.copy(kind = kind) }
    private fun edit(id: String, f: (ParsedItem) -> ParsedItem) = _state.update { s -> s.copy(items = s.items.map { if (it.id == id) f(it) else it }) }

    // region Save

    suspend fun save() {
        val s = _state.value
        val doc = s.doc ?: return
        val chosen = s.items.filter { it.include }
        if (chosen.isEmpty()) return
        _state.update { it.copy(phase = ImportPhase.Saving, progress = 0f) }
        fun fail(msg: String) = _state.update { it.copy(phase = ImportPhase.Failed(msg)) }

        // The source document, as evidence. Optional: if the engine refuses it, the spends still go in.
        var evidenceId: String? = null
        val ev = engine.call<CreateEvidenceInput, EvidenceRecord>("evidence_create", CreateEvidenceInput(
            "ev-" + doc.hash, doc.name.take(200), "local-file:" + doc.hash, doc.mediaType, doc.hash, "Read on this device; the file itself is not uploaded."))
        when (ev) {
            is Api.Ok -> evidenceId = ev.value.id
            is Api.Failed -> {}
            else -> { fail(ev.problem ?: "Couldn't reach Treasure."); return }
        }

        // New merchants for names that matched nothing, once per name.
        val created = HashMap<String, String>()
        for (name in chosen.filter { it.merchantId == null }.mapNotNull { it.merchantName }.toSet()) {
            when (val r = directory.resolveMerchant(name)) {
                is Api.Ok -> r.value?.let { created[name] = it }
                else -> { fail(r.problem ?: "Couldn't reach Treasure."); return }
            }
        }

        val inputs = chosen.map { i ->
            SpendInput(
                occurredOn = i.date, kind = i.kind, amountMinor = i.amountMinor, currency = i.currency,
                merchantId = i.merchantId ?: i.merchantName?.let { created[it] }, description = i.description.ifEmpty { null },
                source = "import", sourceRecordId = i.fingerprint,
                allocations = i.categoryId?.let { listOf(Allocation(it, i.amountMinor)) },
                evidenceIds = evidenceId?.let { listOf(it) },
            )
        }

        var made = 0; var skipped = 0
        for (start in inputs.indices step 100) {
            val chunk = inputs.subList(start, minOf(start + 100, inputs.size))
            val key = "imp-" + Enricher.hash(chunk.mapNotNull { it.sourceRecordId }.joinToString(","))
            when (val r = engine.call<BulkCreateInput, SpendsResult>("spends_bulk_create", BulkCreateInput(key, chunk))) {
                is Api.Ok -> made += r.value.items.size
                is Api.Failed -> if (alreadyThere(r.code)) {
                    // Something in the batch already exists (the same statement imported before). The batch is atomic, so go one by one.
                    for (input in chunk) {
                        val one = engine.call<CreateSpendInput, Spend>("spends_create", CreateSpendInput("imp-" + (input.sourceRecordId ?: UUID.randomUUID().toString()), input))
                        when {
                            one is Api.Ok -> made++
                            one is Api.Failed && alreadyThere(one.code) -> skipped++
                            else -> { fail("Imported $made before a problem: ${one.problem}"); finish(); return }
                        }
                    }
                } else { fail(if (made > 0) "Imported $made before a problem: ${r.problem}" else r.problem ?: "Couldn't import."); finish(); return }
                else -> { fail(if (made > 0) "Imported $made before a problem: ${r.problem}" else r.problem ?: "Couldn't import."); finish(); return }
            }
            _state.update { it.copy(progress = minOf(start + 100, inputs.size).toFloat() / inputs.size) }
        }
        finish()
        _state.update { it.copy(phase = ImportPhase.Done(made, skipped)) }
    }

    companion object {
        /**
         * `duplicate`: the same source identity was imported before. `conflict`: the same key was used for different input
         * (an edited copy of a row already imported). Either way the row is already in Treasure.
         */
        fun alreadyThere(code: String?) = code == "duplicate" || code == "conflict"
    }

    private suspend fun finish() { spends.reload(); directory.refresh() }

    // endregion
}
