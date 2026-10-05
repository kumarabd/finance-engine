import Foundation
import Observation

/// A spend created while the engine couldn't be reached. Its id is the idempotency key, so sending it again after a
/// dropped response can never create a second record.
struct OutboxItem: Codable, Identifiable, Equatable {
    var id: String
    var spend: SpendInput
    /// A merchant typed by name that didn't exist yet: creating one needs the network, so it is resolved when sent.
    var merchantName: String?
    var createdAt: Date
    var failure: String?
    /// Times the engine answered 500 for this spend. Offline and "busy" don't count; a bug that repeats must not block the queue.
    var attempts: Int?
}

@MainActor @Observable
final class Outbox {
    private(set) var items: [OutboxItem]
    private let cache: DiskCache
    private let key: String
    private var flushing = false

    init(cache: DiskCache, key: String = "outbox") {
        self.cache = cache
        self.key = key
        items = cache.load([OutboxItem].self, key, durable: true) ?? []
    }

    func add(_ item: OutboxItem) { items.append(item); save() }
    func discard(_ id: String) { items.removeAll { $0.id == id }; save() }
    static let maxServerErrors = 5

    /// Puts a spend that gave up back in the queue, with the same key.
    func retry(_ id: String) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        items[i].failure = nil; items[i].attempts = nil
        save()
    }

    /// Sends oldest first and returns what was created. Stops at the first connectivity or sign-in problem so order is
    /// kept; the engine refusing one item marks it failed (for the user to see) and carries on with the rest.
    func flush(send: (OutboxItem) async -> Api<Spend>) async -> [Spend] {
        guard !flushing else { return [] }
        flushing = true
        defer { flushing = false }
        var created: [Spend] = []
        for item in items where item.failure == nil {
            switch await send(item) {
            case .ok(let s):
                items.removeAll { $0.id == item.id }; created.append(s)
            case .retry, .unauthorized, .notProvisioned:
                save(); return created
            case .serverError(let message):
                guard let i = items.firstIndex(where: { $0.id == item.id }) else { break }
                items[i].attempts = (items[i].attempts ?? 0) + 1
                if (items[i].attempts ?? 0) < Self.maxServerErrors { save(); return created }   // try again later, in order
                items[i].failure = "Treasure kept failing to save this (\(message)). Retry it, or discard it."
            case .failed(_, let message):
                if let i = items.firstIndex(where: { $0.id == item.id }) { items[i].failure = message }
            }
            save()
        }
        return created
    }

    private func save() { cache.save(items, as: key, durable: true) }
}

extension Outbox {
    /// How a pending item looks in a list.
    static func asSpend(_ item: OutboxItem) -> Spend {
        var s = Spend(id: item.id, version: 0, occurredOn: item.spend.occurredOn, kind: item.spend.kind,
                      amountMinor: item.spend.amountMinor, currency: item.spend.currency)
        s.description = item.merchantName ?? item.spend.description
        s.allocations = item.spend.allocations
        s.merchantId = item.spend.merchantId
        return s
    }
}
