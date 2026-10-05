import SwiftUI
import XCTest
@testable import Treasure

final class LogicTests: XCTestCase {
    private let us = Locale(identifier: "en_US")

    func testMoneyUsesCurrencyFractionDigits() {
        XCTAssertEqual(Money.format(123_450, currency: "USD", locale: us), "$1,234.50")
        XCTAssertEqual(Money.format(500, currency: "JPY", locale: us), "¥500")
        XCTAssertEqual(Money.format(9_007_199_254_740_991, currency: "USD", locale: us), "$90,071,992,547,409.91")
    }

    func testSignedDirection() {
        func spend(_ kind: String) -> Spend { Spend(id: "1", version: 1, occurredOn: "2026-10-04", kind: kind, amountMinor: 700, currency: "USD") }
        XCTAssertEqual(Money.signed(spend("expense")), -700)
        XCTAssertEqual(Money.signed(spend("refund")), 700)
        XCTAssertEqual(Money.signed(spend("transfer")), 0)
    }

    func testClassifyMapsEngineErrors() {
        let conflict = Data(#"{"error":{"code":"conflict","message":"stale"}}"#.utf8)
        guard case .failed(let code, _) = FinanceAPI.classify(409, conflict) as Api<Page<Spend>> else { return XCTFail() }
        XCTAssertEqual(code, "conflict")
        guard case .unauthorized = FinanceAPI.classify(401, Data()) as Api<Page<Spend>> else { return XCTFail() }
        guard case .retry = FinanceAPI.classify(503, Data()) as Api<Page<Spend>> else { return XCTFail() }
    }

    func testPageDecodesSpend() throws {
        let json = #"{"items":[{"id":"a","version":2,"occurred_on":"2026-10-04","kind":"expense","amount_minor":1250,"currency":"USD","deleted_at":null}],"total":1,"next_offset":null}"#
        let page = try JSONDecoder().decode(Page<Spend>.self, from: Data(json.utf8))
        XCTAssertEqual(page.items.first?.amountMinor, 1250)
        XCTAssertNil(page.nextOffset)
    }
}

final class GroupingTests: XCTestCase {
    private func spend(_ id: String, _ day: String, _ kind: String = "expense", _ minor: Int64 = 100, _ cur: String = "USD") -> Spend {
        Spend(id: id, version: 1, occurredOn: day, kind: kind, amountMinor: minor, currency: cur)
    }

    func testGroupsConsecutiveDaysAndNetsRefunds() {
        let g = DayGroups.make([spend("a", "2026-10-04", "expense", 500), spend("b", "2026-10-04", "refund", 200), spend("c", "2026-10-03")])
        XCTAssertEqual(g.map(\.day), ["2026-10-04", "2026-10-03"])
        XCTAssertEqual(g[0].net?.minor, -300)
    }

    func testMixedCurrencyDayHasNoNet() {
        let g = DayGroups.make([spend("a", "2026-10-04", "expense", 1, "USD"), spend("b", "2026-10-04", "expense", 1, "EUR")])
        XCTAssertNil(g[0].net)
    }

    func testTitleTodayAndYesterday() {
        var cal = Calendar(identifier: .gregorian); cal.timeZone = .gmt
        let now = cal.date(from: DateComponents(year: 2026, month: 10, day: 4, hour: 12))!
        XCTAssertEqual(DayGroups.title("2026-10-04", today: now, calendar: cal), "Today")
        XCTAssertEqual(DayGroups.title("2026-10-03", today: now, calendar: cal), "Yesterday")
    }
}

final class WriteTests: XCTestCase {
    func testAmountEntryIsRegisterStyle() {
        var e = AmountEntry()
        e.press("0"); XCTAssertEqual(e.minor, 0)          // no leading zeros
        e.press("1"); e.press("2"); e.press("50")
        XCTAssertEqual(e.minor, 1250)
        e.backspace(); XCTAssertEqual(e.minor, 125)
        for _ in 0..<20 { e.press("9") }
        XCTAssertLessThanOrEqual(e.digits.count, AmountEntry.maxDigits)
    }

    func testCalendarDateRoundTripsWithoutShift() {
        var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        let d = CalendarDate.date(from: "2026-12-31", calendar: cal)!
        XCTAssertEqual(CalendarDate.string(from: d, calendar: cal), "2026-12-31")
    }

    func testUpsertKeepsDayOrder() {
        func s(_ id: String, _ day: String) -> Spend { Spend(id: id, version: 1, occurredOn: day, kind: "expense", amountMinor: 1, currency: "USD") }
        let out = SpendOrder.upsert([s("a", "2026-10-04"), s("b", "2026-10-02")], s("c", "2026-10-03"))
        XCTAssertEqual(out.map(\.id), ["a", "c", "b"])
        let moved = SpendOrder.upsert(out, s("b", "2026-10-05"))
        XCTAssertEqual(moved.map(\.id), ["b", "a", "c"])
    }

    func testEditCarriesUnseenFieldsAndOmitsNil() throws {
        var spend = Spend(id: "x", version: 3, occurredOn: "2026-10-04", kind: "expense", amountMinor: 900, currency: "USD")
        spend.source = "import"; spend.sourceRecordId = "r1"; spend.accountRef = "chk"; spend.evidenceIds = ["e1"]
        let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(SpendInput(spend))) as! [String: Any]
        XCTAssertEqual(json["source_record_id"] as? String, "r1")
        XCTAssertEqual(json["account_ref"] as? String, "chk")
        XCTAssertEqual(json["evidence_ids"] as? [String], ["e1"])
        XCTAssertNil(json["merchant_id"])       // nil is omitted, never sent as null
        XCTAssertNil(json["id"])                // not an editable field
    }

    func testWriteInputsFlattenIdempotencyKey() throws {
        let body = LifecycleInput(idempotencyKey: "k", id: "x", expectedVersion: 2)
        let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(body)) as! [String: Any]
        XCTAssertEqual(json["idempotency_key"] as? String, "k")
        XCTAssertEqual(json["expected_version"] as? Int, 2)
    }
}

final class InsightsTests: XCTestCase {
    private func bucket(_ key: String, _ net: Int64, _ cur: String = "USD", label: String = "") -> Bucket {
        Bucket(currency: cur, key: key, label: label.isEmpty ? key : label, expenseMinor: String(net), refundMinor: "0", netMinor: String(net), count: 1)
    }
    private var greg: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = .gmt; return c }

    func testMonthWindowComparesSameElapsedStretch() {
        let today = greg.date(from: DateComponents(year: 2026, month: 10, day: 15))!
        let w = Period.month.window(today: today, calendar: greg)
        XCTAssertEqual(w, Period.Window(from: "2026-10-01", to: "2026-10-15", compareFrom: "2026-09-01", compareTo: "2026-09-15"))
    }

    func testMonthEndClampsInShorterMonth() {
        let today = greg.date(from: DateComponents(year: 2026, month: 3, day: 31))!
        XCTAssertEqual(Period.month.window(today: today, calendar: greg).compareTo, "2026-02-28")
    }

    func testYearAndQuarterWindows() {
        let today = greg.date(from: DateComponents(year: 2026, month: 10, day: 4))!
        XCTAssertEqual(Period.year.window(today: today, calendar: greg).from, "2026-01-01")
        let q = Period.quarter.window(today: today, calendar: greg)
        XCTAssertEqual(q.from, "2026-08-01"); XCTAssertEqual(q.compareFrom, "2026-05-01")
    }

    func testSeriesFillsEmptyDaysWithZero() {
        let s = Trend.series([bucket("2026-10-02", 500)], currency: "USD", group: "day", from: "2026-10-01", to: "2026-10-03")
        XCTAssertEqual(s.map(\.minor), [0, 500, 0])
    }

    func testWeekBucketsStartOnMonday() {
        // 2026-10-04 is a Sunday; its Monday-based week starts 2026-09-28.
        XCTAssertEqual(Trend.keys(group: "week", from: "2026-10-04", to: "2026-10-12"), ["2026-09-28", "2026-10-05", "2026-10-12"])
        XCTAssertEqual(Trend.keys(group: "month", from: "2026-08-15", to: "2026-10-01"), ["2026-08-01", "2026-09-01", "2026-10-01"])
    }

    func testBreakdownFoldsTailIntoOtherAndKeepsCurrenciesApart() {
        let b = (1...7).map { bucket("c\($0)", Int64($0) * 100) } + [bucket("eur", 9_999, "EUR")]
        let rows = Breakdown.top(b, currency: "USD", limit: 5)
        XCTAssertEqual(rows.map(\.key), ["c7", "c6", "c5", "c4", "c3", "other"])
        XCTAssertEqual(rows.last?.minor, 300)
        XCTAssertEqual(rows.map(\.minor).reduce(0, +), 2800)
    }

    func testDeltaWording() {
        XCTAssertEqual(Delta.text(current: 112, previous: 100, against: "last month"), "12% more than last month")
        XCTAssertEqual(Delta.text(current: 50, previous: 100, against: "last week"), "50% less than last week")
        XCTAssertNil(Delta.text(current: 50, previous: 0, against: "last week"))
        XCTAssertNil(Delta.text(current: 50, previous: nil, against: "last week"))
    }

    func testAnalysisDecodesStringAggregatesAndMissingComparison() throws {
        let json = #"{"group_by":"day","groups_overlap":false,"current":[{"currency":"USD","key":"2026-10-01","label":"2026-10-01","expense_minor":"9007199254740991","refund_minor":"0","net_minor":"9007199254740991","count":1}]}"#
        let a = try JSONDecoder().decode(Analysis.self, from: Data(json.utf8))
        XCTAssertEqual(a.current.first?.net, 9_007_199_254_740_991)
        XCTAssertTrue(a.comparison.isEmpty)
    }
}

final class OrganizeTests: XCTestCase {
    func testCSVJoinKeepsOneHeader() {
        XCTAssertEqual(CSVJoin.join(["id,x\n1,a\n", "id,x\n2,b\n"]), "id,x\n1,a\n2,b\n")
        XCTAssertEqual(CSVJoin.join(["id,x\n1,a\n"]), "id,x\n1,a\n")
        XCTAssertEqual(CSVJoin.join([]), "")
    }

    func testMerchantRenameResendsAliasesAndVersion() throws {
        let body = UpdateDimensionInput(idempotencyKey: "k", id: "m", expectedVersion: 4, name: "Cafe", aliases: ["Café"])
        let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(body)) as! [String: Any]
        XCTAssertEqual(json["aliases"] as? [String], ["Café"])
        XCTAssertEqual(json["expected_version"] as? Int, 4)
    }

    func testOperationPrefixes() {
        XCTAssertEqual(DimensionKind.category.plural, "categories")
        XCTAssertEqual(DimensionKind.tag.plural, "tags")
        XCTAssertEqual(DimensionKind.merchant.plural, "merchants")
    }
}

@MainActor
final class OutboxTests: XCTestCase {
    private func item(_ id: String) -> OutboxItem {
        OutboxItem(id: id, spend: SpendInput(occurredOn: "2026-10-04", kind: "expense", amountMinor: 100, currency: "USD", source: "ios", sourceRecordId: id), createdAt: .now)
    }
    private func spend(_ id: String) -> Spend { Spend(id: "srv-\(id)", version: 1, occurredOn: "2026-10-04", kind: "expense", amountMinor: 100, currency: "USD") }
    private func outbox(_ name: String, _ ids: [String]) -> Outbox {
        let cache = DiskCache(owner: "test-\(name)-\(UUID().uuidString)")
        cache.destroy()
        let o = Outbox(cache: cache)
        ids.forEach { o.add(item($0)) }
        addTeardownBlock { cache.destroy() }
        return o
    }

    func testFlushSendsOldestFirstAndEmptiesQueue() async {
        let o = outbox("order", ["a", "b"])
        var sent: [String] = []
        let created = await o.flush { sent.append($0.id); return .ok(self.spend($0.id)) }
        XCTAssertEqual(sent, ["a", "b"])
        XCTAssertEqual(created.map(\.id), ["srv-a", "srv-b"])
        XCTAssertTrue(o.items.isEmpty)
    }

    func testOfflineStopsAtFirstItemAndKeepsEverything() async {
        let o = outbox("offline", ["a", "b"])
        var calls = 0
        let created = await o.flush { _ in calls += 1; return .retry("offline") }
        XCTAssertEqual(calls, 1)
        XCTAssertTrue(created.isEmpty)
        XCTAssertEqual(o.items.map(\.id), ["a", "b"])
    }

    func testRefusedItemIsMarkedAndTheRestStillSend() async {
        let o = outbox("refused", ["a", "b"])
        let created = await o.flush { $0.id == "a" ? .failed(code: "invalid_input", message: "bad amount") : .ok(self.spend($0.id)) }
        XCTAssertEqual(created.map(\.id), ["srv-b"])
        XCTAssertEqual(o.items.map(\.id), ["a"])
        XCTAssertEqual(o.items.first?.failure, "bad amount")
        // A failed item is not retried automatically.
        var calls = 0
        _ = await o.flush { _ in calls += 1; return .retry("x") }
        XCTAssertEqual(calls, 0)
    }

    func testQueueSurvivesRelaunch() {
        let cache = DiskCache(owner: "test-persist-\(UUID().uuidString)")
        addTeardownBlock { cache.destroy() }
        Outbox(cache: cache).add(item("a"))
        XCTAssertEqual(Outbox(cache: cache).items.map(\.id), ["a"])
    }

    func testPendingRowShowsTypedMerchantName() {
        var i = item("a"); i.merchantName = "Blue Bottle"
        XCTAssertEqual(Outbox.asSpend(i).description, "Blue Bottle")
    }
}

/// The lock cover is shown by an overlay, which sits outside the environment the app injects, so it must not read one.
final class LockCoverTests: XCTestCase {
    @MainActor func testLockCoverRendersWithNoAmbientEnvironment() {
        // Drawn in a real window: SwiftUI only evaluates a view's body once it is on screen.
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = UIHostingController(rootView: LockCover(onUnlock: {}))
        window.makeKeyAndVisible()
        RunLoop.main.run(until: Date().addingTimeInterval(0.5))
        XCTAssertNotNil(window.rootViewController?.view)
    }
}
