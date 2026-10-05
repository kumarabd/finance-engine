import Foundation

/// A reporting window and the earlier window it is compared with. Dates are calendar dates (yyyy-MM-dd).
enum Period: String, CaseIterable, Identifiable {
    case week, month, quarter, year
    var id: String { rawValue }

    var title: String {
        switch self { case .week: "Week"; case .month: "Month"; case .quarter: "3 Months"; case .year: "Year" }
    }
    /// How the trend chart buckets the window.
    var trendGroup: String {
        switch self { case .week, .month: "day"; case .quarter: "week"; case .year: "month" }
    }
    var comparedWith: String {
        switch self { case .week: "last week"; case .month: "last month"; case .quarter: "the 3 months before"; case .year: "last year" }
    }

    struct Window: Equatable { var from, to, compareFrom, compareTo: String }

    /// The window runs from the start of the period to today. The comparison is the same stretch shifted back one
    /// period, so a half-finished month is compared with the first half of last month, not all of it.
    func window(today: Date = .now, calendar: Calendar = .current) -> Window {
        let start: Date
        let shift: DateComponents
        switch self {
        case .week:
            start = calendar.dateInterval(of: .weekOfYear, for: today)?.start ?? today; shift = DateComponents(day: -7)
        case .month:
            start = calendar.dateInterval(of: .month, for: today)?.start ?? today; shift = DateComponents(month: -1)
        case .quarter:
            let m = calendar.dateInterval(of: .month, for: today)?.start ?? today
            start = calendar.date(byAdding: .month, value: -2, to: m) ?? m; shift = DateComponents(month: -3)
        case .year:
            start = calendar.dateInterval(of: .year, for: today)?.start ?? today; shift = DateComponents(year: -1)
        }
        func str(_ d: Date) -> String { CalendarDate.string(from: d, calendar: calendar) }
        return Window(from: str(start), to: str(today),
                      compareFrom: str(calendar.date(byAdding: shift, to: start) ?? start),
                      compareTo: str(calendar.date(byAdding: shift, to: today) ?? today))
    }
}

struct TrendPoint: Identifiable, Equatable {
    var key: String      // bucket start, yyyy-MM-dd
    var minor: Int64
    var id: String { key }
}

enum Trend {
    /// Every bucket key in [from, to], so days with no spending appear as zero bars. Weeks start on Monday and months on
    /// the 1st, matching the engine's date_trunc.
    static func keys(group: String, from: String, to: String) -> [String] {
        var iso = Calendar(identifier: .iso8601); iso.timeZone = .current
        guard var d = CalendarDate.date(from: from, calendar: iso), let end = CalendarDate.date(from: to, calendar: iso) else { return [] }
        var out: [String] = []
        switch group {
        case "week": d = iso.dateInterval(of: .weekOfYear, for: d)?.start ?? d
        case "month": d = iso.dateInterval(of: .month, for: d)?.start ?? d
        default: break
        }
        let step: Calendar.Component = group == "month" ? .month : .day
        let by = group == "week" ? 7 : 1
        while d <= end, out.count < 400 {
            out.append(CalendarDate.string(from: d, calendar: iso))
            guard let next = iso.date(byAdding: step, value: by, to: d) else { break }
            d = next
        }
        return out
    }

    /// Net spend per bucket for one currency, with gaps filled with zero.
    static func series(_ buckets: [Bucket], currency: String, group: String, from: String, to: String) -> [TrendPoint] {
        var byKey: [String: Int64] = [:]
        for b in buckets where b.currency == currency { byKey[b.key] = b.net }
        return keys(group: group, from: from, to: to).map { TrendPoint(key: $0, minor: byKey[$0] ?? 0) }
    }
}

struct BreakdownRow: Identifiable, Equatable {
    var key: String
    var label: String
    var minor: Int64
    var share: Double
    var id: String { key }
}

enum Breakdown {
    /// Largest groups first, anything beyond `limit` folded into "Other". Groups with no net spend are left out.
    static func top(_ buckets: [Bucket], currency: String, limit: Int = 5) -> [BreakdownRow] {
        let rows = buckets.filter { $0.currency == currency && $0.net > 0 }.sorted { $0.net > $1.net }
        let total = rows.reduce(Int64(0)) { $0 + $1.net }
        guard total > 0 else { return [] }
        var out = rows.prefix(limit).map { BreakdownRow(key: $0.key, label: $0.label, minor: $0.net, share: Double($0.net) / Double(total)) }
        let rest = rows.dropFirst(limit).reduce(Int64(0)) { $0 + $1.net }
        if rest > 0 { out.append(BreakdownRow(key: "other", label: "Other", minor: rest, share: Double(rest) / Double(total))) }
        return out
    }
}
