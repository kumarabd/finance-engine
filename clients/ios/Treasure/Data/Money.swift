import Foundation

enum Money {
    /// "1,234.50" style text for integer minor units, using the currency's own fraction digits (JPY has none).
    static func format(_ minor: Int64, currency: String, locale: Locale = .current) -> String {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencyCode = currency
        f.locale = locale
        let digits = f.maximumFractionDigits
        f.minimumFractionDigits = digits
        let value = Decimal(minor) / pow(10, digits)
        return f.string(from: value as NSDecimalNumber) ?? "\(minor) \(currency)"
    }

    /// Text typed into an amount field ("12.50", "1,200", "5") as minor units of `currency`; nil when it isn't a number.
    static func parseMinor(_ text: String, currency: String) -> Int64? {
        AmountParse.parseCell(text, digits: AmountParse.fractionDigits(currency)).map(\.minor)
    }

    /// Signed minor units: refunds flow back in, expenses out; transfers are neither.
    static func signed(_ s: Spend) -> Int64 {
        switch s.kind {
        case "refund": s.amountMinor
        case "expense": -s.amountMinor
        default: 0
        }
    }
}
