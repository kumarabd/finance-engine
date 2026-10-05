import CryptoKit
import Foundation

/// Learns from the user's own history, with no model and no network: it matches a description to an existing merchant,
/// suggests the category that merchant usually has, and flags rows that already exist.
struct Enricher {
    var merchants: [Dimension]
    var spends: [Spend]

    // MARK: Text

    /// "SQ *BLUE BOTTLE #1234 OAKLAND CA" -> "blue bottle oakland ca". Processor prefixes, store numbers and any token with a digit go.
    static func normalize(_ s: String) -> String {
        var t = s.lowercased()
        t = t.replacingOccurrences(of: #"\b(sq|tst|pp|paypal|sp|pos|ach|pmt)\s*\*"#, with: " ", options: .regularExpression)
        t = t.replacingOccurrences(of: #"^(pos|debit card purchase|card purchase|checkcard|purchase authorized on|recurring payment|online payment)\b"#, with: " ", options: .regularExpression)
        let kept = t.split(whereSeparator: { !($0.isLetter || $0.isNumber || $0 == "'" || $0 == "&") })
            .filter { tok in !tok.contains(where: \.isNumber) }
        return kept.joined(separator: " ")
    }

    /// A short display name for a new merchant: the first two words, capitalized. Imperfect on purpose; rename or merge later.
    static func cleanName(_ s: String) -> String {
        normalize(s).split(separator: " ").prefix(2).map { $0.prefix(1).uppercased() + $0.dropFirst() }.joined(separator: " ")
    }

    // MARK: Matching

    /// `documentHash` is the file's hash for a receipt (one document, one spend), and nil for a statement or CSV.
    func enrich(_ items: [ParsedItem], documentHash: String? = nil) -> [ParsedItem] {
        let names: [(norm: String, merchant: Dimension)] = merchants.flatMap { m in ([m.name] + (m.aliases ?? [])).map { (Self.normalize($0), m) } }.filter { $0.norm.count >= 3 }
        var merchantByDescription: [String: [String: Int]] = [:], categoryByMerchant: [String: [String: Int]] = [:], categoryByDescription: [String: [String: Int]] = [:]
        for s in spends where s.deletedAt == nil {
            let d = Self.normalize(s.description ?? "")
            let cat = s.allocations?.count == 1 ? s.allocations?.first?.categoryId : nil
            if let m = s.merchantId {
                if !d.isEmpty { merchantByDescription[d, default: [:]][m, default: 0] += 1 }
                if let cat { categoryByMerchant[m, default: [:]][cat, default: 0] += 1 }
            } else if let cat, !d.isEmpty { categoryByDescription[d, default: [:]][cat, default: 0] += 1 }
        }
        func top(_ counts: [String: Int]?) -> String? { counts?.max { $0.value < $1.value }?.key }

        let merchantNameById = Dictionary(uniqueKeysWithValues: merchants.map { ($0.id, $0.name) })
        // Rows already imported (same source identity): the engine would refuse them anyway.
        var importedBySource: [String: String] = [:]
        for s in spends where s.deletedAt == nil && s.source == "import" { if let r = s.sourceRecordId { importedBySource[r] = s.id } }
        // Existing spends by date, amount, currency and kind. One-to-one: two identical coffees on the same day need two
        // existing rows to both count as duplicates.
        var existing: [String: [Spend]] = [:]
        for s in spends where s.deletedAt == nil { existing["\(s.occurredOn)|\(s.amountMinor)|\(s.currency)|\(s.kind)", default: []].append(s) }
        var occurrence: [String: Int] = [:]

        return items.map { item in
            var i = item
            let norm = Self.normalize(i.description)
            let padded = " \(norm) "
            let byName = names.filter { padded.contains(" \($0.norm) ") }.max { $0.norm.count < $1.norm.count }?.merchant
            let mid = byName?.id ?? top(merchantByDescription[norm])
            if let mid, let m = merchants.first(where: { $0.id == mid }) { i.merchantId = mid; i.merchantName = m.name }
            else { let n = Self.cleanName(i.description); i.merchantName = n.isEmpty ? nil : n }
            i.categoryId = (mid.flatMap { top(categoryByMerchant[$0]) }) ?? top(categoryByDescription[norm])

            let base = "\(i.date)|\(i.amountMinor)|\(i.currency)|\(i.kind)|\(norm)"
            let n = occurrence[base, default: 0]; occurrence[base] = n + 1
            i.fingerprint = Self.fingerprint(i, norm: norm, documentHash: documentHash, occurrence: n)

            // Duplicates. Only a match that agrees on WHAT it was (merchant or description) is trusted enough to start unchecked.
            // A coffee and a bus fare for the same price on the same day are two purchases.
            let key = "\(i.date)|\(i.amountMinor)|\(i.currency)|\(i.kind)"
            if let id = importedBySource[i.fingerprint] { i.duplicateOf = id; i.include = false }
            else if var candidates = existing[key], !candidates.isEmpty {
                if let at = candidates.firstIndex(where: { likelySame($0, i, norm, merchantNameById) }) {
                    i.duplicateOf = candidates.remove(at: at).id; i.include = false
                } else if let at = candidates.firstIndex(where: { $0.merchantId == nil && ($0.description ?? "").isEmpty }) {
                    i.possibleDuplicateOf = candidates.remove(at: at).id   // an entry with no merchant or note: can't tell, so only hint
                }
                existing[key] = candidates
            }
            return i
        }
    }

    /// Same date, amount and kind are given; this asks whether the two also agree on what was bought.
    private func likelySame(_ e: Spend, _ item: ParsedItem, _ norm: String, _ merchantNameById: [String: String]) -> Bool {
        if let em = e.merchantId, let im = item.merchantId, em == im { return true }
        let existingText = Self.normalize(e.description ?? "") + " " + (e.merchantId.flatMap { merchantNameById[$0] }.map(Self.normalize) ?? "")
        return Self.overlap(norm, existingText)
    }

    /// At least half of the shorter side's words (3+ letters) appear on the other side.
    static func overlap(_ a: String, _ b: String) -> Bool {
        let x = Set(a.split(separator: " ").filter { $0.count >= 3 }), y = Set(b.split(separator: " ").filter { $0.count >= 3 })
        guard !x.isEmpty, !y.isEmpty else { return false }
        let common = x.intersection(y).count
        return common > 0 && common * 2 >= min(x.count, y.count)
    }

    /// Stable across re-uploads, and distinct for different transactions:
    /// - a bank's own transaction id, when the file has one, is the identity;
    /// - otherwise date, amount, currency, TYPE (an expense and a refund are different), description, and which repeat it is;
    /// - a receipt also carries its file's hash, so two separate receipts for the same coffee never collapse into one. A
    ///   statement does not, so overlapping statements still recognise the rows they share.
    static func fingerprint(_ i: ParsedItem, norm: String, documentHash: String?, occurrence n: Int) -> String {
        if let id = i.externalId, !id.isEmpty { return hash("txn|\(id)|\(i.currency)") }
        var base = "\(i.date)|\(i.amountMinor)|\(i.currency)|\(i.kind)|\(norm)"
        if let documentHash { base += "|doc:\(documentHash)" }
        return hash("\(base)|\(n)")
    }

    static func hash(_ s: String) -> String { SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined() }
}
