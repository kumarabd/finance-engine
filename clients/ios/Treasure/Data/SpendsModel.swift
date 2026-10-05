import Foundation
import Observation

@MainActor @Observable
final class SpendsModel {
    var spends: [Spend]
    private(set) var loading = false
    private(set) var problem: String?
    private(set) var hasMore = false
    /// Everything the list is narrowed by. Changing it is followed by `reload()`.
    var filter = SpendFilter()
    var search: String { get { filter.search } set { filter.search = newValue } }
    private var nextOffset: Int?
    private var generation = 0   // drops responses from a superseded query
    let outbox: Outbox
    private let engine: Engine
    private let cache: DiskCache
    private let resolveMerchant: (String) async -> Api<String?>

    init(engine: Engine, cache: DiskCache, resolveMerchant: @escaping (String) async -> Api<String?>) {
        self.engine = engine; self.cache = cache; self.resolveMerchant = resolveMerchant
        outbox = Outbox(cache: cache)
        spends = cache.load([Spend].self, "spends") ?? []
    }

    /// The last delete (one spend or many), offered for undo for a few seconds.
    struct Undo: Identifiable, Equatable { let id = UUID(); var spends: [Spend] }
    private(set) var undo: Undo?
    /// Bumps on every local change so dependent screens (totals, charts) know to refresh.
    private(set) var revision = 0

    var groups: [DayGroup] { DayGroups.make(spends) }

    /// Reload from the start (pull to refresh, search change). Keeps the cached list visible until the answer lands.
    func reload() async {
        generation += 1
        let mine = generation
        loading = true
        defer { if mine == generation { loading = false } }
        let asked = filter
        let r: Api<Page<Spend>> = await engine.call("spends_search", asked.input())
        guard mine == generation else { return }
        switch r {
        case .ok(let page):
            spends = page.items; nextOffset = page.nextOffset; hasMore = page.nextOffset != nil; problem = nil
            if asked.isDefault { cache.save(page.items, as: "spends") }
        default:
            problem = r.problem
            if !asked.isDefault { spends = [] }
        }
    }

    func loadMore(after last: Spend) async {
        guard hasMore, !loading, last.id == spends.last?.id, let offset = nextOffset else { return }
        generation += 1
        let mine = generation
        loading = true
        defer { if mine == generation { loading = false } }
        let r: Api<Page<Spend>> = await engine.call("spends_search", filter.input(offset: offset))
        guard mine == generation else { return }
        if case .ok(let page) = r {
            spends += page.items; nextOffset = page.nextOffset; hasMore = page.nextOffset != nil
        } else { problem = r.problem }
    }

    // MARK: Offline

    var pending: [OutboxItem] { outbox.items }

    func enqueue(_ spend: SpendInput, merchantName: String?, key: String) {
        outbox.add(OutboxItem(id: key, spend: spend, merchantName: merchantName, createdAt: .now))
    }

    /// Sends spends saved while offline. Safe to call at any time: it does nothing when the queue is empty or already running.
    func flushOutbox() async {
        guard !outbox.items.isEmpty else { return }
        let created = await outbox.flush { [engine, resolveMerchant] item in
            var input = item.spend
            if let name = item.merchantName {
                let m = await resolveMerchant(name)
                if let bad: Api<Spend> = m.failure() { return bad }
                if case .ok(let id) = m { input.merchantId = id }
            }
            return await engine.call("spends_create", CreateSpendInput(idempotencyKey: item.id, spend: input))
        }
        for s in created { stored(s) }
    }

    // MARK: Writes

    func create(_ input: SpendInput, key: String) async -> Api<Spend> {
        let r: Api<Spend> = await engine.call("spends_create", CreateSpendInput(idempotencyKey: key, spend: input))
        if case .ok(let s) = r { stored(s) }
        return r
    }

    func update(_ spend: Spend, to input: SpendInput, key: String) async -> Api<Spend> {
        let r: Api<Spend> = await engine.call("spends_update", UpdateSpendInput(idempotencyKey: key, id: spend.id, expectedVersion: spend.version, spend: input))
        if case .ok(let s) = r { stored(s) }
        return r
    }

    /// The newest server copy, for resolving a version conflict.
    func fetch(_ id: String) async -> Spend? {
        let r: Api<Spend> = await engine.call("spends_get", GetInput(id: id))
        if case .ok(let s) = r { stored(s); return s }
        return nil
    }

    /// Soft delete: the row leaves the list at once and comes back if the server refuses.
    func delete(_ spend: Spend) async {
        let before = spends
        spends.removeAll { $0.id == spend.id }
        let r: Api<Spend> = await engine.call("spends_delete", LifecycleInput(idempotencyKey: UUID().uuidString, id: spend.id, expectedVersion: spend.version))
        if case .ok(let gone) = r { undo = Undo(spends: [gone]); revision += 1; persist() }
        else { spends = before; problem = r.problem }
    }

    func undoDelete() async {
        guard let u = undo else { return }
        undo = nil
        let outcome = await bulkLifecycle("spends_bulk_restore", u.spends)
        problem = outcome.failure
        if outcome.failure == nil { for s in outcome.done { merge(s) }; revision += 1; persist() }
    }

    // MARK: Bulk

    /// What a bulk call got done. Work is sent in batches of 100 (the engine's limit, each batch all-or-nothing), so a failure
    /// partway leaves the earlier batches applied: `done` says which.
    struct BulkOutcome {
        var done: [Spend]
        var total: Int
        var failure: String?
        var ok: Bool { failure == nil }
    }

    /// Apply one change to many spends: categorize, retag, set the merchant, and so on.
    func bulkUpdate(_ targets: [Spend], patch: SpendPatch) async -> BulkOutcome {
        var done: [Spend] = []
        for chunk in targets.chunked(100) {
            let r: Api<SpendsResult> = await engine.call("spends_bulk_update",
                BulkUpdateInput(idempotencyKey: UUID().uuidString, records: chunk.map { Versioned(id: $0.id, expectedVersion: $0.version) }, patch: patch))
            guard case .ok(let result) = r else { return finish(done, targets.count, r) }
            done += result.items
        }
        for s in done { merge(s) }
        revision += 1; persist()
        return BulkOutcome(done: done, total: targets.count, failure: nil)
    }

    /// Soft-delete many. Refunds go before their original expenses; the engine orders that. Offers Undo.
    func bulkDelete(_ targets: [Spend]) async -> BulkOutcome {
        let outcome = await bulkLifecycle("spends_bulk_delete", targets)
        let gone = Set(outcome.done.map(\.id))
        if !gone.isEmpty {
            spends.removeAll { gone.contains($0.id) }
            undo = Undo(spends: outcome.done)
            revision += 1; persist()
        }
        return outcome
    }

    /// Bring deleted spends back (from Trash).
    func bulkRestore(_ targets: [Spend]) async -> BulkOutcome {
        let outcome = await bulkLifecycle("spends_bulk_restore", targets)
        let back = Set(outcome.done.map(\.id))
        if !back.isEmpty {
            if filter.state == "deleted" { spends.removeAll { back.contains($0.id) } } else { for s in outcome.done { merge(s) } }
            revision += 1; persist()
        }
        return outcome
    }

    private func bulkLifecycle(_ op: String, _ targets: [Spend]) async -> BulkOutcome {
        var done: [Spend] = []
        for chunk in targets.chunked(100) {
            let r: Api<SpendsResult> = await engine.call(op, BulkLifecycleInput(idempotencyKey: UUID().uuidString, records: chunk.map { Versioned(id: $0.id, expectedVersion: $0.version) }))
            guard case .ok(let result) = r else { return finish(done, targets.count, r) }
            done += result.items
        }
        return BulkOutcome(done: done, total: targets.count, failure: nil)
    }

    private func finish<T>(_ done: [Spend], _ total: Int, _ failed: Api<T>) -> BulkOutcome {
        var why = failed.problem ?? "Something went wrong."
        if case .failed(let code, _) = failed, code == "conflict" { why = "Some of these changed elsewhere. Refresh the list and try again." }
        if !done.isEmpty { why = "Changed \(done.count) of \(total) before a problem. \(why)" }
        for s in done { merge(s) }
        if !done.isEmpty { revision += 1; persist() }
        return BulkOutcome(done: done, total: total, failure: why)
    }

    func dismissUndo(_ id: UUID) { if undo?.id == id { undo = nil } }

    private func stored(_ s: Spend) {
        revision += 1
        merge(s)
        persist()
    }
    /// Swaps in the newest copy of a spend without announcing a change (callers bump `revision` once for a batch).
    private func merge(_ s: Spend) { spends = SpendOrder.upsert(spends, s) }
    private func persist() { if filter.isDefault { cache.save(spends, as: "spends") } }
}

extension Array {
    func chunked(_ size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: size).map { Array(self[$0..<Swift.min($0 + size, count)]) }
    }
}
