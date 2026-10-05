import SwiftUI

@main
struct TreasureApp: App {
    @State private var auth = AuthService()
    @State private var lock = AppLock()
    @Environment(\.scenePhase) private var phase

    init() {
        // Files from builds before storage was per-user have no known owner: remove them once.
        if !UserDefaults.standard.bool(forKey: "storagePerUser") { DiskCache.removeLegacyFiles(); UserDefaults.standard.set(true, forKey: "storagePerUser") }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(auth)
                .environment(lock)
                .tint(Tok.accent)
                // Cover the content while locked and in the app switcher, so balances never show in the snapshot.
                .overlay { if lock.locked || (lock.enabled && phase != .active) { LockCover { Task { await lock.unlock() } } } }
                .animation(.easeOut(duration: 0.15), value: lock.locked)
                .onChange(of: phase) { _, new in
                    if new == .background { lock.didLeaveForeground() }
                    if new == .active { Task { await lock.unlock() } }
                }
        }
    }
}

/// Shown by an overlay, which sits outside the environment injected above, so it takes its action as a closure
/// instead of reading the lock from the environment.
struct LockCover: View {
    let onUnlock: () -> Void
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "diamond.fill").font(.system(size: 44)).foregroundStyle(Tok.accent)
            Text("Treasure is locked").font(.headline)
            Button("Unlock", action: onUnlock).buttonStyle(.bordered).frame(minHeight: 44)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Tok.background.ignoresSafeArea())
        .accessibilityElement(children: .contain)
    }
}
