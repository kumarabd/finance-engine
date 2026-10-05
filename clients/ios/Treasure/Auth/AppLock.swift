import Foundation
import LocalAuthentication
import Observation

/// Optional Face ID / Touch ID / passcode gate. Locks whenever the app leaves the foreground.
@MainActor @Observable
final class AppLock {
    private static let key = "appLock"
    private(set) var enabled = UserDefaults.standard.bool(forKey: key)
    private(set) var locked: Bool

    init() { locked = UserDefaults.standard.bool(forKey: Self.key) }

    func didLeaveForeground() { if enabled { locked = true } }

    func unlock() async {
        guard locked else { return }
        if await authenticate("Unlock Treasure") { locked = false }
    }

    /// Turning it on is confirmed with the device's own prompt first, so you can't lock yourself out by mistake.
    /// Returns an explanation when it couldn't be enabled.
    func setEnabled(_ on: Bool) async -> String? {
        if on {
            guard LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else {
                return "Set a passcode on this device first."
            }
            guard await authenticate("Turn on Treasure lock") else { return nil }
        }
        enabled = on
        UserDefaults.standard.set(on, forKey: Self.key)
        return nil
    }

    private func authenticate(_ reason: String) async -> Bool {
        (try? await LAContext().evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) ?? false
    }
}
