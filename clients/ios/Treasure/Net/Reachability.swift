import Network
import Observation

/// Whether the device has a usable network path. Used only as a nudge to resend queued spends; failures still decide.
@MainActor @Observable
final class Reachability {
    private(set) var online = true
    private let monitor = NWPathMonitor()

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let up = path.status == .satisfied
            Task { @MainActor in self?.online = up }
        }
        monitor.start(queue: DispatchQueue(label: "treasure.reachability"))
    }
    deinit { monitor.cancel() }
}
