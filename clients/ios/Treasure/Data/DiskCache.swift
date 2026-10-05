import CryptoKit
import Foundation

/// Files for ONE signed-in user: everything lives under a folder named for that user, so another account on the same phone
/// never sees (or sends) it.
///
/// `durable` data is the user's own (spends not yet sent) and lives in Application Support, which iOS never purges.
/// Everything else is re-fetchable and lives in Caches, which iOS may clear when storage runs low, and is dropped on sign-out.
// ponytail: JSON files hold only the first page; move to SwiftData once the cache must be queried or edited offline.
struct DiskCache {
    let owner: String

    private var folder: String {
        owner.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_" || $0 == "-") } && !owner.isEmpty
            ? owner : SHA256.hash(data: Data(owner.utf8)).map { String(format: "%02x", $0) }.joined()
    }
    private func root(durable: Bool) -> URL {
        FileManager.default.urls(for: durable ? .applicationSupportDirectory : .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("treasure", isDirectory: true).appendingPathComponent(folder, isDirectory: true)
    }
    private func url(_ key: String, durable: Bool) -> URL {
        let dir = root(durable: durable)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("\(key).json")
    }

    func save<T: Encodable>(_ value: T, as key: String, durable: Bool = false) {
        try? JSONEncoder().encode(value).write(to: url(key, durable: durable), options: .atomic)
    }
    func load<T: Decodable>(_ type: T.Type, _ key: String, durable: Bool = false) -> T? {
        guard let data = try? Data(contentsOf: url(key, durable: durable)) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }
    func clear(_ key: String, durable: Bool = false) { try? FileManager.default.removeItem(at: url(key, durable: durable)) }

    /// On sign-out: this user's re-fetchable data goes. Their unsent spends stay, under their own folder, and are sent only
    /// when they sign in again.
    func purgeCaches() { try? FileManager.default.removeItem(at: root(durable: false)) }

    /// Removes everything for this user, unsent spends included. Used by tests.
    func destroy() {
        try? FileManager.default.removeItem(at: root(durable: false))
        try? FileManager.default.removeItem(at: root(durable: true))
    }

    /// One-time cleanup of files written by builds before storage was per-user. Their owner is unknown, so they are removed
    /// rather than guessed at (a guess is exactly how one account's data reaches another).
    static func removeLegacyFiles() {
        let fm = FileManager.default
        for dir in [fm.urls(for: .cachesDirectory, in: .userDomainMask)[0], fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]] {
            for f in (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [] where f.lastPathComponent.hasPrefix("treasure-") && f.pathExtension == "json" {
                try? fm.removeItem(at: f)
            }
        }
    }
}
