import ClerkKit
import Foundation
import Observation

enum AuthState: Equatable {
    case loading
    case signedOut
    case signedIn(userId: String, email: String?)
}

/// Identity for the whole app. Signing in is Clerk, the same identity the router verifies. There is no guest mode:
/// the records live on the server.
@MainActor @Observable
final class AuthService {
    /// Clerk couldn't finish starting (offline first launch): show the sign-in screen rather than a blank one.
    private var loadTimedOut = false

    init() {
        if Config.devEngineURL != nil { return }   // developer mode: no Clerk, a fixed local user
        Clerk.configure(publishableKey: Config.clerkPublishableKey)
        Task {
            try? await Task.sleep(for: .seconds(8))
            loadTimedOut = true
        }
    }

    var state: AuthState {
        if Config.devEngineURL != nil { return .signedIn(userId: "dev_user", email: "dev@local") }
        if let user = Clerk.shared.user {
            return .signedIn(userId: user.id, email: user.primaryEmailAddress?.emailAddress)
        }
        return Clerk.shared.isLoaded || loadTimedOut ? .signedOut : .loading
    }

    var userId: String? { if case .signedIn(let id, _) = state { id } else { nil } }

    /// Opens Google sign-in (Clerk OAuth). Returns an error message to show, or nil on success.
    func signInWithGoogle() async -> String? {
        do {
            try await Clerk.shared.auth.signInWithOAuth(provider: .google)
            return nil
        } catch {
            if (error as? CancellationError) != nil { return nil }
            return "Couldn't sign in with Google. Check your connection and try again."
        }
    }

    /// Signing out drops this user's cached lists from the phone. Spends they saved offline and haven't sent yet stay, under
    /// their own folder, and go out only when they sign in again.
    func signOut() async {
        if let id = userId { DiskCache(owner: id).purgeCaches() }
        try? await Clerk.shared.auth.signOut()
    }

    /// A session token for the router, or nil when not signed in or offline. [skipCache] forces a fresh one.
    func token(skipCache: Bool = false) async -> String? {
        if Config.devEngineURL != nil { return "dev" }   // the local engine ignores it
        guard Clerk.shared.session != nil else { return nil }
        return try? await Clerk.shared.auth.getToken(.init(skipCache: skipCache))
    }
}
