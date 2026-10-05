import Foundation

/// Everything the Spends list can be narrowed by, in one value the filter sheet edits and the chips summarise.
struct SpendFilter: Equatable {
    var search = ""
    var from: String?
    var to: String?
    var kind: String?
    var currency: String?
    var merchantId: String?
    var categoryId: String?
    var uncategorized = false
    var tagIds: [String] = []
    var accountRef = ""
    var minAmountMinor: Int64?
    var maxAmountMinor: Int64?
    var evidenceId: String?
    var state = "active"        // active | deleted | all
    var sort = "date_desc"

    static let sorts = ["date_desc", "date_asc", "amount_desc", "amount_asc"]
    static var `default`: SpendFilter { SpendFilter() }

    /// True for the unfiltered, newest-first list: the one whose first page is cached for offline.
    var isDefault: Bool { self == .default }

    /// Narrowing conditions only (not the view onto trash, and not the sort order). Drives the chips and the badge.
    var activeCount: Int {
        [!search.trimmingCharacters(in: .whitespaces).isEmpty, from != nil || to != nil, kind != nil, currency != nil, merchantId != nil,
         categoryId != nil || uncategorized, !tagIds.isEmpty, !accountRef.trimmingCharacters(in: .whitespaces).isEmpty,
         minAmountMinor != nil || maxAmountMinor != nil, evidenceId != nil].filter { $0 }.count
    }

    /// Amount sorting is only defined within one currency.
    var sortNeedsCurrency: Bool { sort.hasPrefix("amount") && currency == nil }

    func input(limit: Int = 50, offset: Int = 0) -> SearchInput {
        let q = search.trimmingCharacters(in: .whitespaces), account = accountRef.trimmingCharacters(in: .whitespaces)
        return SearchInput(
            limit: limit, offset: offset, from: from, to: to, currency: currency, search: q.isEmpty ? nil : q, kind: kind,
            merchantId: merchantId, categoryId: uncategorized ? nil : categoryId, uncategorized: uncategorized ? true : nil,
            tagIds: tagIds.isEmpty ? nil : tagIds, accountRef: account.isEmpty ? nil : account, evidenceId: evidenceId,
            minAmountMinor: minAmountMinor, maxAmountMinor: maxAmountMinor,
            state: state == "active" ? nil : state, sort: sort == "date_desc" ? nil : sort)
    }
}

/// One removable summary of an active condition, shown under the search field.
struct FilterChip: Identifiable {
    let id: String
    let label: String
    let clear: (inout SpendFilter) -> Void
}

enum DatePreset: String, CaseIterable, Identifiable {
    case any, thisMonth, lastMonth, last30, custom
    var id: String { rawValue }
    var title: String {
        switch self { case .any: "Any time"; case .thisMonth: "This month"; case .lastMonth: "Last month"; case .last30: "Last 30 days"; case .custom: "Custom" }
    }

    /// The calendar-date range for a preset ("custom" keeps whatever the user picked).
    func range(today: Date = .now, calendar: Calendar = .current) -> (from: String?, to: String?)? {
        func str(_ d: Date) -> String { CalendarDate.string(from: d, calendar: calendar) }
        switch self {
        case .any: return (nil, nil)
        case .thisMonth:
            guard let start = calendar.dateInterval(of: .month, for: today)?.start else { return nil }
            return (str(start), str(today))
        case .lastMonth:
            guard let thisStart = calendar.dateInterval(of: .month, for: today)?.start, let lastStart = calendar.date(byAdding: .month, value: -1, to: thisStart),
                  let lastEnd = calendar.date(byAdding: .day, value: -1, to: thisStart) else { return nil }
            return (str(lastStart), str(lastEnd))
        case .last30:
            guard let start = calendar.date(byAdding: .day, value: -29, to: today) else { return nil }
            return (str(start), str(today))
        case .custom: return nil
        }
    }

    /// Which preset a from/to pair is, for showing the picker's current value.
    static func matching(from: String?, to: String?, today: Date = .now, calendar: Calendar = .current) -> DatePreset {
        if from == nil && to == nil { return .any }
        for p in [DatePreset.thisMonth, .lastMonth, .last30] { if let r = p.range(today: today, calendar: calendar), r.from == from, r.to == to { return p } }
        return .custom
    }
}

extension SpendFilter {
    /// The active narrowing conditions as chips. Names come from the caller so this stays independent of the directory.
    func chips(category: (String) -> String?, merchant: (String) -> String?, tag: (String) -> String?, evidence: Bool = false) -> [FilterChip] {
        var out: [FilterChip] = []
        let q = search.trimmingCharacters(in: .whitespaces)
        if !q.isEmpty { out.append(FilterChip(id: "search", label: "“\(q)”") { $0.search = "" }) }
        if from != nil || to != nil {
            let label = switch (from, to) {
            case (let f?, let t?): "\(DayGroups.title(f)) – \(DayGroups.title(t))"
            case (let f?, nil): "From \(DayGroups.title(f))"
            case (nil, let t?): "Until \(DayGroups.title(t))"
            default: ""
            }
            out.append(FilterChip(id: "dates", label: label) { $0.from = nil; $0.to = nil })
        }
        if let kind { out.append(FilterChip(id: "kind", label: kind == "expense" ? "Expenses" : kind == "refund" ? "Refunds" : "Transfers") { $0.kind = nil }) }
        if let currency { out.append(FilterChip(id: "currency", label: currency) { $0.currency = nil; if $0.sort.hasPrefix("amount") { $0.sort = "date_desc" }; $0.minAmountMinor = nil; $0.maxAmountMinor = nil }) }
        if uncategorized { out.append(FilterChip(id: "category", label: "Uncategorized") { $0.uncategorized = false }) }
        else if let categoryId { out.append(FilterChip(id: "category", label: category(categoryId) ?? "Category") { $0.categoryId = nil }) }
        if let merchantId { out.append(FilterChip(id: "merchant", label: merchant(merchantId) ?? "Merchant") { $0.merchantId = nil }) }
        for t in tagIds { out.append(FilterChip(id: "tag-\(t)", label: "#" + (tag(t) ?? "tag")) { $0.tagIds.removeAll { $0 == t } }) }
        let account = accountRef.trimmingCharacters(in: .whitespaces)
        if !account.isEmpty { out.append(FilterChip(id: "account", label: "Account \(account)") { $0.accountRef = "" }) }
        if minAmountMinor != nil || maxAmountMinor != nil {
            let c = currency ?? "USD"
            let label = switch (minAmountMinor, maxAmountMinor) {
            case (let lo?, let hi?): "\(Money.format(lo, currency: c)) – \(Money.format(hi, currency: c))"
            case (let lo?, nil): "At least \(Money.format(lo, currency: c))"
            case (nil, let hi?): "At most \(Money.format(hi, currency: c))"
            default: ""
            }
            out.append(FilterChip(id: "amount", label: label) { $0.minAmountMinor = nil; $0.maxAmountMinor = nil })
        }
        if evidenceId != nil { out.append(FilterChip(id: "evidence", label: "Has this receipt") { $0.evidenceId = nil }) }
        return out
    }
}
