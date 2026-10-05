import SwiftUI

struct RootView: View {
    @Environment(AuthService.self) private var auth

    var body: some View {
        Group {
            switch auth.state {
            case .loading:
                // Clerk takes a moment on a cold start; show the mark rather than an empty screen.
                Image(systemName: "diamond.fill").font(.system(size: 44)).foregroundStyle(Tok.accent)
                    .frame(maxWidth: .infinity, maxHeight: .infinity).background(Tok.background.ignoresSafeArea())
            case .signedOut: WelcomeView()
            case .signedIn(let id, _): Tabs(userId: id).id(id)   // a different user is a different shell, never a reused one
            }
        }
        .animation(.easeInOut(duration: 0.25), value: auth.state)
    }
}

struct WelcomeView: View {
    @Environment(AuthService.self) private var auth
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer()
            Image(systemName: "diamond.fill").font(.system(size: 44)).foregroundStyle(Tok.accent)
            Text("Treasure").font(.largeTitle.bold()).padding(.top, 20)
            Text("Every spend, in one place you own.").font(.body).foregroundStyle(Tok.muted).padding(.top, 8)
            Spacer()
            if let error { Text(error).font(.callout).foregroundStyle(Tok.critical).padding(.bottom, 12) }
            Button {
                busy = true
                Task { error = await auth.signInWithGoogle(); busy = false }
            } label: {
                if busy { ProgressView().tint(Tok.onAccent) } else { Text("Continue with Google") }
            }
            .buttonStyle(PrimaryButtonStyle()).disabled(busy)
        }
        .padding(24)
        .background(Tok.background.ignoresSafeArea())
    }
}

/// Signed-in shell. Owns the per-user models so they are rebuilt (and caches dropped) on sign-out.
enum AppTab: Hashable { case home, spends, insights, more }

/// Which tab is showing, so one screen can send the user to another (an Insights row opens the matching Spends).
@Observable final class Router { var tab: AppTab = .home }

struct Tabs: View {
    let userId: String
    @Environment(AuthService.self) private var auth
    @State private var engine: Engine?
    @State private var directory: Directory?
    @State private var spends: SpendsModel?
    @State private var composer = Composer()
    @State private var importer = Importer()
    @State private var reach = Reachability()
    @State private var router = Router()
    @Environment(\.scenePhase) private var phase

    var body: some View {
        Group {
            if let engine, let directory, let spends {
                TabView(selection: $router.tab) {
                    HomeView().tabItem { Label("Home", systemImage: "house") }.tag(AppTab.home)
                    SpendsView().tabItem { Label("Spends", systemImage: "list.bullet") }.tag(AppTab.spends)
                    InsightsView().tabItem { Label("Insights", systemImage: "chart.bar.xaxis") }.tag(AppTab.insights)
                    MoreView().tabItem { Label("More", systemImage: "ellipsis") }.tag(AppTab.more)
                }
                .environment(router)
                .environment(engine).environment(directory).environment(spends).environment(composer).environment(importer)
                .modifier(ImportHost(importer: importer, engine: engine, directory: directory, spends: spends))
                .sheet(item: $composer.target) { SpendEditor(target: $0).environment(spends).environment(directory) }
                .task { await directory.refresh(); await spends.flushOutbox() }
                .onChange(of: reach.online) { _, online in if online { Task { await spends.flushOutbox() } } }
                .onChange(of: phase) { _, new in if new == .active { Task { await spends.flushOutbox() } } }
            } else {
                Tok.background.ignoresSafeArea()
            }
        }
        .task {
            let cache = DiskCache(owner: userId)
            let e = Engine(auth: auth, owner: userId)
            let d = Directory(engine: e, cache: cache)
            engine = e; directory = d; spends = SpendsModel(engine: e, cache: cache) { await d.resolveMerchant($0) }
        }
    }
}
