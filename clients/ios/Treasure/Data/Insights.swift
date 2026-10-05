import Foundation

/// Aggregates cross the API as exact integer strings; parse them as Int64 (never Double).
struct Bucket: Decodable, Equatable {
    var currency: String
    var key: String
    var label: String
    var expenseMinor: String
    var refundMinor: String
    var netMinor: String
    var count: Int64
    var net: Int64 { Int64(netMinor) ?? 0 }
    var expense: Int64 { Int64(expenseMinor) ?? 0 }
    enum CodingKeys: String, CodingKey {
        case currency, key, label, count
        case expenseMinor = "expense_minor", refundMinor = "refund_minor", netMinor = "net_minor"
    }
}

struct Analysis: Decodable, Equatable {
    var current: [Bucket]
    var comparison: [Bucket]
    enum CodingKeys: String, CodingKey { case current, comparison }
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        current = try c.decodeIfPresent([Bucket].self, forKey: .current) ?? []
        comparison = try c.decodeIfPresent([Bucket].self, forKey: .comparison) ?? []
    }
}

struct AnalysisInput: Encodable {
    var from: String
    var to: String
    var groupBy: String
    var compareFrom: String?
    var compareTo: String?
    enum CodingKeys: String, CodingKey { case from, to, groupBy = "group_by", compareFrom = "compare_from", compareTo = "compare_to" }
}

struct Snapshot: Equatable {
    var period: Period
    var window: Period.Window
    var trend: Analysis
    var categories: Analysis
    var merchants: Analysis
    var tags: Analysis

    /// Currencies present in the window, biggest spend first. They are never combined.
    var currencies: [String] {
        var totals: [String: Int64] = [:]
        for b in trend.current { totals[b.currency, default: 0] += b.expense }
        return totals.sorted { $0.value > $1.value }.map(\.key)
    }

    func total(_ currency: String) -> (current: Int64, previous: Int64?) {
        let cur = trend.current.filter { $0.currency == currency }.reduce(Int64(0)) { $0 + $1.net }
        let prev = trend.comparison.filter { $0.currency == currency }
        return (cur, prev.isEmpty ? nil : prev.reduce(Int64(0)) { $0 + $1.net })
    }

    func series(_ currency: String) -> [TrendPoint] {
        Trend.series(trend.current, currency: currency, group: period.trendGroup, from: window.from, to: window.to)
    }
    func previousSeries(_ currency: String) -> [TrendPoint] {
        Trend.series(trend.comparison, currency: currency, group: period.trendGroup, from: window.compareFrom, to: window.compareTo)
    }
}

enum Delta {
    /// "12% more than last month", or nil when there is nothing to compare with. Neutral wording: spending more is not an error.
    static func text(current: Int64, previous: Int64?, against: String) -> String? {
        guard let previous, previous > 0 else { return nil }
        let pct = Int((Double(abs(current - previous)) * 100 / Double(previous)).rounded())
        if pct == 0 { return "Same as \(against)" }
        return "\(pct)% \(current > previous ? "more" : "less") than \(against)"
    }
}

@MainActor
struct Insights {
    let engine: Engine

    func snapshot(_ period: Period) async -> Api<Snapshot> {
        let w = period.window()
        async let trend: Api<Analysis> = engine.call("spending_analyze", AnalysisInput(from: w.from, to: w.to, groupBy: period.trendGroup, compareFrom: w.compareFrom, compareTo: w.compareTo))
        async let cats: Api<Analysis> = engine.call("spending_analyze", AnalysisInput(from: w.from, to: w.to, groupBy: "category"))
        async let merchants: Api<Analysis> = engine.call("spending_analyze", AnalysisInput(from: w.from, to: w.to, groupBy: "merchant"))
        async let tagged: Api<Analysis> = engine.call("spending_analyze", AnalysisInput(from: w.from, to: w.to, groupBy: "tag"))
        let (t, c, m, g) = await (trend, cats, merchants, tagged)
        guard case .ok(let t) = t else { return t.map { _ in Snapshot.empty } }
        guard case .ok(let c) = c else { return c.map { _ in Snapshot.empty } }
        guard case .ok(let m) = m else { return m.map { _ in Snapshot.empty } }
        guard case .ok(let g) = g else { return g.map { _ in Snapshot.empty } }
        return .ok(Snapshot(period: period, window: w, trend: t, categories: c, merchants: m, tags: g))
    }
}

extension Snapshot {
    static let empty = Snapshot(period: .month, window: Period.month.window(), trend: .init(current: []), categories: .init(current: []), merchants: .init(current: []), tags: .init(current: []))
}

extension Analysis {
    init(current: [Bucket], comparison: [Bucket] = []) { self.current = current; self.comparison = comparison }
}
