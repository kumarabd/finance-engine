package org.nighthawklabs.treasure

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.nighthawklabs.treasure.data.*
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.Engine
import org.nighthawklabs.treasure.net.FinanceApi
import org.nighthawklabs.treasure.net.Transport
import org.nighthawklabs.treasure.net.call
import java.io.File
import kotlin.io.path.createTempDirectory

/** Two accounts on one phone must never see, or send, each other's data. */
class IsolationTest {
    private val root: File = createTempDirectory("treasure-iso").toFile()
    private fun cacheFor(user: String) = DiskCache(File(root, "u-$user"))
    private fun spend(id: String) = Spend(id = id, version = 1, occurredOn = "2026-10-04", kind = "expense", amountMinor = 100, currency = "USD")
    private fun input() = SpendInput("2026-10-04", "expense", 100, "USD")

    /** Records every request that would have left the phone and answers with an empty page. */
    private class Counting : Transport {
        val calls = mutableListOf<Pair<String, String>>() // url to token
        override suspend fun post(url: String, token: String, body: String, timeoutMs: Int): Pair<Int, String>? {
            calls += url to token
            return 200 to """{"items":[],"total":0,"next_offset":null}"""
        }
    }

    @Test fun anotherUserSeesNoCachedDataAndNoPendingSpends() {
        val a = cacheFor("A"); val b = cacheFor("B")
        a.save(listOf(spend("a1")), "spends")
        Outbox(a).add(OutboxItem("pending-a", input(), createdAt = 0))

        assertNull(b.load<List<Spend>>("spends"))
        assertTrue(Outbox(b).items.value.isEmpty())
        assertEquals(listOf("a1"), a.load<List<Spend>>("spends")?.map { it.id })
        assertEquals(listOf("pending-a"), Outbox(a).items.value.map { it.id })
    }

    @Test fun signOutDropsCachedListsButKeepsUnsentSpendsForTheirOwner() {
        // Unsent spends are in a different, durable folder from the re-fetchable cache (see Session).
        val cacheDir = File(root, "cache-A"); val durableDir = File(root, "durable-A")
        DiskCache(cacheDir).save(listOf(spend("a1")), "spends")
        Outbox(DiskCache(durableDir)).add(OutboxItem("pending-a", input(), createdAt = 0))
        cacheDir.deleteRecursively() // what Session.purgeCaches does
        assertNull(DiskCache(cacheDir).load<List<Spend>>("spends"))
        assertEquals(listOf("pending-a"), Outbox(DiskCache(durableDir)).items.value.map { it.id })
    }

    @Test fun aSessionForTheWrongUserSendsNothing() = runTest {
        val transport = Counting()
        var signedIn = "user_A"
        val engine = Engine(FinanceApi("https://router.test/finance/api/v1", transport), "user_A", { signedIn }) { "token-for-$signedIn" }

        assertTrue(engine.call<SearchInput, Page<Spend>>("spends_search", SearchInput()) is Api.Ok)
        assertEquals("token-for-user_A", transport.calls.single().second)

        signedIn = "user_B" // the phone switched accounts while session A's work was still running
        assertTrue(engine.call<SearchInput, Page<Spend>>("spends_search", SearchInput()) is Api.Unauthorized)
        assertEquals("nothing may be sent with the other user's token", 1, transport.calls.size)
    }

    @Test fun aStaleSessionDoesNotFlushItsPendingSpendsIntoAnotherAccount() = runTest {
        val transport = Counting()
        var signedIn = "user_A"
        val engine = Engine(FinanceApi("https://router.test/finance/api/v1", transport), "user_A", { signedIn }) { "t" }
        val cache = cacheFor("A")
        val store = SpendsStore(engine, cache, Outbox(cache)) { Api.Ok(null) }
        store.enqueue(input(), null, "k1")

        signedIn = "user_B"
        store.flushOutbox()

        assertTrue(transport.calls.isEmpty())
        assertEquals(listOf("k1"), store.outbox.items.value.map { it.id }) // still waiting, for user A
    }

    @Test fun aNewSessionStartsWithOnlyItsOwnCachedSpends() {
        cacheFor("A").save(listOf(spend("a1")), "spends")
        val engine = Engine(FinanceApi("https://router.test/finance/api/v1", Counting()), "B", { "B" }) { "t" }
        val b = cacheFor("B")
        assertTrue(SpendsStore(engine, b, Outbox(b)) { Api.Ok(null) }.state.value.spends.isEmpty())
    }
}
