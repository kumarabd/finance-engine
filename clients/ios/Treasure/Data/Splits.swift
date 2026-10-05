import Foundation

/// One line of a split purchase: a category (nil = uncategorized) and its share, in minor units.
struct SplitRow: Identifiable, Equatable {
    var id = UUID()
    var categoryId: String?
    var amountMinor: Int64
}

/// The engine's rules for splitting a spend across categories: every share is at least 1, a category appears once, and the
/// shares add up to the amount exactly. Checked here so the editor can say what's wrong before anything is sent.
enum Splits {
    static func remaining(total: Int64, rows: [SplitRow]) -> Int64 { total - rows.reduce(0) { $0 + $1.amountMinor } }

    /// What is wrong with a split, or nil when it is valid (or not a split at all).
    static func problem(total: Int64, rows: [SplitRow], currency: String) -> String? {
        guard rows.count > 1 else { return nil }
        if rows.contains(where: { $0.amountMinor < 1 }) { return "Each split needs an amount." }
        let categories = rows.map { $0.categoryId ?? "" }
        if Set(categories).count != categories.count { return "Each category can only be used once." }
        let left = remaining(total: total, rows: rows)
        if left > 0 { return "\(Money.format(left, currency: currency)) left to assign." }
        if left < 0 { return "Over by \(Money.format(-left, currency: currency))." }
        return nil
    }

    static func allocations(_ rows: [SplitRow]) -> [Allocation] { rows.map { Allocation(categoryId: $0.categoryId, amountMinor: $0.amountMinor) } }

    /// Rows for editing a spend that is already split (a single allocation is not a split).
    static func rows(from allocations: [Allocation]?) -> [SplitRow] {
        guard let a = allocations, a.count > 1 else { return [] }
        return a.map { SplitRow(categoryId: $0.categoryId, amountMinor: $0.amountMinor) }
    }

    /// Starting a split: two even halves, so it opens already adding up and usually needs one number changed (and two categories
    /// chosen). The current category keeps the larger half.
    static func start(categoryId: String?, total: Int64) -> [SplitRow] {
        [SplitRow(categoryId: categoryId, amountMinor: total - total / 2), SplitRow(categoryId: nil, amountMinor: total / 2)]
    }

    /// A new line takes whatever is left, so adding one is usually all that's needed before choosing its category.
    static func adding(to rows: [SplitRow], total: Int64) -> [SplitRow] {
        rows + [SplitRow(categoryId: nil, amountMinor: max(0, remaining(total: total, rows: rows)))]
    }
}
