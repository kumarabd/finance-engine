import Foundation
import Observation

/// Merchant and category names by id, so spends can show names rather than UUIDs.
@MainActor @Observable
final class Directory {
    private(set) var merchants: [String: Dimension] = [:]
    private(set) var categories: [String: Dimension] = [:]
    private(set) var tags: [String: Dimension] = [:]
    private let engine: Engine
    private let cache: DiskCache

    init(engine: Engine, cache: DiskCache) {
        self.engine = engine
        self.cache = cache
        merchants = Dictionary(uniqueKeysWithValues: (cache.load([Dimension].self, "merchants") ?? []).map { ($0.id, $0) })
        categories = Dictionary(uniqueKeysWithValues: (cache.load([Dimension].self, "categories") ?? []).map { ($0.id, $0) })
        tags = Dictionary(uniqueKeysWithValues: (cache.load([Dimension].self, "tags") ?? []).map { ($0.id, $0) })
    }

    func merchant(_ id: String?) -> String? { id.flatMap { merchants[$0]?.name } }
    func category(_ id: String?) -> String? { id.flatMap { categories[$0]?.name } }
    func tag(_ id: String?) -> String? { id.flatMap { tags[$0]?.name } }
    /// Names for a spend's tag ids, in the order stored, skipping any no longer known.
    func tagNames(_ ids: [String]?) -> [String] { (ids ?? []).compactMap { tags[$0]?.name } }

    /// The id for a typed merchant name: an existing merchant (by name or alias, ignoring case) or a newly created one.
    func resolveMerchant(_ raw: String) async -> Api<String?> {
        let name = raw.trimmingCharacters(in: .whitespaces)
        if name.isEmpty { return .ok(nil) }
        let key = name.lowercased()
        if let m = existingMerchant(key) { return .ok(m.id) }
        var r: Api<Dimension> = await engine.call("merchants_create", CreateDimensionInput(idempotencyKey: UUID().uuidString, name: name))
        // The name is already taken: another device made it, or an earlier attempt succeeded and its reply was lost. Either
        // way the merchant exists, so reload the list and use it rather than failing (and failing a queued spend with it).
        if case .failed(let code, let message) = r, code == "duplicate" || (code == "conflict" && message.contains("already in use")) {
            await refreshMerchants()
            if let m = existingMerchant(key) { return .ok(m.id) }
        }
        if case .ok(let d) = r { merchants[d.id] = d; cache.save(Array(merchants.values), as: "merchants") }
        return r.map { $0.id }
    }

    /// A tag by name (ignoring case), created when it doesn't exist yet. Same lost-reply handling as merchants.
    func resolveTag(_ raw: String) async -> Api<Dimension> {
        let name = raw.trimmingCharacters(in: .whitespaces)
        let key = name.lowercased()
        if let t = tags.values.first(where: { $0.name.lowercased() == key }) { return .ok(t) }
        let r: Api<Dimension> = await engine.call("tags_create", CreateDimensionInput(idempotencyKey: UUID().uuidString, name: name))
        if case .failed(let code, _) = r, code == "duplicate" {
            if let t = await all("tags_list") { tags = Dictionary(uniqueKeysWithValues: t.map { ($0.id, $0) }); cache.save(t, as: "tags") }
            if let t = tags.values.first(where: { $0.name.lowercased() == key }) { return .ok(t) }
        }
        if case .ok(let d) = r { tags[d.id] = d; cache.save(Array(tags.values), as: "tags") }
        return r
    }

    /// Tags ordered by how often the loaded spends use them, then by name.
    func tagsByUse(_ spends: [Spend]) -> [Dimension] {
        var uses: [String: Int] = [:]
        for s in spends { for t in s.tagIds ?? [] { uses[t, default: 0] += 1 } }
        return tags.values.sorted { (uses[$0.id] ?? 0, $1.name.lowercased()) > (uses[$1.id] ?? 0, $0.name.lowercased()) }
    }

    private func existingMerchant(_ lowercasedName: String) -> Dimension? {
        merchants.values.first { $0.name.lowercased() == lowercasedName || ($0.aliases ?? []).contains { $0.lowercased() == lowercasedName } }
    }

    private func refreshMerchants() async {
        if let m = await all("merchants_list") { merchants = Dictionary(uniqueKeysWithValues: m.map { ($0.id, $0) }); cache.save(m, as: "merchants") }
    }

    /// Category ids ordered by how often the loaded spends use them, then by name.
    func categoriesByUse(_ spends: [Spend]) -> [Dimension] {
        var uses: [String: Int] = [:]
        for s in spends { for a in s.allocations ?? [] { if let c = a.categoryId { uses[c, default: 0] += 1 } } }
        return categories.values.sorted { (uses[$0.id] ?? 0, $1.name.lowercased()) > (uses[$1.id] ?? 0, $0.name.lowercased()) }
    }

    func refresh() async {
        if let m = await all("merchants_list") { merchants = Dictionary(uniqueKeysWithValues: m.map { ($0.id, $0) }); cache.save(m, as: "merchants") }
        if let c = await all("categories_list") { categories = Dictionary(uniqueKeysWithValues: c.map { ($0.id, $0) }); cache.save(c, as: "categories") }
        if let t = await all("tags_list") { tags = Dictionary(uniqueKeysWithValues: t.map { ($0.id, $0) }); cache.save(t, as: "tags") }
    }

    private func all(_ op: String) async -> [Dimension]? {
        var items: [Dimension] = [], offset = 0
        while true {
            let r: Api<Page<Dimension>> = await engine.call(op, PageInput(offset: offset))
            guard case .ok(let page) = r else { return nil }
            items += page.items
            guard let next = page.nextOffset else { return items }
            offset = next
        }
    }
}
