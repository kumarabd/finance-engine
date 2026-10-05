import XCTest
@testable import Treasure

/// The app's models against real responses captured from a running finance-engine (clients/fixtures), so a change on
/// either side that would break decoding fails here instead of on a phone.
final class ContractTests: XCTestCase {
    private func data(_ name: String) throws -> Data {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: "json"), "missing fixture \(name).json")
        return try Data(contentsOf: url)
    }
    private func decode<T: Decodable>(_ type: T.Type, _ name: String) throws -> T { try JSONDecoder().decode(type, from: data(name)) }

    func testSpend() throws {
        let s = try decode(Spend.self, "spend")
        XCTAssertEqual(s.version, 1); XCTAssertEqual(s.amountMinor, 550); XCTAssertEqual(s.occurredOn, "2026-10-04")
        XCTAssertEqual(s.source, "android"); XCTAssertEqual(s.allocations?.first?.amountMinor, 550)
        XCTAssertNil(s.deletedAt)
        // An edit must resend what it doesn't show, so the round trip keeps the source identity.
        XCTAssertEqual(SpendInput(s).sourceRecordId, "fx-1")
    }

    func testSpendsPage() throws {
        let p = try decode(Page<Spend>.self, "spends_page")
        XCTAssertEqual(p.items.count, 3); XCTAssertNil(p.nextOffset)
        XCTAssertTrue(p.items.contains { $0.kind == "refund" })
    }

    func testAnalysisByDayHasExactStringAggregatesAndNegativeNet() throws {
        let a = try decode(Analysis.self, "analysis_by_day")
        XCTAssertTrue(a.comparison.isEmpty)
        let refundDay = try XCTUnwrap(a.current.first { $0.key == "2026-10-03" })
        XCTAssertEqual(refundDay.net, -200)            // a refund-only day is negative; charts clamp it to zero
        XCTAssertEqual(a.current.first { $0.key == "2026-10-02" }?.expense, 1234)
        // The same data through the trend and breakdown code the screens use.
        let series = Trend.series(a.current, currency: "USD", group: "day", from: "2026-10-01", to: "2026-10-05")
        XCTAssertEqual(series.map(\.minor), [0, 1234, -200, 550, 0])
    }

    func testAnalysisByCategory() throws {
        let a = try decode(Analysis.self, "analysis_by_category")
        XCTAssertTrue(Set(a.current.map(\.label)).isSuperset(of: ["Coffee", "Uncategorized"]))
        XCTAssertFalse(Breakdown.top(a.current, currency: "USD").isEmpty)
    }

    func testHistoryDimensionsBulkEvidenceExport() throws {
        let h = try decode(Page<Change>.self, "history")
        XCTAssertEqual(h.items.first?.operation, "spends_create"); XCTAssertFalse(h.items[0].actor.isEmpty)
        let d = try decode(Page<Treasure.Dimension>.self, "dimensions_page")
        XCTAssertEqual(d.items.first?.aliases, ["BLUEBOTTLE"]); XCTAssertEqual(d.items.first?.version, 1)
        XCTAssertEqual(try decode(SpendsResult.self, "bulk_result").items.count, 1)
        XCTAssertFalse(try decode(EvidenceRecord.self, "evidence").id.isEmpty)
        let e = try decode(ExportResult.self, "export")
        XCTAssertTrue(e.csv.hasPrefix("id,version,occurred_on")); XCTAssertNil(e.nextOffset)
    }

    func testEngineErrorsAreClassified() throws {
        guard case .failed(let c1, let m1) = FinanceAPI.classify(409, try data("error_conflict")) as Api<Spend> else { return XCTFail() }
        XCTAssertEqual(c1, "conflict"); XCTAssertTrue(m1.contains("Stale version"))
        guard case .failed(let c2, _) = FinanceAPI.classify(400, try data("error_invalid")) as Api<Spend> else { return XCTFail() }
        XCTAssertEqual(c2, "invalid_input")
    }

    func testDuplicateAnswersFromTheRealEngine() throws {
        // A source identity imported before, and a merchant name already taken: both are "duplicate", not "conflict".
        for name in ["error_duplicate", "error_name_in_use"] {
            guard case .failed(let code, _) = FinanceAPI.classify(409, try data(name)) as Api<Spend> else { return XCTFail(name) }
            XCTAssertEqual(code, "duplicate", name)
            XCTAssertTrue(ImportSession.alreadyThere(code))
        }
    }
}
