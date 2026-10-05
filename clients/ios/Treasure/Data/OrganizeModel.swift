import Foundation
import Observation

enum DimensionKind: String, CaseIterable, Identifiable {
    case category, tag, merchant
    var id: String { rawValue }
    var plural: String { self == .category ? "categories" : "\(rawValue)s" }   // operation prefix too: categories_list, tags_list...
    var title: String { plural.capitalized }
}

struct UpdateDimensionInput: Encodable {
    var idempotencyKey: String
    var id: String
    var expectedVersion: Int64
    var name: String
    var aliases: [String]?   // a merchant's aliases must be sent back, or the update clears them
    enum CodingKeys: String, CodingKey { case idempotencyKey = "idempotency_key", id, expectedVersion = "expected_version", name, aliases }
}
struct MergeDimensionInput: Encodable {
    var idempotencyKey: String
    var id: String
    var expectedVersion: Int64
    var targetId: String
    var targetVersion: Int64
    enum CodingKeys: String, CodingKey { case idempotencyKey = "idempotency_key", id, expectedVersion = "expected_version", targetId = "target_id", targetVersion = "target_version" }
}
struct DeleteDimensionInput: Encodable {
    var idempotencyKey: String
    var id: String
    var expectedVersion: Int64
    var replacementId: String?
    var replacementVersion: Int64?
    enum CodingKeys: String, CodingKey { case idempotencyKey = "idempotency_key", id, expectedVersion = "expected_version", replacementId = "replacement_id", replacementVersion = "replacement_version" }
}
struct MergeResult: Decodable { var changedSpends: Int; enum CodingKeys: String, CodingKey { case changedSpends = "changed_spends" } }

@MainActor @Observable
final class OrganizeModel {
    let kind: DimensionKind
    private(set) var items: [Dimension] = []
    private(set) var loading = true
    var problem: String?
    /// A delete the engine refused because spends still use the record: the user must choose a replacement.
    var needsReplacement: Dimension?
    var showDeleted = false { didSet { Task { await load() } } }
    private let engine: Engine
    private let afterChange: () async -> Void

    init(kind: DimensionKind, engine: Engine, afterChange: @escaping () async -> Void) {
        self.kind = kind; self.engine = engine; self.afterChange = afterChange
    }

    func load() async {
        defer { loading = false }
        var all: [Dimension] = [], offset = 0
        while true {
            let r: Api<Page<Dimension>> = await engine.call("\(kind.plural)_list", PageInput(offset: offset, state: showDeleted ? "deleted" : nil))
            guard case .ok(let page) = r else { problem = r.problem; return }
            all += page.items
            guard let next = page.nextOffset else { break }
            offset = next
        }
        items = all; problem = nil
    }

    func create(_ name: String) async {
        let r: Api<Dimension> = await engine.call("\(kind.plural)_create", CreateDimensionInput(idempotencyKey: UUID().uuidString, name: name))
        await finish(r.problem, reassigned: false)
    }

    /// Replace a merchant's alternative names (what the importer and search also match on).
    func setAliases(_ d: Dimension, to aliases: [String]) async {
        let r: Api<Dimension> = await engine.call("\(kind.plural)_update",
            UpdateDimensionInput(idempotencyKey: UUID().uuidString, id: d.id, expectedVersion: d.version ?? 0, name: d.name, aliases: aliases))
        await finish(r.problem, reassigned: false)
    }

    /// Bring back a deleted record. The engine refuses if its name (or an alias) has since been taken.
    func restore(_ d: Dimension) async {
        let r: Api<Dimension> = await engine.call("\(kind.plural)_restore", LifecycleInput(idempotencyKey: UUID().uuidString, id: d.id, expectedVersion: d.version ?? 0))
        await finish(r.problem, reassigned: false)
    }

    func history(_ d: Dimension) async -> Api<Page<Change>> {
        await engine.call("history_list", HistoryInput(entityType: kind.rawValue, id: d.id))
    }

    func rename(_ d: Dimension, to name: String) async {
        let r: Api<Dimension> = await engine.call("\(kind.plural)_update",
            UpdateDimensionInput(idempotencyKey: UUID().uuidString, id: d.id, expectedVersion: d.version ?? 0, name: name, aliases: d.aliases))
        await finish(r.problem, reassigned: false)
    }

    func merge(_ source: Dimension, into target: Dimension) async {
        let r: Api<MergeResult> = await engine.call("\(kind.plural)_merge",
            MergeDimensionInput(idempotencyKey: UUID().uuidString, id: source.id, expectedVersion: source.version ?? 0, targetId: target.id, targetVersion: target.version ?? 0))
        await finish(r.problem, reassigned: true)
    }

    /// Plain delete. If spends still use it the engine answers `conflict`, and we ask for a replacement instead.
    func delete(_ d: Dimension, replacement: Dimension? = nil) async {
        let r: Api<Dimension> = await engine.call("\(kind.plural)_delete",
            DeleteDimensionInput(idempotencyKey: UUID().uuidString, id: d.id, expectedVersion: d.version ?? 0,
                                 replacementId: replacement?.id, replacementVersion: replacement?.version))
        if case .failed("conflict", let m) = r, replacement == nil, m.contains("replacement") { needsReplacement = d; return }
        await finish(r.problem, reassigned: replacement != nil)
    }

    private func finish(_ failure: String?, reassigned: Bool) async {
        if let failure { problem = failure; await load(); return }
        await load()
        await afterChange()   // names and references changed: refresh the directory and spends
        _ = reassigned
    }
}
