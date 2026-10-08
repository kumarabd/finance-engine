import Foundation

/// Budgets, as the app sees them.
///
/// One read drives everything: `budget_status` returns each budget *and* how far
/// through it this period is, computed from live spending, so the screen never
/// has to combine a limit list with a separate totals call and never disagrees
/// with what the server thinks.
///
/// Writes are idempotent by key like every other write in this app, and carry the
/// version last read so two devices cannot silently overwrite each other's limit.
@MainActor
@Observable
final class BudgetsModel {
    private let engine: Engine
    private let directory: Directory

    private(set) var statuses: [BudgetStatus] = []
    private(set) var loading = false
    private(set) var problem: String?

    init(engine: Engine, directory: Directory) {
        self.engine = engine
        self.directory = directory
    }

    /// The currency a new budget should default to: whatever the user's budgets
    /// already use, else the last currency they spent in. Currencies are never
    /// combined, so this is only ever a default — the picker stays available.
    var defaultCurrency: String {
        statuses.first?.currency ?? UserDefaults.standard.string(forKey: "lastCurrency") ?? "USD"
    }

    var hasAny: Bool { !statuses.isEmpty }
    /// Budgets past their alert line, which is what Home surfaces.
    var alerted: [BudgetStatus] { statuses.filter(\.alerted) }

    func reload() async {
        loading = true
        defer { loading = false }
        let result: Api<StatusResult> = await engine.call("budget_status", StatusInput())
        switch result {
        case .ok(let value):
            statuses = value.items
            problem = nil
        default:
            problem = result.problem
        }
    }

    @discardableResult
    func create(name: String, categoryID: String?, period: String, limitMinor: Int64,
                currency: String, notifyAtPercent: Int) async -> Bool {
        let input = CreateBudgetInput(
            idempotencyKey: UUID().uuidString,
            budget: BudgetInput(name: name, categoryID: categoryID, period: period,
                                limitMinor: limitMinor, currency: currency,
                                notifyAtPercent: notifyAtPercent)
        )
        let result: Api<Budget> = await engine.call("budgets_create", input)
        switch result {
        case .ok:
            await reload()
            return true
        default:
            problem = result.problem
            return false
        }
    }

    /// Category and kind are fixed at creation — moving a budget to another
    /// category would invalidate every crossing already recorded against the old
    /// one, so the server refuses and the editor does not offer it.
    @discardableResult
    func update(_ status: BudgetStatus, name: String, period: String, limitMinor: Int64,
                notifyAtPercent: Int) async -> Bool {
        let input = UpdateBudgetInput(
            idempotencyKey: UUID().uuidString,
            id: status.id,
            expectedVersion: status.version,
            budget: BudgetInput(name: name, categoryID: status.categoryID, period: period,
                                limitMinor: limitMinor, currency: status.currency,
                                notifyAtPercent: notifyAtPercent)
        )
        let result: Api<Budget> = await engine.call("budgets_update", input)
        switch result {
        case .ok:
            await reload()
            return true
        default:
            problem = result.problem
            return false
        }
    }

    @discardableResult
    func delete(_ status: BudgetStatus) async -> Bool {
        let input = LifecycleInput(idempotencyKey: UUID().uuidString, id: status.id,
                                   expectedVersion: status.version)
        let result: Api<Budget> = await engine.call("budgets_delete", input)
        switch result {
        case .ok:
            await reload()
            return true
        default:
            problem = result.problem
            return false
        }
    }

    /// What one budget has noticed, newest first.
    func fires(for status: BudgetStatus) async -> Api<[BudgetFire]> {
        let result: Api<FiresResult> = await engine.call("budget_fires_list", ListFiresInput(budgetID: status.id))
        return result.map(\.items)
    }
}
