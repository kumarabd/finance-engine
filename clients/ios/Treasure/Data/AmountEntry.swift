import Foundation

/// Cash-register entry: digits are typed as minor units, so 1,2,5,0 is 12.50 (or 1,250 in a currency with no cents).
struct AmountEntry: Equatable {
    private(set) var digits = ""
    static let maxDigits = 13   // well under the engine's 9007199254740991 ceiling

    init(minor: Int64 = 0) { digits = minor > 0 ? String(minor) : "" }

    var minor: Int64 { Int64(digits) ?? 0 }

    mutating func press(_ d: String) {
        guard d.allSatisfy(\.isNumber), digits.count + d.count <= Self.maxDigits else { return }
        if digits.isEmpty && d.allSatisfy({ $0 == "0" }) { return }   // no leading zeros
        digits += d
    }
    mutating func backspace() { _ = digits.popLast() }
}

/// Calendar dates cross the API as yyyy-MM-dd with no timezone; these convert at the edge only.
enum CalendarDate {
    private static func formatter(_ calendar: Calendar) -> DateFormatter {
        let f = DateFormatter()
        f.calendar = calendar
        f.timeZone = calendar.timeZone   // a calendar date must be read and written in the same zone
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }
    static func string(from date: Date, calendar: Calendar = .current) -> String { formatter(calendar).string(from: date) }
    static func date(from string: String, calendar: Calendar = .current) -> Date? { formatter(calendar).date(from: string) }
}

enum SpendOrder {
    /// Puts `spend` in place of any older copy, newest day first, and above same-day rows (the server sorts date, then id).
    static func upsert(_ list: [Spend], _ spend: Spend) -> [Spend] {
        var out = list.filter { $0.id != spend.id }
        let at = out.firstIndex { $0.occurredOn <= spend.occurredOn } ?? out.count
        out.insert(spend, at: at)
        return out
    }
}
