import Foundation
import Observation

/// Authenticated access to the engine for ONE user: fetches the Clerk token, and retries once with a fresh token on 401.
///
/// It is bound to the user it was created for. The auth service is shared, so a sync still running for user A after the
/// phone has switched to user B would otherwise pick up B's token and write A's data into B's account. Every call checks
/// that the signed-in user is still the owner, and fails as unauthorized (sending nothing) when it isn't.
@MainActor @Observable
final class Engine {
    let owner: String
    private let currentUser: () -> String?
    private let token: (_ skipCache: Bool) async -> String?
    private let api: FinanceAPI

    init(owner: String, currentUser: @escaping () -> String?, token: @escaping (Bool) async -> String?, api: FinanceAPI = .live) {
        self.owner = owner; self.currentUser = currentUser; self.token = token; self.api = api
    }

    convenience init(auth: AuthService, owner: String, api: FinanceAPI = .live) {
        self.init(owner: owner, currentUser: { auth.userId }, token: { await auth.token(skipCache: $0) }, api: api)
    }

    func call<In: Encodable, Out: Decodable>(_ op: String, _ input: In) async -> Api<Out> {
        guard currentUser() == owner, let t = await token(false), currentUser() == owner else { return .unauthorized }
        let r: Api<Out> = await api.call(op, input, token: t)
        if case .unauthorized = r, currentUser() == owner, let fresh = await token(true), currentUser() == owner {
            return await api.call(op, input, token: fresh)
        }
        return r
    }
}

extension Api {
    /// A short, human line for a failed call; nil when it succeeded.
    var problem: String? {
        switch self {
        case .ok: nil
        case .unauthorized: "Session expired. Sign in again."
        case .notProvisioned: "Your finance service isn't set up yet."
        case .retry(let m): "Couldn't reach Treasure: \(m)"
        case .serverError(let m): "Treasure had a problem (\(m)). Try again in a moment."
        case .failed(_, let m): m
        }
    }
}

extension Api {
    func map<U>(_ f: (T) -> U) -> Api<U> {
        switch self {
        case .ok(let v): .ok(f(v))
        case .unauthorized: .unauthorized
        case .notProvisioned: .notProvisioned
        case .retry(let m): .retry(m)
        case .serverError(let m): .serverError(m)
        case .failed(let c, let m): .failed(code: c, message: m)
        }
    }
}

extension Api {
    /// This result re-typed, when it isn't a success; nil for a success.
    func failure<U>(as: U.Type = U.self) -> Api<U>? {
        switch self {
        case .ok: nil
        case .unauthorized: .unauthorized
        case .notProvisioned: .notProvisioned
        case .retry(let m): .retry(m)
        case .serverError(let m): .serverError(m)
        case .failed(let c, let m): .failed(code: c, message: m)
        }
    }
}
