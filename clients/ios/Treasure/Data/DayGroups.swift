import Foundation

struct DayGroup: Identifiable, Equatable {
    var day: String          // yyyy-MM-dd
    var spends: [Spend]
    var id: String { day }

    /// Net signed total for the day, or nil when the day mixes currencies (they are never combined).
    var net: (minor: Int64, currency: String)? {
        guard let c = spends.first?.currency, spends.allSatisfy({ $0.currency == c }) else { return nil }
        return (spends.reduce(0) { $0 + Money.signed($1) }, c)
    }
}

enum DayGroups {
    /// Groups an already date-sorted list into consecutive days, keeping the server's order.
    static func make(_ spends: [Spend]) -> [DayGroup] {
        var out: [DayGroup] = []
        for s in spends {
            if out.last?.day == s.occurredOn { out[out.count - 1].spends.append(s) }
            else { out.append(DayGroup(day: s.occurredOn, spends: [s])) }
        }
        return out
    }

    /// "Today", "Yesterday", or "Mon, Oct 4" for a calendar date string. No timezone maths: the date is parsed in the
    /// current calendar as written.
    static func title(_ day: String, today: Date = .now, calendar: Calendar = .current) -> String {
        let parse = DateFormatter()
        parse.calendar = calendar
        parse.dateFormat = "yyyy-MM-dd"
        guard let d = parse.date(from: day) else { return day }
        if calendar.isDate(d, inSameDayAs: today) { return "Today" }
        if let y = calendar.date(byAdding: .day, value: -1, to: today), calendar.isDate(d, inSameDayAs: y) { return "Yesterday" }
        return d.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
    }
}
