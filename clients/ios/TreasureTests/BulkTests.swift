import XCTest
@testable import Treasure

/// A fake engine whose answers are computed from the request body, and which remembers every call in order.
final class ScriptedProtocol: URLProtocol {
    nonisolated(unsafe) static var handlers: [String: (Data) -> (Int, String)] = [:]
    nonisolated(unsafe) static var calls: [(op: String, body: Data)] = []
    static func reset(_ h: [String: (Data) -> (Int, String)] = [:]) { handlers = h; calls = [] }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let op = request.url!.lastPathComponent
        var body = request.httpBody ?? Data()
        if body.isEmpty, let stream = request.httpBodyStream {   // URLSession hands bodies over as a stream
            stream.open(); defer { stream.close() }
            var buf = [UInt8](repeating: 0, count: 65_536)
            while stream.hasBytesAvailable { let n = stream.read(&buf, maxLength: buf.count); if n <= 0 { break }; body.append(buf, count: n) }
        }
        Self.calls.append((op, body))
        let (status, text) = Self.handlers[op]?(body) ?? (200, "{}")
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(text.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor func scriptedEngine(owner: String = "u") -> Engine {
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [ScriptedProtocol.self]
    return Engine(owner: owner, currentUser: { owner }, token: { _ in "t" }, api: FinanceAPI(baseURL: "https://router.test/finance/api/v1", session: URLSession(configuration: config)))
}

final class FilterAndPatchWireTests: XCTestCase {
    private func json<T: Encodable>(_ v: T) throws -> [String: Any] { try JSONSerialization.jsonObject(with: JSONEncoder().encode(v)) as! [String: Any] }

    func testTheDefaultFilterSendsOnlyPaging() throws {
        let j = try json(SpendFilter().input(limit: 50, offset: 0))
        XCTAssertEqual(Set(j.keys), ["limit", "offset"])
    }

    func testEveryFilterFieldReachesTheEngineWithItsWireName() throws {
        var f = SpendFilter()
        f.search = "  latte "; f.from = "2026-10-01"; f.to = "2026-10-31"; f.kind = "expense"; f.currency = "USD"
        f.merchantId = "m1"; f.categoryId = "c1"; f.tagIds = ["t1", "t2"]; f.accountRef = "chk"; f.minAmountMinor = 100; f.maxAmountMinor = 9_900
        f.evidenceId = "e1"; f.state = "deleted"; f.sort = "amount_desc"
        let j = try json(f.input(limit: 20, offset: 40))
        XCTAssertEqual(j["search"] as? String, "latte")
        XCTAssertEqual(j["merchant_id"] as? String, "m1"); XCTAssertEqual(j["category_id"] as? String, "c1")
        XCTAssertEqual(j["tag_ids"] as? [String], ["t1", "t2"]); XCTAssertEqual(j["account_ref"] as? String, "chk")
        XCTAssertEqual(j["min_amount_minor"] as? Int, 100); XCTAssertEqual(j["max_amount_minor"] as? Int, 9_900)
        XCTAssertEqual(j["evidence_id"] as? String, "e1"); XCTAssertEqual(j["state"] as? String, "deleted"); XCTAssertEqual(j["sort"] as? String, "amount_desc")
        XCTAssertEqual(j["offset"] as? Int, 40)
    }

    func testUncategorizedReplacesTheCategory() throws {
        var f = SpendFilter(); f.categoryId = "c1"; f.uncategorized = true
        let j = try json(f.input())
        XCTAssertEqual(j["uncategorized"] as? Bool, true); XCTAssertNil(j["category_id"])
    }

    func testChipCountIgnoresStateAndSort() {
        var f = SpendFilter(); f.state = "deleted"; f.sort = "date_asc"
        XCTAssertEqual(f.activeCount, 0); XCTAssertFalse(f.isDefault)
        f.from = "2026-10-01"; f.to = "2026-10-31"; f.tagIds = ["t"]
        XCTAssertEqual(f.activeCount, 2, "a date range is one filter")
    }

    func testAmountSortNeedsACurrency() {
        var f = SpendFilter(); f.sort = "amount_desc"
        XCTAssertTrue(f.sortNeedsCurrency); f.currency = "USD"; XCTAssertFalse(f.sortNeedsCurrency)
    }

    func testPatchSendsOnlyWhatIsSetAndKeepsEmptyStringsThatMeanClear() throws {
        var p = SpendPatch(); p.categoryId = ""; p.addTagIds = ["t1"]
        let j = try json(p)
        XCTAssertEqual(j["category_id"] as? String, "", "an empty category means uncategorized")
        XCTAssertEqual(j["add_tag_ids"] as? [String], ["t1"])
        XCTAssertNil(j["merchant_id"]); XCTAssertNil(j["tag_ids"]); XCTAssertNil(j["remove_tag_ids"])
        var clear = SpendPatch(); clear.tagIds = []
        XCTAssertEqual(try json(clear)["tag_ids"] as? [String], [], "replacing tags with none clears them")
    }

    func testBulkInputsCarryEachRecordsExpectedVersion() throws {
        let j = try json(BulkUpdateInput(idempotencyKey: "k", records: [Versioned(id: "a", expectedVersion: 3)], patch: SpendPatch(categoryId: "c")))
        XCTAssertEqual(j["idempotency_key"] as? String, "k")
        XCTAssertEqual(((j["records"] as? [[String: Any]])?.first?["expected_version"]) as? Int, 3)
    }
}

@MainActor
final class BulkOperationTests: XCTestCase {
    private func spend(_ i: Int, version: Int64 = 1) -> Spend { Spend(id: "s\(i)", version: version, occurredOn: "2026-10-04", kind: "expense", amountMinor: 100, currency: "USD") }
    private func model() -> (SpendsModel, DiskCache) {
        let cache = DiskCache(owner: "bulk-\(UUID().uuidString)")
        addTeardownBlock { cache.destroy() }
        return (SpendsModel(engine: scriptedEngine(), cache: cache) { _ in .ok(nil) }, cache)
    }
    /// Answers a bulk call by returning every requested record, one version higher, optionally marked deleted.
    private func echo(deleted: Bool = false) -> (Data) -> (Int, String) {
        { body in
            let recs = ((try? JSONSerialization.jsonObject(with: body)) as? [String: Any])?["records"] as? [[String: Any]] ?? []
            let items = recs.map { r in
                #"{"id":"\#(r["id"] as! String)","version":\#((r["expected_version"] as! Int) + 1),"occurred_on":"2026-10-04","kind":"expense","amount_minor":100,"currency":"USD","deleted_at":\#(deleted ? "\"2026-10-05T00:00:00Z\"" : "null")}"#
            }
            return (200, #"{"items":[\#(items.joined(separator: ","))]}"#)
        }
    }

    func testTwoHundredFiftySpendsGoAsThreeBatchesOfAtMostAHundred() async {
        ScriptedProtocol.reset(["spends_bulk_update": echo()])
        let (m, _) = model()
        let outcome = await m.bulkUpdate((1...250).map { spend($0) }, patch: SpendPatch(categoryId: "c1"))
        XCTAssertTrue(outcome.ok); XCTAssertEqual(outcome.done.count, 250)
        let sizes = ScriptedProtocol.calls.map { (try! JSONSerialization.jsonObject(with: $0.body) as! [String: Any])["records"] as! [Any] }.map(\.count)
        XCTAssertEqual(sizes, [100, 100, 50])
        // Each batch is its own retry-safe request.
        let keys = Set(ScriptedProtocol.calls.map { (try! JSONSerialization.jsonObject(with: $0.body) as! [String: Any])["idempotency_key"] as! String })
        XCTAssertEqual(keys.count, 3)
    }

    func testTheSelectedSpendsVersionsAreSentAndTheListTakesTheNewCopies() async {
        ScriptedProtocol.reset(["spends_bulk_update": echo()])
        let (m, _) = model()
        let outcome = await m.bulkUpdate([spend(1, version: 4)], patch: SpendPatch(categoryId: "c1"))
        XCTAssertEqual(outcome.done.first?.version, 5)
        XCTAssertEqual(m.spends.first { $0.id == "s1" }?.version, 5)
    }

    func testAStaleVersionExplainsItselfAndChangesNothing() async {
        ScriptedProtocol.reset(["spends_bulk_update": { _ in (409, #"{"error":{"code":"conflict","message":"Stale version: expected 1, current 2."}}"#) }])
        let (m, _) = model()
        let outcome = await m.bulkUpdate([spend(1)], patch: SpendPatch(categoryId: "c1"))
        XCTAssertFalse(outcome.ok); XCTAssertTrue(outcome.done.isEmpty)
        XCTAssertTrue(outcome.failure?.contains("changed elsewhere") == true, outcome.failure ?? "")
    }

    func testAFailureAfterTheFirstBatchSaysWhatWasAlreadyDone() async {
        var calls = 0
        ScriptedProtocol.reset(["spends_bulk_update": { body in calls += 1; return calls == 1 ? self.echo()(body) : (500, #"{"error":{"code":"internal","message":"x"}}"#) }])
        let (m, _) = model()
        let outcome = await m.bulkUpdate((1...150).map { spend($0) }, patch: SpendPatch(categoryId: "c1"))
        XCTAssertEqual(outcome.done.count, 100)
        XCTAssertTrue(outcome.failure?.hasPrefix("Changed 100 of 150") == true, outcome.failure ?? "")
    }

    func testBulkDeleteRemovesRowsAndOffersAnUndoThatRestoresThemAll() async {
        ScriptedProtocol.reset(["spends_bulk_delete": echo(deleted: true), "spends_bulk_restore": echo()])
        let (m, _) = model()
        m.spends = (1...3).map { spend($0) }   // the list the user is looking at
        let outcome = await m.bulkDelete(Array(m.spends.prefix(2)))
        XCTAssertTrue(outcome.ok)
        XCTAssertEqual(m.spends.map(\.id), ["s3"])
        XCTAssertEqual(m.undo?.spends.count, 2)

        await m.undoDelete()
        XCTAssertEqual(Set(m.spends.map(\.id)), ["s1", "s2", "s3"])
        XCTAssertNil(m.undo)
        // Restoring uses the versions the delete returned, not the originals.
        let restore = ScriptedProtocol.calls.last!
        let versions = ((try! JSONSerialization.jsonObject(with: restore.body) as! [String: Any])["records"] as! [[String: Any]]).map { $0["expected_version"] as! Int }
        XCTAssertEqual(versions, [2, 2])
    }

    func testRestoringFromTrashTakesRowsOutOfTheTrashList() async {
        ScriptedProtocol.reset(["spends_bulk_restore": echo()])
        let (m, _) = model()
        m.filter.state = "deleted"
        m.spends = [spend(1), spend(2)]
        _ = await m.bulkRestore([m.spends[0]])
        XCTAssertEqual(m.spends.map(\.id), ["s2"])
    }

    func testFilteredListsAreNeverWrittenOverTheOfflineCache() async {
        ScriptedProtocol.reset(["spends_search": { _ in (200, #"{"items":[{"id":"only","version":1,"occurred_on":"2026-10-04","kind":"expense","amount_minor":1,"currency":"USD"}],"total":1,"next_offset":null}"#) }])
        let (m, cache) = model()
        m.filter.kind = "refund"
        await m.reload()
        XCTAssertNil(cache.load([Spend].self, "spends"), "a filtered page must not replace the newest-first first page")
        let body = String(decoding: ScriptedProtocol.calls.last!.body, as: UTF8.self)
        XCTAssertTrue(body.contains("\"kind\":\"refund\""), body)
    }
}

@MainActor
final class TagDirectoryTests: XCTestCase {
    private func directory() -> Directory {
        let cache = DiskCache(owner: "tags-\(UUID().uuidString)")
        addTeardownBlock { cache.destroy() }
        return Directory(engine: scriptedEngine(), cache: cache)
    }

    func testANewTagIsCreatedAndKnownAfterwards() async {
        ScriptedProtocol.reset(["tags_create": { _ in (200, #"{"id":"t1","name":"Trip","version":1}"#) }])
        let d = directory()
        guard case .ok(let t) = await d.resolveTag("  Trip ") else { return XCTFail() }
        XCTAssertEqual(t.id, "t1"); XCTAssertEqual(d.tag("t1"), "Trip")
        // Asking again, in another case, must not create it a second time.
        guard case .ok(let again) = await d.resolveTag("trip") else { return XCTFail() }
        XCTAssertEqual(again.id, "t1"); XCTAssertEqual(ScriptedProtocol.calls.filter { $0.op == "tags_create" }.count, 1)
    }

    func testATagCreatedElsewhereIsFoundInsteadOfFailing() async {
        ScriptedProtocol.reset([
            "tags_create": { _ in (409, #"{"error":{"code":"duplicate","message":"That name or alias is already in use"}}"#) },
            "tags_list": { _ in (200, #"{"items":[{"id":"t9","name":"Work","version":1}],"total":1,"next_offset":null}"#) },
        ])
        guard case .ok(let t) = await directory().resolveTag("work") else { return XCTFail() }
        XCTAssertEqual(t.id, "t9")
    }

    func testTagsAreOrderedByHowOftenTheyAreUsed() async {
        ScriptedProtocol.reset(["tags_list": { _ in (200, #"{"items":[{"id":"a","name":"Alpha","version":1},{"id":"b","name":"Beta","version":1}],"total":2,"next_offset":null}"#) }, "merchants_list": { _ in (200, #"{"items":[],"total":0,"next_offset":null}"#) }, "categories_list": { _ in (200, #"{"items":[],"total":0,"next_offset":null}"#) }])
        let d = directory()
        await d.refresh()
        var used = Spend(id: "1", version: 1, occurredOn: "2026-10-04", kind: "expense", amountMinor: 1, currency: "USD"); used.tagIds = ["b", "b"]
        XCTAssertEqual(d.tagsByUse([used]).map(\.name), ["Beta", "Alpha"])
        XCTAssertEqual(d.tagNames(["b", "gone", "a"]), ["Beta", "Alpha"], "an unknown tag id is skipped, not shown as a UUID")
    }
}

final class FilterChipTests: XCTestCase {
    private func chips(_ f: SpendFilter) -> [FilterChip] {
        f.chips(category: { ["c1": "Coffee"][$0] }, merchant: { ["m1": "Starbucks"][$0] }, tag: { ["t1": "trip", "t2": "work"][$0] })
    }

    func testNothingActiveMeansNoChips() { XCTAssertTrue(chips(SpendFilter()).isEmpty) }

    func testEachConditionIsOneChipWithAReadableLabel() {
        var f = SpendFilter()
        f.search = "latte"; f.kind = "refund"; f.currency = "USD"; f.categoryId = "c1"; f.merchantId = "m1"; f.tagIds = ["t1", "t2"]; f.minAmountMinor = 500; f.maxAmountMinor = 2000
        let labels = chips(f).map(\.label)
        XCTAssertEqual(labels, ["“latte”", "Refunds", "USD", "Coffee", "Starbucks", "#trip", "#work", "$5.00 – $20.00"])
    }

    func testClearingAChipRemovesOnlyItsOwnCondition() {
        var f = SpendFilter(); f.kind = "expense"; f.tagIds = ["t1", "t2"]; f.categoryId = "c1"
        var chip = chips(f).first { $0.id == "tag-t1" }!
        chip.clear(&f)
        XCTAssertEqual(f.tagIds, ["t2"]); XCTAssertEqual(f.kind, "expense"); XCTAssertEqual(f.categoryId, "c1")
    }

    func testDroppingTheCurrencyAlsoDropsWhatOnlyMakesSenseWithOne() {
        var f = SpendFilter(); f.currency = "USD"; f.sort = "amount_desc"; f.minAmountMinor = 100
        chips(f).first { $0.id == "currency" }!.clear(&f)
        XCTAssertNil(f.currency); XCTAssertEqual(f.sort, "date_desc"); XCTAssertNil(f.minAmountMinor)
    }

    func testUncategorizedIsItsOwnChip() {
        var f = SpendFilter(); f.uncategorized = true
        XCTAssertEqual(chips(f).map(\.label), ["Uncategorized"])
    }

    func testDateRangeLabelsHandleOpenEnds() {
        var f = SpendFilter(); f.from = "2026-10-01"
        XCTAssertTrue(chips(f)[0].label.hasPrefix("From")); f.from = nil; f.to = "2026-10-31"
        XCTAssertTrue(chips(f)[0].label.hasPrefix("Until"))
    }

    func testDatePresets() {
        var cal = Calendar(identifier: .gregorian); cal.timeZone = .gmt
        let today = cal.date(from: DateComponents(year: 2026, month: 10, day: 15))!
        XCTAssertEqual(DatePreset.thisMonth.range(today: today, calendar: cal)?.from, "2026-10-01")
        XCTAssertEqual(DatePreset.thisMonth.range(today: today, calendar: cal)?.to, "2026-10-15")
        XCTAssertEqual(DatePreset.lastMonth.range(today: today, calendar: cal)?.from, "2026-09-01")
        XCTAssertEqual(DatePreset.lastMonth.range(today: today, calendar: cal)?.to, "2026-09-30")
        XCTAssertEqual(DatePreset.last30.range(today: today, calendar: cal)?.from, "2026-09-16")
        XCTAssertEqual(DatePreset.matching(from: "2026-09-01", to: "2026-09-30", today: today, calendar: cal), .lastMonth)
        XCTAssertEqual(DatePreset.matching(from: "2026-01-01", to: nil, today: today, calendar: cal), .custom)
        XCTAssertEqual(DatePreset.matching(from: nil, to: nil, today: today, calendar: cal), .any)
    }

    func testAmountFieldsAreReadInTheCurrencysOwnUnits() {
        XCTAssertEqual(Money.parseMinor("12.50", currency: "USD"), 1250)
        XCTAssertEqual(Money.parseMinor("1,200", currency: "JPY"), 1200)
        XCTAssertEqual(Money.parseMinor("5", currency: "USD"), 500)
        XCTAssertNil(Money.parseMinor("abc", currency: "USD"))
    }
}
