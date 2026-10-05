import XCTest
@testable import Treasure

/// Counts requests that would have left the phone, and answers every one with an empty page.
private final class CountingProtocol: URLProtocol {
    nonisolated(unsafe) static var requests: [URLRequest] = []
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        let body = Data(#"{"items":[],"total":0,"next_offset":null}"#.utf8)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

/// Two accounts on one phone must never see, or send, each other's data.
@MainActor
final class IsolationTests: XCTestCase {
    private func spend(_ id: String) -> Spend { Spend(id: id, version: 1, occurredOn: "2026-10-04", kind: "expense", amountMinor: 100, currency: "USD") }
    private func item(_ id: String) -> OutboxItem {
        OutboxItem(id: id, spend: SpendInput(occurredOn: "2026-10-04", kind: "expense", amountMinor: 100, currency: "USD", source: "ios", sourceRecordId: id), createdAt: .now)
    }
    private func caches() -> (a: DiskCache, b: DiskCache) {
        let a = DiskCache(owner: "user_A_\(UUID().uuidString.prefix(8))"), b = DiskCache(owner: "user_B_\(UUID().uuidString.prefix(8))")
        addTeardownBlock { a.destroy(); b.destroy() }
        return (a, b)
    }
    private func stubAPI() -> FinanceAPI {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [CountingProtocol.self]
        return FinanceAPI(baseURL: "https://router.test/finance/api/v1", session: URLSession(configuration: config))
    }

    func testAnotherUserSeesNoCachedDataAndNoPendingSpends() {
        let (a, b) = caches()
        a.save([spend("a1")], as: "spends")
        Outbox(cache: a).add(item("pending-a"))

        XCTAssertNil(b.load([Spend].self, "spends"))
        XCTAssertTrue(Outbox(cache: b).items.isEmpty)
        XCTAssertEqual(a.load([Spend].self, "spends")?.map(\.id), ["a1"])
        XCTAssertEqual(Outbox(cache: a).items.map(\.id), ["pending-a"])
    }

    func testSignOutDropsCachedListsButKeepsUnsentSpendsForTheirOwner() {
        let (a, _) = caches()
        a.save([spend("a1")], as: "spends")
        Outbox(cache: a).add(item("pending-a"))
        a.purgeCaches()
        XCTAssertNil(a.load([Spend].self, "spends"))
        XCTAssertEqual(Outbox(cache: a).items.map(\.id), ["pending-a"], "unsent spends must survive sign-out")
    }

    func testOwnerIdsThatAreNotFilenameSafeStillGetTheirOwnFolder() {
        let a = DiskCache(owner: "../evil"), b = DiskCache(owner: "../evil2")
        addTeardownBlock { a.destroy(); b.destroy() }
        a.save([spend("x")], as: "spends")
        XCTAssertNil(b.load([Spend].self, "spends"))
        XCTAssertNotNil(a.load([Spend].self, "spends"))
    }

    func testASessionForTheWrongUserSendsNothing() async {
        CountingProtocol.requests = []
        var signedIn = "user_A"
        let engine = Engine(owner: "user_A", currentUser: { signedIn }, token: { _ in "token-for-\(signedIn)" }, api: stubAPI())

        let ok: Api<Page<Spend>> = await engine.call("spends_search", SearchInput())
        guard case .ok = ok else { return XCTFail("the owner's own session should work") }
        XCTAssertEqual(CountingProtocol.requests.count, 1)
        XCTAssertEqual(CountingProtocol.requests.first?.value(forHTTPHeaderField: "Authorization"), "Bearer token-for-user_A")

        signedIn = "user_B"    // the phone switched accounts while session A's work was still running
        let blocked: Api<Page<Spend>> = await engine.call("spends_search", SearchInput())
        guard case .unauthorized = blocked else { return XCTFail("a stale session must be refused") }
        XCTAssertEqual(CountingProtocol.requests.count, 1, "nothing may be sent with the other user's token")
    }

    func testAStaleSessionDoesNotFlushItsPendingSpendsIntoAnotherAccount() async {
        CountingProtocol.requests = []
        let (a, _) = caches()
        var signedIn = "user_A"
        let engine = Engine(owner: "user_A", currentUser: { signedIn }, token: { _ in "t" }, api: stubAPI())
        let model = SpendsModel(engine: engine, cache: a) { _ in .ok(nil) }
        model.enqueue(SpendInput(occurredOn: "2026-10-04", kind: "expense", amountMinor: 100, currency: "USD"), merchantName: nil, key: "k1")

        signedIn = "user_B"
        await model.flushOutbox()

        XCTAssertTrue(CountingProtocol.requests.isEmpty)
        XCTAssertEqual(model.pending.map(\.id), ["k1"], "still waiting, for user A")
    }

    func testANewSessionStartsWithOnlyItsOwnCachedSpends() {
        let (a, b) = caches()
        a.save([spend("a1")], as: "spends")
        let engine = Engine(owner: "B", currentUser: { "B" }, token: { _ in "t" }, api: stubAPI())
        XCTAssertTrue(SpendsModel(engine: engine, cache: b) { _ in .ok(nil) }.spends.isEmpty)
    }
}
