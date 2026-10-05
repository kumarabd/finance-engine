import Foundation

/// One transaction read from a receipt, statement or CSV, waiting for the user's review.
struct ParsedItem: Identifiable, Equatable {
    var id = UUID()
    var date: String            // yyyy-MM-dd
    var description: String
    var amountMinor: Int64      // positive; `kind` carries direction
    var kind: String            // expense | refund | transfer
    var currency: String
    var sourceLine: String
    var merchantId: String?     // matched to an existing merchant
    var merchantName: String?   // display name; for an unmatched row, the cleaned name a new merchant would get
    var categoryId: String?
    var include = true
    var duplicateOf: String?    // an existing spend that looks identical
    var fingerprint = ""        // stable across re-uploads; becomes the spend's source_record_id
    var externalId: String?     // the bank's own transaction id, when the file has one
    var possibleDuplicateOf: String?   // might match an entry you already have; only a hint, the row stays selected
}

struct ParseOptions {
    var dayFirst = false
    /// Bank convention: money out is negative. Card convention (false): charges are positive, payments and credits negative.
    var negativeIsSpend = true
    var currency = "USD"
    var today = Date()
    var calendar = Calendar(identifier: .gregorian)
    var fractionDigits: Int { AmountParse.fractionDigits(currency) }

    /// Whether the user's region writes the day before the month (04/10/2026 is 4 October).
    static var localeDayFirst: Bool {
        let f = DateFormatter.dateFormat(fromTemplate: "yMd", options: 0, locale: .current) ?? ""
        let d = f.firstIndex(of: "d"), m = f.firstIndex(of: "M")
        guard let d, let m else { return false }
        return d < m
    }
}

enum AmountParse {
    struct Value: Equatable {
        var minor: Int64
        var negative: Bool
        var credit = false   // CR suffix: money in
        var debit = false    // DR suffix: money out
    }

    /// Decimal places a currency uses: 2 for most, 0 for yen and won, 3 for dinars. Unknown codes count as 2.
    static func fractionDigits(_ currency: String) -> Int {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.locale = Locale(identifier: "en_US")
        f.currencyCode = currency
        return f.maximumFractionDigits
    }

    // With decimals (2, 3), a token must have exactly that many, so store numbers, years and day numbers are never read as
    // money. Currencies without decimals (yen) have no such tell: see the trailing-run rule in `find`.
    private static var tokenCache: [Int: NSRegularExpression] = [:]
    private static func token(_ digits: Int) -> NSRegularExpression {
        if let r = tokenCache[digits] { return r }
        let r = try! NSRegularExpression(pattern: #"(?<![\w/.,])\(?-?[$€£₹¥￥₩]?\s?-?(?:\d{1,3}(?:[,.]\d{3})+|\d+)[.,]\d{"# + "\(digits)" + #"}\)?-?(?:\s?(?:CR|DR))?(?![\w/]|[.,]\d)"#, options: [.caseInsensitive])
        tokenCache[digits] = r
        return r
    }
    private static let wholeToken = try! NSRegularExpression(pattern: #"^\(?-?[$€£₹¥￥₩]?-?\d[\d,]*\)?-?(?:CR|DR)?$"#, options: [.caseInsensitive])

    /// Every money amount in a line, left to right, with where it sits.
    /// For a currency with no decimals an amount can't be told from a store number by its shape, so only the last
    /// `trailing` numeric tokens at the end of the line count (the amount, plus a running balance when there is one).
    static func find(in line: String, digits: Int = 2, trailing: Int = 1) -> [(value: Value, range: Range<String.Index>)] {
        if digits == 0 { return findWhole(in: line, trailing: trailing) }
        let ns = line as NSString
        return token(digits).matches(in: line, range: NSRange(location: 0, length: ns.length)).compactMap { m in
            guard let r = Range(m.range, in: line), let v = parseToken(String(line[r]), digits: digits) else { return nil }
            return (v, r)
        }
    }

    private static func findWhole(in line: String, trailing: Int) -> [(value: Value, range: Range<String.Index>)] {
        var run: [(value: Value, range: Range<String.Index>)] = []
        var end = line.endIndex
        while true {
            // Walk back over spaces, then over one token.
            var tokenEnd = end
            while tokenEnd > line.startIndex, line[line.index(before: tokenEnd)].isWhitespace { tokenEnd = line.index(before: tokenEnd) }
            var tokenStart = tokenEnd
            while tokenStart > line.startIndex, !line[line.index(before: tokenStart)].isWhitespace { tokenStart = line.index(before: tokenStart) }
            guard tokenStart < tokenEnd else { break }
            let text = String(line[tokenStart..<tokenEnd])
            guard wholeToken.firstMatch(in: text, range: NSRange(location: 0, length: (text as NSString).length)) != nil,
                  let v = parseToken(text, digits: 0) else { break }
            run.insert((v, tokenStart..<tokenEnd), at: 0)
            end = tokenStart
        }
        return Array(run.suffix(trailing))
    }

    /// "$1,234.56", "-12.34", "(12.34)", "12.34-", "12.34 CR", "1.234,56". The last separator is the decimal point.
    static func parseToken(_ raw: String, digits: Int = 2) -> Value? {
        let t = raw.trimmingCharacters(in: .whitespaces)
        let all = t.filter(\.isNumber)
        guard all.count >= max(digits + 1, 1), let minor = Int64(all) else { return nil }
        let upper = t.uppercased()
        let negative = t.contains("(") || t.hasPrefix("-") || t.hasSuffix("-") || t.contains("$-") || t.contains("-$")
        return Value(minor: minor, negative: negative, credit: upper.hasSuffix("CR"), debit: upper.hasSuffix("DR"))
    }

    /// A lenient parse for a single CSV cell, where "12", "12.5", "-1,234.50" and "1.234,50" are all plausible, scaled to
    /// the currency's own minor unit: "500" is 500 yen but 5.00 in dollars once typed as "500.00".
    static func parseCell(_ raw: String, digits: Int = 2) -> Value? {
        var t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return nil }
        let negative = t.contains("(") || t.hasPrefix("-") || t.hasSuffix("-")
        t = t.filter { $0.isNumber || $0 == "." || $0 == "," }
        guard !t.isEmpty else { return nil }
        var whole = t, frac = ""
        if digits > 0, let i = t.lastIndex(where: { $0 == "." || $0 == "," }) {
            let after = t[t.index(after: i)...]
            let separators = t.filter { $0 == "." || $0 == "," }.count
            // One separator followed by exactly three digits is a thousands mark ("1,234"), anything else a decimal point.
            // In a three-decimal currency a lone ".345" is a fraction.
            let thousands = after.count == 3 && separators == 1 && !(digits == 3 && t[i] == ".")
            if !thousands { whole = String(t[..<i]); frac = String(after) }
        }
        let w = whole.filter(\.isNumber), f = String(frac.prefix(digits)).padding(toLength: digits, withPad: "0", startingAt: 0)
        guard let minor = Int64((w.isEmpty ? "0" : w) + f) else { return nil }
        return Value(minor: minor, negative: negative)
    }
}

/// Which currency a document is in: an explicit code or symbol, else the user's default.
enum CurrencyDetect {
    private static let codes = ["USD", "EUR", "GBP", "INR", "JPY", "KRW", "CNY", "CAD", "AUD", "NZD", "CHF", "SEK", "NOK", "DKK", "SGD", "HKD", "MXN", "BRL", "ZAR", "KWD", "BHD"]

    static func detect(in text: String, default fallback: String) -> String {
        for code in codes where text.range(of: #"(?<![A-Za-z])"# + code + #"(?![A-Za-z])"#, options: .regularExpression) != nil { return code }
        if text.contains("€") { return "EUR" }
        if text.contains("£") { return "GBP" }
        if text.contains("₹") { return "INR" }
        if text.contains("₩") { return "KRW" }
        if text.contains("¥") || text.contains("￥") || text.contains("円") { return "JPY" }
        if text.contains("$"), !["USD", "CAD", "AUD", "NZD", "SGD", "HKD", "MXN"].contains(fallback) { return "USD" }
        return fallback
    }
}

enum DateParse {
    private static let months = "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec"
    private static func rx(_ p: String) -> NSRegularExpression { try! NSRegularExpression(pattern: p, options: [.caseInsensitive]) }
    private static let iso = rx(#"(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)"#)
    private static let numeric = rx(#"(?<![\d/.])(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(\d{4}|\d{2})(?!\d)"#)  // OCR often adds a space: "10/04/ 2026"
    private static let numericNoYear = rx(#"^\s*(\d{1,2})[/.](\d{1,2})(?![\d/.])"#)
    private static let dayMonth = rx(#"(?<!\w)(\d{1,2})(?:st|nd|rd|th)?\s+("# + months + #")[a-z]*\.?,?(?:\s+(\d{4}))?(?!\d)"#)
    private static let monthDay = rx(#"(?<!\w)("# + months + #")[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?(?!\d)"#)

    /// The first date in a line: yyyy-MM-dd text and where it sits. A missing year is the current one, or last year when
    /// that would put the date more than 45 days in the future (a December transaction on a January statement).
    static func find(in line: String, options: ParseOptions, startOnly: Bool = false) -> (date: String, range: Range<String.Index>)? {
        let ns = line as NSString
        let all = NSRange(location: 0, length: ns.length)
        func g(_ m: NSTextCheckingResult, _ i: Int) -> String? { m.range(at: i).location == NSNotFound ? nil : ns.substring(with: m.range(at: i)) }
        var found: [(Int, String, NSRange)] = []

        if let m = iso.firstMatch(in: line, range: all), let y = Int(g(m, 1)!), let mo = Int(g(m, 2)!), let d = Int(g(m, 3)!), let s = make(y, mo, d, options) { found.append((m.range.location, s, m.range)) }
        if let m = numeric.firstMatch(in: line, range: all), let a = Int(g(m, 1)!), let b = Int(g(m, 2)!), var y = Int(g(m, 3)!) {
            if y < 100 { y += 2000 }
            let dayFirst = a > 12 ? true : (b > 12 ? false : options.dayFirst)
            if let s = make(y, dayFirst ? b : a, dayFirst ? a : b, options) { found.append((m.range.location, s, m.range)) }
        }
        if let m = numericNoYear.firstMatch(in: line, range: all), let a = Int(g(m, 1)!), let b = Int(g(m, 2)!) {
            let dayFirst = a > 12 ? true : (b > 12 ? false : options.dayFirst)
            if let s = make(nil, dayFirst ? b : a, dayFirst ? a : b, options) { found.append((m.range.location, s, m.range)) }
        }
        for (re, dayFirstOrder) in [(dayMonth, true), (monthDay, false)] {
            guard let m = re.firstMatch(in: line, range: all) else { continue }
            let mon = g(m, dayFirstOrder ? 2 : 1)!, day = Int(g(m, dayFirstOrder ? 1 : 2)!)!
            guard let mi = monthIndex(mon), let s = make(g(m, 3).flatMap(Int.init), mi, day, options) else { continue }
            found.append((m.range.location, s, m.range))
        }
        guard let best = found.min(by: { $0.0 < $1.0 }), let r = Range(best.2, in: line) else { return nil }
        if startOnly, line.distance(from: line.startIndex, to: r.lowerBound) > 12 { return nil }
        return (best.1, r)
    }

    private static func monthIndex(_ s: String) -> Int? {
        ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"].firstIndex(of: String(s.lowercased().prefix(3))).map { $0 + 1 }
    }

    private static func make(_ year: Int?, _ month: Int, _ day: Int, _ o: ParseOptions) -> String? {
        let cal = o.calendar
        let thisYear = cal.component(.year, from: o.today)
        var y = year ?? thisYear
        func valid(_ y: Int) -> Date? {
            var c = DateComponents(); c.year = y; c.month = month; c.day = day
            guard let d = cal.date(from: c), cal.component(.day, from: d) == day, cal.component(.month, from: d) == month else { return nil }
            return d
        }
        guard var d = valid(y) else { return nil }
        if year == nil, d > o.today.addingTimeInterval(45 * 86_400), let prev = valid(y - 1) { d = prev; y -= 1 }
        return String(format: "%04d-%02d-%02d", y, month, day)
    }
}

enum ReceiptParser {
    private static let strong = ["grand total", "amount due", "balance due", "total due", "amount paid", "total"]
    private static let weak = ["subtotal", "sub total", "sub-total", "tax", "change", "cash", "tender", "tip", "gratuity", "visa", "mastercard", "card", "savings", "discount"]
    private static let skipMerchant = ["receipt", "invoice", "welcome", "thank", "tel", "phone", "www.", "http", "order", "table", "server", "cashier"]

    /// One receipt is one spend: the total, the date, and a guess at the merchant from the top of the page.
    static func parse(_ text: String, options: ParseOptions) -> [ParsedItem] {
        let lines = text.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        let currency = CurrencyDetect.detect(in: text, default: options.currency)
        let digits = AmountParse.fractionDigits(currency)
        guard let total = total(lines, digits: digits) else { return [] }
        let date = lines.lazy.compactMap { DateParse.find(in: $0, options: options)?.date }.first
            ?? String(format: "%04d-%02d-%02d", options.calendar.component(.year, from: options.today), options.calendar.component(.month, from: options.today), options.calendar.component(.day, from: options.today))
        let name = merchantLine(lines, options, digits: digits)
        return [ParsedItem(date: date, description: name, amountMinor: total, kind: "expense", currency: currency,
                           sourceLine: lines.first { AmountParse.find(in: $0, digits: digits).contains { $0.value.minor == total } } ?? "")]
    }

    private static func total(_ lines: [String], digits: Int) -> Int64? {
        var strongHit: Int64?
        var best: Int64?
        for l in lines {
            let low = l.lowercased()
            guard let last = AmountParse.find(in: l, digits: digits).last?.value else { continue }
            let isWeak = weak.contains { low.contains($0) }
            if strong.contains(where: { low.contains($0) }) && !low.contains("subtotal") && !low.contains("sub total") && !low.contains("sub-total") { strongHit = last.minor }
            else if !isWeak { best = max(best ?? 0, last.minor) }
        }
        return strongHit ?? best
    }

    private static func merchantLine(_ lines: [String], _ options: ParseOptions, digits: Int) -> String {
        for l in lines.prefix(6) {
            let low = l.lowercased()
            let letters = l.filter(\.isLetter).count
            if letters < 3 || skipMerchant.contains(where: { low.contains($0) }) { continue }
            if let f = l.first, f.isNumber { continue } // street addresses, phone numbers, order numbers
            if DateParse.find(in: l, options: options) != nil || !AmountParse.find(in: l, digits: digits).isEmpty { continue }
            return l
        }
        return ""
    }
}

enum StatementParser {
    private static let skipStarts = ["opening balance", "closing balance", "balance forward", "balance brought", "brought forward", "carried forward",
                                     "previous balance", "new balance", "minimum payment", "payment due", "credit limit", "statement", "account number",
                                     "summary", "page ", "total"]
    private static let refundWords = ["refund", "reversal", "return", "chargeback", "cashback", "cash back"]
    private static let transferWords = ["transfer", "xfer", "payment", "autopay", "thank you", "zelle", "venmo cashout", "withdrawal to savings"]

    /// Guesses the sign convention: when most amounts are negative, money out is negative (a bank account);
    /// otherwise charges are positive (a card). The user can flip it in the review screen.
    static func detectNegativeIsSpend(_ text: String, options: ParseOptions) -> Bool {
        var neg = 0, pos = 0
        for l in lines(text) where DateParse.find(in: l, options: options, startOnly: true) != nil {
            guard let v = AmountParse.find(in: l, digits: options.fractionDigits, trailing: 1).first?.value else { continue }
            if v.credit || v.debit { continue }
            if v.negative { neg += 1 } else { pos += 1 }
        }
        return neg > pos
    }

    static func parse(_ text: String, options: ParseOptions) -> [ParsedItem] {
        let all = lines(text)
        let hasBalanceColumn = all.contains { l in
            let low = l.lowercased()
            return low.contains("balance") && ["debit", "credit", "withdraw", "deposit", "amount"].contains { low.contains($0) } && AmountParse.find(in: l, digits: options.fractionDigits).isEmpty
        }
        var out: [ParsedItem] = []
        for line in all {
            guard let first = DateParse.find(in: line, options: options, startOnly: true) else { continue }
            var amounts = AmountParse.find(in: line, digits: options.fractionDigits, trailing: hasBalanceColumn ? 2 : 1).filter { $0.range.lowerBound >= first.range.upperBound }
            var balance: Range<String.Index>?
            if hasBalanceColumn && amounts.count >= 2 { balance = amounts.removeLast().range } // the running balance
            guard let a = pick(amounts.map(\.value)) else { continue }

            // Remove the date(s), amounts and balance; what remains is the description.
            var rest = line
            let spans = amounts.map(\.range) + [first.range] + (balance.map { [$0] } ?? [])
            for r in spans.sorted(by: { $0.lowerBound > $1.lowerBound }) { rest.removeSubrange(r) }
            if let second = DateParse.find(in: rest, options: options, startOnly: true) { rest.removeSubrange(second.range) } // posting date next to transaction date
            let desc = rest.split(whereSeparator: \.isWhitespace).joined(separator: " ").trimmingCharacters(in: CharacterSet(charactersIn: " -–*"))
            let low = desc.lowercased()
            if desc.isEmpty || skipStarts.contains(where: { low.hasPrefix($0) }) { continue }

            let outflow = direction(a, negativeIsSpend: options.negativeIsSpend)
            let kind: String
            if outflow { kind = transferWords.contains { low.contains($0) } ? "transfer" : "expense" }
            else { kind = refundWords.contains { low.contains($0) } ? "refund" : "transfer" } // deposits and card payments are not spending
            out.append(ParsedItem(date: first.date, description: desc, amountMinor: a.minor, kind: kind, currency: options.currency, sourceLine: line, include: kind != "transfer"))
        }
        return out
    }

    /// With two amounts left (separate debit and credit columns) the flagged or nonzero one is the transaction.
    private static func pick(_ values: [AmountParse.Value]) -> AmountParse.Value? {
        let nonzero = values.filter { $0.minor != 0 }
        return nonzero.first(where: { $0.credit || $0.debit }) ?? nonzero.first
    }

    static func direction(_ v: AmountParse.Value, negativeIsSpend: Bool) -> Bool {
        if v.debit { return true }
        if v.credit { return false }
        return negativeIsSpend ? v.negative : !v.negative
    }

    private static func lines(_ text: String) -> [String] {
        text.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }
}

enum CSVParser {
    /// RFC 4180-ish: quoted fields, doubled quotes, and commas, semicolons or tabs as the separator.
    static func rows(_ text: String) -> [[String]] {
        let first = text.split(whereSeparator: \.isNewline).first.map(String.init) ?? ""
        var sep: Character = ","
        var most = 0
        for c: Character in [",", ";", "\t"] {
            let n = first.filter { $0 == c }.count
            if n > most { most = n; sep = c }
        }
        var rows: [[String]] = [], row: [String] = [], field = "", quoted = false
        let chars = Array(text)
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if quoted {
                if c == "\"" { if i + 1 < chars.count, chars[i + 1] == "\"" { field.append("\""); i += 1 } else { quoted = false } }
                else { field.append(c) }
            } else if c == "\"" { quoted = true }
            else if c == sep { row.append(field); field = "" }
            else if c == "\n" || c == "\r" {
                if c == "\r", i + 1 < chars.count, chars[i + 1] == "\n" { i += 1 }
                row.append(field); field = ""
                if row.contains(where: { !$0.isEmpty }) { rows.append(row) }
                row = []
            } else { field.append(c) }
            i += 1
        }
        row.append(field)
        if row.contains(where: { !$0.isEmpty }) { rows.append(row) }
        return rows
    }

    /// Reads a bank export by its header names. Returns nil when there is no recognizable date and amount column.
    static func items(_ text: String, options: ParseOptions) -> [ParsedItem]? {
        let all = rows(text)
        guard let header = all.first?.map({ $0.lowercased() }) else { return nil }
        func col(_ names: [String]) -> Int? { header.firstIndex { h in names.contains { h.contains($0) } } }
        guard let dateCol = col(["date", "posted"]) else { return nil }
        let amountCol = col(["amount"]), debitCol = col(["debit", "withdraw", "paid out", "money out"]), creditCol = col(["credit", "deposit", "paid in", "money in"])
        guard amountCol != nil || debitCol != nil || creditCol != nil else { return nil }
        let descCol = col(["description", "memo", "payee", "details", "merchant", "name", "narrative"])
        let idCol = header.firstIndex { h in h == "id" || ["transaction id", "transaction no", "transaction ref", "txn id", "reference", "fitid"].contains { h.contains($0) } }

        var out: [ParsedItem] = []
        for r in all.dropFirst() {
            func cell(_ i: Int?) -> String { i.flatMap { $0 < r.count ? r[$0] : nil } ?? "" }
            guard let date = DateParse.find(in: cell(dateCol), options: options)?.date else { continue }
            let digits = options.fractionDigits
            let amount = AmountParse.parseCell(cell(amountCol), digits: digits), debit = AmountParse.parseCell(cell(debitCol), digits: digits), credit = AmountParse.parseCell(cell(creditCol), digits: digits)
            let desc = cell(descCol).split(whereSeparator: \.isWhitespace).joined(separator: " ")
            let minor: Int64, outflow: Bool
            if let d = debit, d.minor != 0 { minor = d.minor; outflow = true }
            else if let c = credit, c.minor != 0 { minor = c.minor; outflow = false }
            else if let a = amount, a.minor != 0 { minor = a.minor; outflow = StatementParser.direction(a, negativeIsSpend: options.negativeIsSpend) }
            else { continue }
            let low = desc.lowercased()
            let kind = outflow ? (["transfer", "xfer", "payment", "autopay"].contains { low.contains($0) } ? "transfer" : "expense")
                               : (["refund", "reversal", "return", "chargeback"].contains { low.contains($0) } ? "refund" : "transfer")
            let external = cell(idCol).trimmingCharacters(in: .whitespaces)
            out.append(ParsedItem(date: date, description: desc, amountMinor: minor, kind: kind, currency: options.currency, sourceLine: r.joined(separator: ","), include: kind != "transfer", externalId: external.isEmpty ? nil : external))
        }
        return out
    }

    /// Sign convention for an amount column: most negative means money out is negative.
    static func detectNegativeIsSpend(_ text: String, digits: Int = 2) -> Bool {
        let all = rows(text)
        guard let header = all.first?.map({ $0.lowercased() }), let i = header.firstIndex(where: { $0.contains("amount") }) else { return true }
        let values = all.dropFirst().compactMap { $0.count > i ? AmountParse.parseCell($0[i], digits: digits) : nil }
        return values.filter(\.negative).count > values.filter { !$0.negative }.count
    }
}
