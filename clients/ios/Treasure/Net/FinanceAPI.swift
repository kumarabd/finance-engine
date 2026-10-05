import Foundation

enum Api<T> {
    case ok(T)
    case unauthorized
    /// The router has no instance for this user yet.
    case notProvisioned
    /// Couldn't reach the engine, or it is busy or restarting (offline, 408, 429, 502-504). The same request is safe to resend.
    case retry(String)
    /// The engine answered 500: probably transient, but possibly a bug that will repeat. Resend a few times, then give up.
    case serverError(String)
    /// The engine understood and said no; `code` is its machine-readable reason (invalid_input, conflict, ...).
    case failed(code: String?, message: String)
}

/// Every engine operation is `POST <router>/finance/api/v1/operations/<name>` with a JSON object body.
struct FinanceAPI {
    let baseURL: String
    var session: URLSession = .shared

    static var live: FinanceAPI { FinanceAPI(baseURL: (Config.devEngineURL ?? (Config.routerBaseURL + "/finance")) + "/api/v1") }

    /// Write operations must carry an `idempotency_key`; pass the same one when retrying the same write.
    func call<In: Encodable, Out: Decodable>(_ op: String, _ input: In, token: String, timeout: TimeInterval = 30) async -> Api<Out> {
        guard let url = URL(string: baseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/operations/" + op),
              let body = try? JSONEncoder().encode(input) else { return .failed(code: nil, message: "Bad request") }
        var req = URLRequest(url: url, timeoutInterval: timeout)
        req.httpMethod = "POST"
        req.httpBody = body
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        guard let (data, resp) = try? await session.data(for: req), let http = resp as? HTTPURLResponse else {
            return .retry("network error")
        }
        return Self.classify(http.statusCode, data)
    }

    static func classify<Out: Decodable>(_ code: Int, _ data: Data) -> Api<Out> {
        switch code {
        case 200...299:
            if let v = try? JSONDecoder().decode(Out.self, from: data) { return .ok(v) }
            return .retry("unreadable response")
        case 401, 403: return .unauthorized
        case 404 where String(decoding: data, as: UTF8.self).contains("no_tenant"): return .notProvisioned
        case 408, 429, 502, 503, 504: return .retry("server returned \(code)")
        case 500...: return .serverError("server returned \(code)")
        default:
            let e = errorOf(data)
            return .failed(code: e.code, message: e.message ?? "The server returned \(code).")
        }
    }

    // Engine errors are {"error":{"code","message"}}; the router's are {"error":"..."}.
    static func errorOf(_ data: Data) -> (code: String?, message: String?) {
        guard let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return (nil, nil) }
        if let e = j["error"] as? [String: Any] { return (e["code"] as? String, e["message"] as? String) }
        return (nil, j["error"] as? String)
    }
}
