import Foundation

enum Config {
    /// The router's public address; every finance-engine call lives under /finance/ on it.
    static let routerBaseURL: String = info("RouterBaseURL")
    static let clerkPublishableKey: String = info("ClerkPublishableKey")

    /// DEBUG builds only: `-dev-engine http://127.0.0.1:18091` points the app straight at a local finance-engine run with
    /// DEV_VERIFIED_USER, skipping Clerk and the router. Release builds ignore the flag entirely.
    static var devEngineURL: String? {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-dev-engine"), i + 1 < args.count { return args[i + 1] }
        #endif
        return nil
    }

    private static func info(_ key: String) -> String {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String) ?? ""
    }
}
