package org.nighthawklabs.treasure

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import org.junit.Assert.*
import org.junit.Test
import org.nighthawklabs.treasure.data.*
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.EngineApi
import org.nighthawklabs.treasure.net.FinanceApi
import java.io.File
import kotlin.io.path.createTempDirectory

class ClassifyRetryTest {
    private fun classify(code: Int, body: String = "") = FinanceApi.classify(code, body) { it }

    @Test fun serverFailuresAreRetryableNotFinal() {
        // The engine's own 500 carries an error code; it used to be read as a definitive failure.
        assertTrue(classify(500, """{"error":{"code":"internal","message":"The operation could not be completed"}}""") is Api.ServerError)
        for (status in listOf(502, 503, 504, 408, 429)) assertTrue("$status must be retryable", classify(status, """{"error":{"code":"busy","message":"x"}}""") is Api.Retry)
    }

    @Test fun engineAnswersThatAreDefinitiveStayDefinitive() {
        assertEquals("duplicate", (classify(409, """{"error":{"code":"duplicate","message":"x"}}""") as Api.Failed).code)
        assertEquals("invalid_input", (classify(400, """{"error":{"code":"invalid_input","message":"x"}}""") as Api.Failed).code)
    }
}

class OutboxRetryTest {
    private fun cache() = DiskCache(createTempDirectory("treasure-retry").toFile())
    private fun item(id: String) = OutboxItem(id, SpendInput("2026-10-04", "expense", 100, "USD", source = "android", sourceRecordId = id), createdAt = 0)
    private fun made(id: String) = Spend(id = "srv-$id", version = 1, occurredOn = "2026-10-04", kind = "expense", amountMinor = 100, currency = "USD")
    private fun outbox(vararg ids: String) = Outbox(cache()).also { o -> ids.forEach { o.add(item(it)) } }

    @Test fun aServerErrorIsRetriedInOrderAndKeepsItsKey() = runTest {
        val o = outbox("a", "b")
        val sent = mutableListOf<String>()
        val created = o.flush { sent += it.id; Api.ServerError("500") }
        assertEquals(listOf("a"), sent) // stops at the first so order is kept
        assertTrue(created.isEmpty())
        assertEquals(listOf("a", "b"), o.items.value.map { it.id })
        assertNull(o.items.value[0].failure)
    }

    @Test fun aRepeatingServerErrorGivesUpSoItCannotBlockTheQueueForever() = runTest {
        val o = outbox("a", "b")
        repeat(Outbox.MAX_SERVER_ERRORS - 1) { o.flush { Api.ServerError("500") } }
        assertNull(o.items.value[0].failure)
        val created = o.flush { if (it.id == "a") Api.ServerError("500") else Api.Ok(made(it.id)) } // the 5th attempt
        assertNotNull(o.items.value.first { it.id == "a" }.failure)
        assertEquals(listOf("srv-b"), created.map { it.id }) // the spend behind it is no longer blocked
    }

    @Test fun offlineAndBusyNeverCountTowardGivingUp() = runTest {
        val o = outbox("a")
        repeat(Outbox.MAX_SERVER_ERRORS * 2) { o.flush { Api.Retry("offline") } }
        assertNull(o.items.value[0].failure); assertNull(o.items.value[0].attempts)
    }

    @Test fun retryPutsAGivenUpSpendBackWithItsSameKey() = runTest {
        val o = outbox("a")
        repeat(Outbox.MAX_SERVER_ERRORS) { o.flush { Api.ServerError("500") } }
        assertNotNull(o.items.value[0].failure)
        o.retry("a")
        assertNull(o.items.value[0].failure); assertNull(o.items.value[0].attempts); assertEquals("a", o.items.value[0].id)
        assertEquals(1, o.flush { Api.Ok(made(it.id)) }.size); assertTrue(o.items.value.isEmpty())
    }
}

/** A merchant created by one attempt whose reply was lost, or by another device, must not fail the spend that needs it. */
class MerchantResolveTest {
    private class FakeEngine(private val answers: Map<String, Api<*>>) : EngineApi {
        val calls = mutableListOf<String>()
        @Suppress("UNCHECKED_CAST")
        override suspend fun <I, O> call(op: String, input: I, inSer: KSerializer<I>, outSer: KSerializer<O>): Api<O> {
            calls += op
            return (answers[op] ?: Api.Failed(null, "unexpected $op")) as Api<O>
        }
    }
    private val taken = Api.Failed("duplicate", "That name or alias is already in use")
    private fun dir(engine: FakeEngine) = Directory(engine, DiskCache(File(createTempDirectory("treasure-dir").toFile(), "d")))
    private fun page(vararg d: Dimension) = Api.Ok(Page(d.toList(), d.size, null))

    @Test fun aNameThatAlreadyExistsIsUsedInsteadOfFailing() = runTest {
        val engine = FakeEngine(mapOf("merchants_create" to taken, "merchants_list" to page(Dimension("m1", "Blue Bottle", emptyList(), 1))))
        assertEquals("m1", (dir(engine).resolveMerchant("Blue Bottle") as Api.Ok).value)
        assertEquals(listOf("merchants_create", "merchants_list"), engine.calls)
    }

    @Test fun anOlderEngineSayingConflictIsHandledToo() = runTest {
        val engine = FakeEngine(mapOf("merchants_create" to Api.Failed("conflict", "That name or alias is already in use"), "merchants_list" to page(Dimension("m1", "Blue Bottle", null, 1))))
        assertEquals("m1", (dir(engine).resolveMerchant("blue bottle") as Api.Ok).value)
    }

    @Test fun aTakenNameThatCannotBeFoundStillReportsTheFailure() = runTest {
        val engine = FakeEngine(mapOf("merchants_create" to taken, "merchants_list" to page()))
        assertEquals("duplicate", (dir(engine).resolveMerchant("Ghost") as Api.Failed).code) // must not pretend it resolved
    }

    @Test fun aServerErrorWhileCreatingIsRetryableNotFinal() = runTest {
        assertTrue(dir(FakeEngine(mapOf("merchants_create" to Api.ServerError("server returned 500")))).resolveMerchant("New Place") is Api.ServerError)
    }
}

/** Importing the same statement twice: the engine answers `duplicate`, and the import must carry on and report what was already there. */
class ReimportTest {
    private class FakeEngine(private val answers: Map<String, Api<*>>) : EngineApi {
        val calls = mutableListOf<String>()
        @Suppress("UNCHECKED_CAST")
        override suspend fun <I, O> call(op: String, input: I, inSer: KSerializer<I>, outSer: KSerializer<O>): Api<O> {
            calls += op
            return (answers[op] ?: Api.Failed(null, "unexpected $op")) as Api<O>
        }
    }
    private val noReader = object : org.nighthawklabs.treasure.ingest.DocumentReader {
        override suspend fun extract(uri: android.net.Uri) = error("not used")
        override suspend fun extract(uris: List<android.net.Uri>, name: String) = error("not used")
    }
    private val statement = org.nighthawklabs.treasure.ingest.ExtractedDocument("10/02/2026 RAMEN SHOP -12.50\n10/03/2026 BOOKSTORE -30.00\n10/04/2026 CORNER CAFE -4.75", false, "h1", "stmt.pdf", "application/pdf")
    private val taken = Api.Failed("duplicate", "A record with that name or source identity already exists")
    private fun session(engine: FakeEngine): org.nighthawklabs.treasure.ingest.ImportSession {
        val cache = DiskCache(File(createTempDirectory("treasure-reimport").toFile(), "c"))
        return org.nighthawklabs.treasure.ingest.ImportSession(engine, Directory(engine, cache), SpendsStore(engine, cache, Outbox(cache)) { Api.Ok(null) }, noReader, { "USD" })
    }
    private val emptyPage = Api.Ok(Page<Spend>(emptyList(), 0, null))
    private val emptyDims = Api.Ok(Page<Dimension>(emptyList(), 0, null))
    private val common = mapOf<String, Api<*>>(
        "evidence_create" to Api.Ok(EvidenceRecord("ev1")), "merchants_create" to Api.Ok(Dimension("m1", "x", null, 1)),
        "merchants_list" to emptyDims, "categories_list" to emptyDims, "spends_search" to emptyPage,
    )

    @Test fun reimportingASeenStatementSkipsTheRowsAlreadyThere() = runTest {
        val engine = FakeEngine(common + mapOf("spends_bulk_create" to taken, "spends_create" to taken))
        val s = session(engine)
        s.begin(statement)
        assertEquals(3, s.state.value.items.size)
        s.save()
        assertEquals(org.nighthawklabs.treasure.ingest.ImportPhase.Done(0, 3), s.state.value.phase)
        assertEquals("falls back to one row at a time", 3, engine.calls.count { it == "spends_create" })
    }

    @Test fun aServerErrorWhileImportingStopsWithAMessageInsteadOfSkippingRows() = runTest {
        val s = session(FakeEngine(common + mapOf("spends_bulk_create" to Api.ServerError("server returned 500"))))
        s.begin(statement)
        s.save()
        val phase = s.state.value.phase as org.nighthawklabs.treasure.ingest.ImportPhase.Failed
        assertTrue(phase.message, phase.message.contains("problem"))
    }
}
