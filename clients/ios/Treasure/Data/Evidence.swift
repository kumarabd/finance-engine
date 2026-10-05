import Foundation
import Observation

/// A reference to a supporting document (receipt, statement). The engine stores the reference and never fetches the file.
struct Evidence: Codable, Identifiable, Equatable, Hashable {
    var id: String
    var title: String
    var sourceRef: String
    var mediaType: String?
    var checksum: String?
    var notes: String?
    var version: Int64
    var deletedAt: String?
    enum CodingKeys: String, CodingKey { case id, title, sourceRef = "source_ref", mediaType = "media_type", checksum, notes, version, deletedAt = "deleted_at" }
}

struct EvidenceInput: Encodable {
    var title: String
    var sourceRef: String
    var mediaType: String?
    var checksum: String?
    var notes: String?
    enum CodingKeys: String, CodingKey { case title, sourceRef = "source_ref", mediaType = "media_type", checksum, notes }
}
struct NewEvidenceInput: Encodable {
    var idempotencyKey: String
    var evidence: EvidenceInput
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Keys.self)
        try c.encode(idempotencyKey, forKey: .key)
        try evidence.encode(to: encoder)
    }
    enum Keys: String, CodingKey { case key = "idempotency_key" }
}
struct UpdateEvidenceInput: Encodable {
    var idempotencyKey: String
    var id: String
    var expectedVersion: Int64
    var evidence: EvidenceInput
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Keys.self)
        try c.encode(idempotencyKey, forKey: .key); try c.encode(id, forKey: .id); try c.encode(expectedVersion, forKey: .version)
        try evidence.encode(to: encoder)
    }
    enum Keys: String, CodingKey { case key = "idempotency_key", id, version = "expected_version" }
}
struct DeleteEvidenceInput: Encodable {
    var idempotencyKey: String
    var id: String
    var expectedVersion: Int64
    var detach: Bool
    enum CodingKeys: String, CodingKey { case idempotencyKey = "idempotency_key", id, expectedVersion = "expected_version", detach }
}
struct EvidenceLinkInput: Encodable {
    var idempotencyKey: String
    var id: String                // the spend
    var expectedVersion: Int64     // the spend's version
    var evidenceId: String
    enum CodingKeys: String, CodingKey { case idempotencyKey = "idempotency_key", id, expectedVersion = "expected_version", evidenceId = "evidence_id" }
}
struct EvidencePageInput: Encodable {
    var limit = 200
    var offset = 0
    var state: String?
    var search: String?
}

@MainActor @Observable
final class EvidenceModel {
    private(set) var items: [Evidence] = []
    private(set) var loading = true
    var problem: String?
    var showDeleted = false { didSet { Task { await load() } } }
    /// A delete the engine refused because spends are still linked: the user must confirm detaching them.
    var needsDetach: Evidence?
    private let engine: Engine
    private let afterChange: () async -> Void

    init(engine: Engine, afterChange: @escaping () async -> Void = {}) { self.engine = engine; self.afterChange = afterChange }

    func load() async {
        defer { loading = false }
        var all: [Evidence] = [], offset = 0
        while true {
            let r: Api<Page<Evidence>> = await engine.call("evidence_list", EvidencePageInput(offset: offset, state: showDeleted ? "deleted" : nil))
            guard case .ok(let page) = r else { problem = r.problem; return }
            all += page.items
            guard let next = page.nextOffset else { break }
            offset = next
        }
        items = all; problem = nil
    }

    @discardableResult
    func create(_ input: EvidenceInput, key: String = UUID().uuidString) async -> Evidence? {
        let r: Api<Evidence> = await engine.call("evidence_create", NewEvidenceInput(idempotencyKey: key, evidence: input))
        guard case .ok(let e) = r else { problem = r.problem; return nil }
        items.insert(e, at: 0); problem = nil
        return e
    }

    func update(_ e: Evidence, to input: EvidenceInput) async -> Bool {
        let r: Api<Evidence> = await engine.call("evidence_update", UpdateEvidenceInput(idempotencyKey: UUID().uuidString, id: e.id, expectedVersion: e.version, evidence: input))
        guard case .ok(let new) = r else { problem = r.problem; return false }
        if let i = items.firstIndex(where: { $0.id == new.id }) { items[i] = new }
        problem = nil
        return true
    }

    /// `detach` unlinks it from every spend first; without it the engine refuses while any spend still uses the document.
    func delete(_ e: Evidence, detach: Bool = false) async {
        let r: Api<Evidence> = await engine.call("evidence_delete", DeleteEvidenceInput(idempotencyKey: UUID().uuidString, id: e.id, expectedVersion: e.version, detach: detach))
        switch r {
        case .ok: items.removeAll { $0.id == e.id }; problem = nil; needsDetach = nil; if detach { await afterChange() }
        case .failed(let code, _) where code == "conflict" && !detach: needsDetach = e
        default: problem = r.problem
        }
    }

    func restore(_ e: Evidence) async {
        let r: Api<Evidence> = await engine.call("evidence_restore", LifecycleInput(idempotencyKey: UUID().uuidString, id: e.id, expectedVersion: e.version))
        if case .ok = r { items.removeAll { $0.id == e.id }; problem = nil } else { problem = r.problem }
    }

    func get(_ id: String) async -> Evidence? {
        let r: Api<Evidence> = await engine.call("evidence_get", GetInput(id: id))
        if case .ok(let e) = r { return e }
        return nil
    }
}
