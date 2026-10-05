import Foundation
import Observation
import UIKit

struct EvidenceRecord: Decodable { var id: String }
struct CreateEvidenceInput: Encodable {
    var idempotencyKey: String
    var title: String
    var sourceRef: String
    var mediaType: String
    var checksum: String
    var notes: String
    enum CodingKeys: String, CodingKey { case idempotencyKey = "idempotency_key", title, sourceRef = "source_ref", mediaType = "media_type", checksum, notes }
}
struct BulkCreateInput: Encodable {
    var idempotencyKey: String
    var spends: [SpendInput]
    enum CodingKeys: String, CodingKey { case idempotencyKey = "idempotency_key", spends }
}
struct SpendsResult: Decodable { var items: [Spend] }

/// One import from file or camera to saved spends: read, parse, enrich from the user's history, review, save.
/// Everything up to "save" runs on this device; only the confirmed spends are sent.
@MainActor @Observable
final class ImportSession: Identifiable {
    enum Phase: Equatable { case reading, review, saving, done(created: Int, skipped: Int), failed(String) }

    let id = UUID()
    private(set) var phase: Phase = .reading
    private(set) var doc: ExtractedDocument?
    var items: [ParsedItem] = []
    var asStatement = true
    var negativeIsSpend = true
    /// Amounts are read in this currency's minor unit, so it is detected from the document and the user can correct it.
    var currency = "USD"
    private(set) var progress = 0.0

    private let engine: Engine
    private let directory: Directory
    private let spends: SpendsModel
    private var history: [Spend] = []
    private var historyRange = ""

    init(engine: Engine, directory: Directory, spends: SpendsModel) { self.engine = engine; self.directory = directory; self.spends = spends }

    var selected: Int { items.filter(\.include).count }
    var duplicates: Int { items.filter { $0.duplicateOf != nil }.count }

    // MARK: Read and parse

    func start(url: URL) async { await begin(await TextExtractor.extract(url: url)) }
    func start(images: [UIImage]) async { await begin(await TextExtractor.extract(images: images, name: "Scanned receipt")) }

    /// `duplicate`: the same source identity was imported before. `conflict`: the same key was used for different input
    /// (an edited copy of a row already imported). Either way the row is already in Treasure.
    nonisolated static func alreadyThere(_ code: String?) -> Bool { code == "duplicate" || code == "conflict" }

    func begin(_ doc: ExtractedDocument?) async {
        guard let doc else { phase = .failed("Couldn't read that file."); return }
        self.doc = doc
        currency = CurrencyDetect.detect(in: doc.text, default: UserDefaults.standard.string(forKey: "lastCurrency") ?? Locale.current.currency?.identifier ?? "USD")
        let o = options(negativeIsSpend: true)
        if doc.isCSV { asStatement = true; negativeIsSpend = CSVParser.detectNegativeIsSpend(doc.text, digits: o.fractionDigits) }
        else {
            let dated = doc.text.split(whereSeparator: \.isNewline).filter { DateParse.find(in: String($0), options: o, startOnly: true) != nil && !AmountParse.find(in: String($0)).isEmpty }
            asStatement = dated.count >= 3
            negativeIsSpend = StatementParser.detectNegativeIsSpend(doc.text, options: o)
        }
        await reparse()
    }

    func options(negativeIsSpend: Bool) -> ParseOptions {
        ParseOptions(dayFirst: ParseOptions.localeDayFirst, negativeIsSpend: negativeIsSpend,
                     currency: currency)
    }

    /// Parse again after the user changes the document type or sign convention. Their per-row edits are not kept.
    func reparse() async {
        guard let doc else { return }
        let o = options(negativeIsSpend: negativeIsSpend)
        let raw: [ParsedItem]
        if doc.isCSV { raw = CSVParser.items(doc.text, options: o) ?? StatementParser.parse(doc.text, options: o) }
        else { raw = asStatement ? StatementParser.parse(doc.text, options: o) : ReceiptParser.parse(doc.text, options: o) }
        await loadHistory(for: raw)
        items = Enricher(merchants: Array(directory.merchants.values), spends: history).enrich(raw, documentHash: (doc.isCSV || asStatement) ? nil : doc.hash)
        phase = .review
    }

    /// Existing spends over the same dates, for spotting duplicates, plus what's already loaded, for learning categories.
    private func loadHistory(for raw: [ParsedItem]) async {
        guard let from = raw.map(\.date).min(), let to = raw.map(\.date).max() else { history = spends.spends; return }
        let range = "\(from)|\(to)"
        if range != historyRange {
            var found: [Spend] = [], offset = 0
            while found.count < 2000 {
                let r: Api<Page<Spend>> = await engine.call("spends_search", SearchInput(limit: 200, offset: offset, from: from, to: to))
                guard case .ok(let page) = r else { break }
                found += page.items
                guard let next = page.nextOffset else { break }
                offset = next
            }
            history = found; historyRange = range
        }
        let known = Set(history.map(\.id))
        history += spends.spends.filter { !known.contains($0.id) }
    }

    // MARK: Save

    func save() async {
        guard let doc else { return }
        let chosen = items.filter(\.include)
        guard !chosen.isEmpty else { return }
        phase = .saving; progress = 0

        // The source document, as evidence. Optional: if the engine refuses it, the spends still go in.
        var evidenceId: String?
        let ev: Api<EvidenceRecord> = await engine.call("evidence_create", CreateEvidenceInput(
            idempotencyKey: "ev-" + doc.hash, title: String(doc.name.prefix(200)), sourceRef: "local-file:" + doc.hash,
            mediaType: doc.mediaType, checksum: doc.hash, notes: "Read on this device; the file itself is not uploaded."))
        switch ev {
        case .ok(let e): evidenceId = e.id
        case .failed: break
        default: phase = .failed(ev.problem ?? "Couldn't reach Treasure."); return
        }

        // New merchants for names that matched nothing, once per name.
        var created: [String: String] = [:]
        for name in Set(chosen.filter { $0.merchantId == nil }.compactMap(\.merchantName)) {
            switch await directory.resolveMerchant(name) {
            case .ok(let id): created[name] = id
            case let r: phase = .failed(r.problem ?? "Couldn't reach Treasure."); return
            }
        }

        let inputs = chosen.map { i in
            SpendInput(occurredOn: i.date, kind: i.kind, amountMinor: i.amountMinor, currency: i.currency,
                       merchantId: i.merchantId ?? i.merchantName.flatMap { created[$0] }, description: i.description.isEmpty ? nil : i.description,
                       source: "import", sourceRecordId: i.fingerprint,
                       allocations: i.categoryId.map { [Allocation(categoryId: $0, amountMinor: i.amountMinor)] },
                       evidenceIds: evidenceId.map { [$0] })
        }

        var made = 0, skipped = 0
        for start in stride(from: 0, to: inputs.count, by: 100) {
            let chunk = Array(inputs[start..<min(start + 100, inputs.count)])
            let key = "imp-" + Enricher.hash(chunk.compactMap(\.sourceRecordId).joined(separator: ","))
            let r: Api<SpendsResult> = await engine.call("spends_bulk_create", BulkCreateInput(idempotencyKey: key, spends: chunk))
            switch r {
            case .ok(let result): made += result.items.count
            case .failed(let code, _) where Self.alreadyThere(code):
                // Something in the batch already exists (the same statement imported before). The batch is atomic, so go one by one.
                for input in chunk {
                    let one: Api<Spend> = await engine.call("spends_create", CreateSpendInput(idempotencyKey: "imp-" + (input.sourceRecordId ?? UUID().uuidString), spend: input))
                    switch one {
                    case .ok: made += 1
                    case .failed(let code, _) where Self.alreadyThere(code): skipped += 1
                    default: phase = .failed("Imported \(made) before a problem: \(one.problem ?? "unknown")"); await finish(); return
                    }
                }
            default:
                phase = .failed(made > 0 ? "Imported \(made) before a problem: \(r.problem ?? "unknown")" : (r.problem ?? "Couldn't import."))
                await finish(); return
            }
            progress = Double(min(start + 100, inputs.count)) / Double(inputs.count)
        }
        await finish()
        phase = .done(created: made, skipped: skipped)
    }

    private func finish() async { await spends.reload(); await directory.refresh() }
}
