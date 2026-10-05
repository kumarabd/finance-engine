import SwiftUI

// "Ledger at night": the AutoTelemetry neutrals with a brass accent. Expenses are plain text (spending is not an
// error); `live` marks money coming back in; `critical` is only for errors and destructive actions.
extension Color {
    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}

enum Tok {
    static let background = Color(light: 0xF6F7F9, dark: 0x0B0D10)
    static let surface = Color(light: 0xFFFFFF, dark: 0x14181D)
    static let raised = Color(light: 0xEEF0F3, dark: 0x1B2027)
    static let hairline = Color(light: 0xE1E5EA, dark: 0x262C34)
    static let text = Color(light: 0x0F1318, dark: 0xE8ECF1)
    static let muted = Color(light: 0x5B6674, dark: 0x8A94A1)
    // Measured: brass is 5.2-5.9:1 on light surfaces and 8.4-10:1 on dark ones.
    static let accent = Color(light: 0x8A5A00, dark: 0xE3B25C)
    static let onAccent = Color(light: 0xFFFFFF, dark: 0x1A1203)
    static let live = Color(light: 0x0A7A4C, dark: 0x3DDC97)   // light value measured >= 4.7:1 on every light surface
    static let warn = Color(light: 0x9A6200, dark: 0xFFB020)
    static let critical = Color(light: 0xC42B2B, dark: 0xFF5C5C)
}

struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(Tok.onAccent)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(Tok.accent.opacity(enabled ? 1 : 0.35), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

extension View {
    /// Every money value uses tabular figures so columns align and rolling digits don't jitter.
    func amountStyle() -> some View { monospacedDigit() }
}

extension View {
    func field() -> some View {
        padding(.horizontal, 14).frame(minHeight: 52)
            .background(Tok.raised, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

extension Tok {
    /// Categorical colors for breakdowns, in rank order; "Other" is always muted. Never the only carrier of meaning:
    /// every row is also labeled with its name and amount.
    // Assigned by rank on screen, not stable per category (the engine stores no category color). Measured >= 5:1 on surface.
    static let series: [Color] = [
        Color(light: 0x8A5A00, dark: 0xE3B25C), Color(light: 0x13796E, dark: 0x4FB6A8), Color(light: 0x2F5FC4, dark: 0x6C9BE8),
        Color(light: 0x6D4FC2, dark: 0x9B8AE0), Color(light: 0xB5472F, dark: 0xE0806B), Color(light: 0x4D7A25, dark: 0x8DBF6F),
    ]
}

/// Large money figures scale with Dynamic Type and shrink rather than truncate.
struct HeroAmount: ViewModifier {
    @ScaledMetric(relativeTo: .largeTitle) private var size: CGFloat = 44
    func body(content: Content) -> some View {
        content.font(.system(size: size, weight: .medium)).monospacedDigit().minimumScaleFactor(0.5).lineLimit(1)
    }
}
extension View { func heroAmount() -> some View { modifier(HeroAmount()) } }

/// Shown when a screen has nothing to display because a request failed.
struct ProblemView: View {
    let message: String
    let retry: () async -> Void
    var body: some View {
        ContentUnavailableView {
            Label("Can't load", systemImage: "wifi.slash")
        } description: {
            Text(message)
        } actions: {
            Button("Try again") { Task { await retry() } }.buttonStyle(.bordered).frame(minHeight: 44)
        }
    }
}
