import Foundation

/// A spending limit and the alert that goes with it — one thing, not two.
///
/// "Keep me to 20,000 on dining this month" and "my dining budget is 20,000" are
/// the same sentence, so the limit and the alert live on one record. `kind` is
/// `total` when the budget covers all spending, `category` when it covers one; the
/// server sets it from which fields you filled in, never from a flag here.
struct Budget: Decodable, Identifiable, Hashable {
    var id: String
    var version: Int64
    var name: String
    var kind: String
    var categoryID: String?
    var categoryName: String?
    var period: String
    var limitMinor: Int64
    var currency: String
    var notifyAtPercent: Int
    var active: Bool

    var coversAllSpending: Bool { kind == "total" }

    /// What the budget is about, in the user's terms.
    var subject: String { coversAllSpending ? "All spending" : (categoryName ?? "Category") }

    var periodLabel: String { period == "week" ? "week" : "month" }

    enum CodingKeys: String, CodingKey {
        case id, version, name, kind, period, currency, active
        case categoryID = "category_id"
        case categoryName = "category_name"
        case limitMinor = "limit_minor"
        case notifyAtPercent = "notify_at_percent"
    }
}

/// The budget plus how this period is going — what the screen actually renders.
///
/// Computed server-side from live spending rather than from the alert log, so a
/// budget is true about the ledger whether or not a notification was ever
/// delivered.
struct BudgetStatus: Decodable, Identifiable, Hashable {
    var id: String
    var version: Int64
    var name: String
    var kind: String
    var categoryID: String?
    var categoryName: String?
    var period: String
    var limitMinor: Int64
    var currency: String
    var notifyAtPercent: Int
    var active: Bool
    var periodKey: String
    var spentMinor: Int64
    var remainingMinor: Int64
    var percent: Int
    var alertMinor: Int64
    var alerted: Bool

    var budget: Budget {
        Budget(id: id, version: version, name: name, kind: kind, categoryID: categoryID,
               categoryName: categoryName, period: period, limitMinor: limitMinor,
               currency: currency, notifyAtPercent: notifyAtPercent, active: active)
    }

    var subject: String { budget.subject }
    var periodLabel: String { budget.periodLabel }
    var coversAllSpending: Bool { budget.coversAllSpending }

    /// How much of the limit is used, as a 0...1 fraction for drawing. `percent`
    /// itself may exceed 100 and is what gets shown as text.
    var fraction: Double {
        guard limitMinor > 0 else { return 0 }
        return min(1, Double(spentMinor) / Double(limitMinor))
    }

    /// Where the alert line sits on the bar, so the warning is visible before you
    /// cross it rather than only after.
    var alertFraction: Double {
        guard limitMinor > 0 else { return 1 }
        return min(1, Double(alertMinor) / Double(limitMinor))
    }

    var over: Bool { spentMinor > limitMinor }

    enum CodingKeys: String, CodingKey {
        case id, version, name, kind, period, currency, active, percent, alerted
        case categoryID = "category_id"
        case categoryName = "category_name"
        case limitMinor = "limit_minor"
        case notifyAtPercent = "notify_at_percent"
        case periodKey = "period_key"
        case spentMinor = "spent_minor"
        case remainingMinor = "remaining_minor"
        case alertMinor = "alert_minor"
    }
}

/// One recorded crossing. What a budget noticed, and when.
///
/// Timestamps stay strings here, like every other date in this app: the engine
/// sends RFC3339 and no decoder in the client sets a date strategy, so a `Date`
/// field would silently fail to decode. Convert at the edge if a screen ever
/// needs to render one.
struct BudgetFire: Decodable, Identifiable, Hashable {
    var id: String
    var budgetID: String
    var name: String
    var periodKey: String
    var observedMinor: Int64
    var limitMinor: Int64
    var alertMinor: Int64
    var percent: Int
    var currency: String
    var firedAt: String
    var deliveredAt: String?

    enum CodingKeys: String, CodingKey {
        case id, name, percent, currency
        case budgetID = "budget_id"
        case periodKey = "period_key"
        case observedMinor = "observed_minor"
        case limitMinor = "limit_minor"
        case alertMinor = "alert_minor"
        case firedAt = "fired_at"
        case deliveredAt = "delivered_at"
    }
}

// MARK: - Operation inputs

/// The budget fields a create or update sends. `categoryID` nil means a budget on
/// all spending — the server derives `kind` from that rather than taking a flag.
struct BudgetInput: Encodable {
    var name: String
    var categoryID: String?
    var period: String
    var limitMinor: Int64
    var currency: String
    var notifyAtPercent: Int

    enum CodingKeys: String, CodingKey {
        case name, period, currency
        case categoryID = "category_id"
        case limitMinor = "limit_minor"
        case notifyAtPercent = "notify_at_percent"
    }
}

struct CreateBudgetInput: Encodable {
    var idempotencyKey: String
    var budget: BudgetInput
    enum CodingKeys: String, CodingKey { case budget; case idempotencyKey = "idempotency_key" }
}

struct UpdateBudgetInput: Encodable {
    var idempotencyKey: String
    var id: String
    var expectedVersion: Int64
    var budget: BudgetInput
    enum CodingKeys: String, CodingKey {
        case id, budget
        case idempotencyKey = "idempotency_key"
        case expectedVersion = "expected_version"
    }
}

struct StatusInput: Encodable {
    var includeInactive = false
    enum CodingKeys: String, CodingKey { case includeInactive = "include_inactive" }
}

struct StatusResult: Decodable { var items: [BudgetStatus] }
struct BudgetsResult: Decodable { var items: [Budget] }
struct FiresResult: Decodable { var items: [BudgetFire] }
struct ListFiresInput: Encodable {
    var budgetID: String?
    enum CodingKeys: String, CodingKey { case budgetID = "budget_id" }
}
