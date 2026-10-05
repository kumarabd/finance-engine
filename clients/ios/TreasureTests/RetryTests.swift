import XCTest
@testable import Treasure

/// Answers each engine operation (the last path component) with a canned status and body, and records the order of calls.
private final class StubProtocol: URLProtocol {
    nonisolated(unsafe) static var answers: [String: (Int, String)] = [:]
    nonisolated(unsafe) static var calls: [String] = []
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let op = request.url!.lastPathComponent
        Self.calls.append(op)
        let (status, body) = Self.answers[op] ?? (200, "{}")
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class ClassifyTests: XCTestCase {
    private func classify(_ code: Int, _ body: String = "") -> Api<Spend> { FinanceAPI.classify(code, Data(body.utf8)) }

    func testServerFailuresAreRetryableNotFinal() {
        let internalError = #"{"error":{"code":"internal","message":"The operation could not be completed"}}"#
        // The engine's own 500 carries an error code; it used to be read as a definitive failure.
        guard case .serverError = classify(500, internalError) else { return XCTFail("500 must be retryable") }
        for status in [502, 503, 504, 408, 429] {
            guard case .retry = classify(status, #"{"error":{"code":"busy","message":"x"}}"#) else { return XCTFail("\(status) must be retryable") }
        }
    }

    func testEngineAnswersThatAreDefinitiveStayDefinitive() {
        guard case .failed(let c1, _) = classify(409, #"{"error":{"code":"duplicate","message":"x"}}"#) else { return XCTFail() }
        XCTAssertEqual(c1, "duplicate")
        guard case .failed(let c2, _) = classify(400, #"{"error":{"code":"invalid_input","message":"x"}}"#) else { return XCTFail() }
        XCTAssertEqual(c2, "invalid_input")
    }
}

@MainActor
final class OutboxRetryTests: XCTestCase {
    private func item(_ id: String) -> OutboxItem {
        OutboxItem(id: id, spend: SpendInput(occurredOn: "2026-10-04", kind: "expense", amountMinor: 100, currency: "USD", source: "ios", sourceRecordId: id), createdAt: .now)
    }
    private func spend(_ id: String) -> Spend { Spend(id: "srv-\(id)", version: 1, occurredOn: "2026-10-04", kind: "expense", amountMinor: 100, currency: "USD") }
    private func outbox(_ ids: [String]) -> Outbox {
        let cache = DiskCache(owner: "retry-\(UUID().uuidString)")
        addTeardownBlock { cache.destroy() }
        let o = Outbox(cache: cache)
        ids.forEach { o.add(item($0)) }
        return o
    }

    func testAServerErrorIsRetriedInOrderAndKeepsItsKey() async {
        let o = outbox(["a", "b"])
        var sent: [String] = []
        let created = await o.flush { sent.append($0.id); return .serverError("500") }
        XCTAssertEqual(sent, ["a"], "stops at the first so order is kept")
        XCTAssertTrue(created.isEmpty)
        XCTAssertEqual(o.items.map(\.id), ["a", "b"])
        XCTAssertNil(o.items[0].failure)
        XCTAssertEqual(o.items[0].id, "a", "the idempotency key never changes")
    }

    func testARepeatingServerErrorGivesUpSoItCannotBlockTheQueueForever() async {
        let o = outbox(["a", "b"])
        for _ in 1..<Outbox.maxServerErrors { _ = await o.flush { _ in .serverError("500") } }
        XCTAssertNil(o.items[0].failure)
        let created = await o.flush { $0.id == "a" ? .serverError("500") : .ok(self.spend($0.id)) }   // the 5th attempt
        XCTAssertNotNil(o.items.first { $0.id == "a" }?.failure)
        XCTAssertEqual(created.map(\.id), ["srv-b"], "the spend behind it is no longer blocked")
    }

    func testOfflineAndBusyNeverCountTowardGivingUp() async {
        let o = outbox(["a"])
        for _ in 0..<(Outbox.maxServerErrors * 2) { _ = await o.flush { _ in .retry("offline") } }
        XCTAssertNil(o.items[0].failure); XCTAssertNil(o.items[0].attempts)
    }

    func testRetryPutsAGivenUpSpendBackWithItsSameKey() async {
        let o = outbox(["a"])
        for _ in 0..<Outbox.maxServerErrors { _ = await o.flush { _ in .serverError("500") } }
        XCTAssertNotNil(o.items[0].failure)
        o.retry("a")
        XCTAssertNil(o.items[0].failure); XCTAssertNil(o.items[0].attempts); XCTAssertEqual(o.items[0].id, "a")
        let created = await o.flush { .ok(self.spend($0.id)) }
        XCTAssertEqual(created.count, 1); XCTAssertTrue(o.items.isEmpty)
    }
}

/// A merchant created by one attempt whose reply was lost, or by another device, must not fail the spend that needs it.
@MainActor
final class MerchantResolveTests: XCTestCase {
    private func directory() -> Directory {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let engine = Engine(owner: "u", currentUser: { "u" }, token: { _ in "t" }, api: FinanceAPI(baseURL: "https://router.test/finance/api/v1", session: URLSession(configuration: config)))
        let cache = DiskCache(owner: "merchant-\(UUID().uuidString)")
        addTeardownBlock { cache.destroy() }
        return Directory(engine: engine, cache: cache)
    }

    func testANameThatAlreadyExistsIsUsedInsteadOfFailing() async {
        StubProtocol.calls = []
        StubProtocol.answers = [
            "merchants_create": (409, #"{"error":{"code":"duplicate","message":"That name or alias is already in use"}}"#),
            "merchants_list": (200, #"{"items":[{"id":"m1","name":"Blue Bottle","aliases":[],"version":1}],"total":1,"next_offset":null}"#),
        ]
        let r = await directory().resolveMerchant("Blue Bottle")
        guard case .ok(let id) = r else { return XCTFail("expected the existing merchant, got \(r)") }
        XCTAssertEqual(id, "m1")
        XCTAssertEqual(StubProtocol.calls, ["merchants_create", "merchants_list"])
    }

    func testAnOlderEngineSayingConflictIsHandledToo() async {
        StubProtocol.answers = [
            "merchants_create": (409, #"{"error":{"code":"conflict","message":"That name or alias is already in use"}}"#),
            "merchants_list": (200, #"{"items":[{"id":"m1","name":"Blue Bottle","version":1}],"total":1,"next_offset":null}"#),
        ]
        guard case .ok(let id) = await directory().resolveMerchant("blue bottle") else { return XCTFail() }
        XCTAssertEqual(id, "m1")
    }

    func testATakenNameThatCannotBeFoundStillReportsTheFailure() async {
        StubProtocol.answers = [
            "merchants_create": (409, #"{"error":{"code":"duplicate","message":"That name or alias is already in use"}}"#),
            "merchants_list": (200, #"{"items":[],"total":0,"next_offset":null}"#),
        ]
        guard case .failed(let code, _) = await directory().resolveMerchant("Ghost") else { return XCTFail("must not pretend it resolved") }
        XCTAssertEqual(code, "duplicate")
    }

    func testAServerErrorWhileCreatingIsRetryableNotFinal() async {
        StubProtocol.answers = ["merchants_create": (500, #"{"error":{"code":"internal","message":"The operation could not be completed"}}"#)]
        guard case .serverError = await directory().resolveMerchant("New Place") else { return XCTFail() }
    }
}

/// Importing the same statement twice: the engine answers `duplicate`, and the import must carry on and report what was already there.
@MainActor
final class ReimportTests: XCTestCase {
    private func session() -> ImportSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let engine = Engine(owner: "u", currentUser: { "u" }, token: { _ in "t" }, api: FinanceAPI(baseURL: "https://router.test/finance/api/v1", session: URLSession(configuration: config)))
        let cache = DiskCache(owner: "reimport-\(UUID().uuidString)")
        addTeardownBlock { cache.destroy() }
        let directory = Directory(engine: engine, cache: cache)
        return ImportSession(engine: engine, directory: directory, spends: SpendsModel(engine: engine, cache: cache) { _ in .ok(nil) })
    }
    private let statement = ExtractedDocument(text: "10/02/2026 RAMEN SHOP -12.50\n10/03/2026 BOOKSTORE -30.00\n10/04/2026 CORNER CAFE -4.75", isCSV: false, hash: "h1", name: "stmt.pdf", mediaType: "application/pdf")
    private let created = #"{"id":"s1","version":1,"occurred_on":"2026-10-03","kind":"expense","amount_minor":3000,"currency":"USD","deleted_at":null}"#

    func testReimportingASeenStatementSkipsTheRowsAlreadyThere() async {
        StubProtocol.calls = []
        StubProtocol.answers = [
            "evidence_create": (200, #"{"id":"ev1"}"#),
            "merchants_create": (200, #"{"id":"m1","name":"Ramen Shop","version":1}"#),
            "merchants_list": (200, #"{"items":[],"total":0,"next_offset":null}"#),
            "spends_search": (200, #"{"items":[],"total":0,"next_offset":null}"#),
            "spends_bulk_create": (409, #"{"error":{"code":"duplicate","message":"A record with that name or source identity already exists"}}"#),
            "spends_create": (409, #"{"error":{"code":"duplicate","message":"A record with that name or source identity already exists"}}"#),
        ]
        let s = session()
        await s.begin(statement)
        XCTAssertEqual(s.items.count, 3)
        await s.save()
        XCTAssertEqual(s.phase, .done(created: 0, skipped: 3), "\(s.phase)")
        XCTAssertEqual(StubProtocol.calls.filter { $0 == "spends_create" }.count, 3, "falls back to one row at a time")
    }

    func testAServerErrorWhileImportingStopsWithAMessageInsteadOfSkippingRows() async {
        StubProtocol.answers = [
            "evidence_create": (200, #"{"id":"ev1"}"#), "merchants_create": (200, #"{"id":"m1","name":"x","version":1}"#),
            "merchants_list": (200, #"{"items":[],"total":0,"next_offset":null}"#), "spends_search": (200, #"{"items":[],"total":0,"next_offset":null}"#),
            "spends_bulk_create": (500, #"{"error":{"code":"internal","message":"The operation could not be completed"}}"#),
        ]
        let s = session()
        await s.begin(statement)
        await s.save()
        guard case .failed(let message) = s.phase else { return XCTFail("\(s.phase)") }
        XCTAssertTrue(message.contains("problem"), message)
    }
}
