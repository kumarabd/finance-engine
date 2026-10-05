package org.nighthawklabs.treasure

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.nighthawklabs.treasure.data.*
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.EngineApi
import java.io.File
import kotlin.io.path.createTempDirectory

/** A fake engine whose answers are computed from the request, and which remembers every call in order. */
class ScriptedEngine(private val handlers: Map<String, (Any?) -> Api<*>> = emptyMap()) : EngineApi {
    val calls = mutableListOf<Pair<String, Any?>>()
    @Suppress("UNCHECKED_CAST")
    override suspend fun <I, O> call(op: String, input: I, inSer: KSerializer<I>, outSer: KSerializer<O>): Api<O> {
        calls += op to input
        return (handlers[op]?.invoke(input) ?: Api.Failed(null, "unexpected $op")) as Api<O>
    }
}

private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

class FilterAndPatchWireTest {
    private inline fun <reified T> json(v: T) = obj(ApiJson.encodeToString(v))

    @Test fun theDefaultFilterSendsNothingButPaging() {
        // limit 50 / offset 0 are the engine's own defaults, so they are not sent either.
        assertEquals(emptySet<String>(), json(SpendFilter().input()).keys)
        assertEquals(setOf("limit", "offset"), json(SpendFilter().input(limit = 20, offset = 40)).keys)
    }

    @Test fun everyFilterFieldReachesTheEngineWithItsWireName() {
        val f = SpendFilter(search = "  latte ", from = "2026-10-01", to = "2026-10-31", kind = "expense", currency = "USD", merchantId = "m1", categoryId = "c1",
            tagIds = listOf("t1", "t2"), accountRef = "chk", minAmountMinor = 100, maxAmountMinor = 9_900, evidenceId = "e1", state = "deleted", sort = "amount_desc")
        val j = json(f.input(limit = 20, offset = 40))
        assertEquals("latte", j["search"]!!.jsonPrimitive.content)
        assertEquals("m1", j["merchant_id"]!!.jsonPrimitive.content); assertEquals("c1", j["category_id"]!!.jsonPrimitive.content)
        assertEquals(2, (j["tag_ids"] as JsonArray).size); assertEquals("chk", j["account_ref"]!!.jsonPrimitive.content)
        assertEquals(100, j["min_amount_minor"]!!.jsonPrimitive.int); assertEquals(9_900, j["max_amount_minor"]!!.jsonPrimitive.int)
        assertEquals("e1", j["evidence_id"]!!.jsonPrimitive.content); assertEquals("deleted", j["state"]!!.jsonPrimitive.content)
        assertEquals("amount_desc", j["sort"]!!.jsonPrimitive.content); assertEquals(40, j["offset"]!!.jsonPrimitive.int)
    }

    @Test fun uncategorizedReplacesTheCategory() {
        val j = json(SpendFilter(categoryId = "c1", uncategorized = true).input())
        assertEquals("true", j["uncategorized"]!!.jsonPrimitive.content); assertFalse(j.containsKey("category_id"))
    }

    @Test fun chipCountIgnoresStateAndSort() {
        val f = SpendFilter(state = "deleted", sort = "date_asc")
        assertEquals(0, f.activeCount); assertFalse(f.isDefault)
        assertEquals("a date range is one filter", 2, f.copy(from = "2026-10-01", to = "2026-10-31", tagIds = listOf("t")).activeCount)
    }

    @Test fun amountSortNeedsACurrency() {
        assertTrue(SpendFilter(sort = "amount_desc").sortNeedsCurrency)
        assertFalse(SpendFilter(sort = "amount_desc", currency = "USD").sortNeedsCurrency)
    }

    @Test fun patchSendsOnlyWhatIsSetAndKeepsEmptyStringsThatMeanClear() {
        val j = json(SpendPatch(categoryId = "", addTagIds = listOf("t1")))
        assertEquals("an empty category means uncategorized", "", j["category_id"]!!.jsonPrimitive.content)
        assertEquals("t1", j["add_tag_ids"]!!.jsonArray[0].jsonPrimitive.content)
        assertFalse(j.containsKey("merchant_id")); assertFalse(j.containsKey("tag_ids")); assertFalse(j.containsKey("remove_tag_ids"))
        assertEquals("replacing tags with none clears them", 0, (json(SpendPatch(tagIds = emptyList()))["tag_ids"] as JsonArray).size)
    }

    @Test fun bulkInputsCarryEachRecordsExpectedVersion() {
        val j = json(BulkUpdateInput("k", listOf(Versioned("a", 3)), SpendPatch(categoryId = "c")))
        assertEquals("k", j["idempotency_key"]!!.jsonPrimitive.content)
        assertEquals(3, j["records"]!!.jsonArray[0].jsonObject["expected_version"]!!.jsonPrimitive.int)
    }
}

class BulkOperationTest {
    private fun spend(i: Int, version: Long = 1) = Spend(id = "s$i", version = version, occurredOn = "2026-10-04", kind = "expense", amountMinor = 100, currency = "USD")
    private fun store(engine: EngineApi): SpendsStore {
        val cache = DiskCache(File(createTempDirectory("treasure-bulk").toFile(), "c"))
        return SpendsStore(engine, cache, Outbox(cache)) { Api.Ok(null) }
    }
    private fun recordsOf(input: Any?) = when (input) { is BulkUpdateInput -> input.records; is BulkLifecycleInput -> input.records; else -> error("not a bulk input") }

    /** Answers a bulk call by returning every requested record, one version higher, optionally marked deleted. */
    private fun echo(deleted: Boolean = false): (Any?) -> Api<*> = { input ->
        Api.Ok(SpendsResult(recordsOf(input).map { spend(it.id.drop(1).toInt(), it.expectedVersion + 1).copy(deletedAt = if (deleted) "2026-10-05T00:00:00Z" else null) }))
    }

    @Test fun twoHundredFiftySpendsGoAsThreeBatchesOfAtMostAHundred() = runTest {
        val engine = ScriptedEngine(mapOf("spends_bulk_update" to echo()))
        val outcome = store(engine).bulkUpdate((1..250).map { spend(it) }, SpendPatch(categoryId = "c1"))
        assertTrue(outcome.ok); assertEquals(250, outcome.done.size)
        assertEquals(listOf(100, 100, 50), engine.calls.map { recordsOf(it.second).size })
        assertEquals("each batch is its own retry-safe request", 3, engine.calls.map { (it.second as BulkUpdateInput).idempotencyKey }.toSet().size)
    }

    @Test fun theSelectedSpendsVersionsAreSentAndTheListTakesTheNewCopies() = runTest {
        val s = store(ScriptedEngine(mapOf("spends_bulk_update" to echo())))
        val outcome = s.bulkUpdate(listOf(spend(1, version = 4)), SpendPatch(categoryId = "c1"))
        assertEquals(5L, outcome.done.first().version); assertEquals(5L, s.state.value.spends.first { it.id == "s1" }.version)
    }

    @Test fun aStaleVersionExplainsItselfAndChangesNothing() = runTest {
        val s = store(ScriptedEngine(mapOf("spends_bulk_update" to { _ -> Api.Failed("conflict", "Stale version: expected 1, current 2.") })))
        val outcome = s.bulkUpdate(listOf(spend(1)), SpendPatch(categoryId = "c1"))
        assertFalse(outcome.ok); assertTrue(outcome.done.isEmpty()); assertTrue(outcome.failure!!.contains("changed elsewhere"))
    }

    @Test fun aFailureAfterTheFirstBatchSaysWhatWasAlreadyDone() = runTest {
        var n = 0
        val engine = ScriptedEngine(mapOf("spends_bulk_update" to { input -> if (++n == 1) echo()(input) else Api.ServerError("server returned 500") }))
        val outcome = store(engine).bulkUpdate((1..150).map { spend(it) }, SpendPatch(categoryId = "c1"))
        assertEquals(100, outcome.done.size); assertTrue(outcome.failure!!, outcome.failure!!.startsWith("Changed 100 of 150"))
    }

    @Test fun aBulkDeleteOffersUndoWithTheVersionsTheDeleteReturned() = runTest {
        val engine = ScriptedEngine(mapOf("spends_bulk_delete" to echo(deleted = true), "spends_bulk_restore" to echo()))
        val s = store(engine)
        val outcome = s.bulkDelete(listOf(spend(1), spend(2)))
        assertTrue(outcome.ok); assertEquals(2, s.state.value.undo!!.spends.size)

        s.undoDelete()
        assertNull(s.state.value.undo)
        val restore = engine.calls.last()
        assertEquals("spends_bulk_restore", restore.first)
        assertEquals("restoring uses the versions the delete returned, not the originals", listOf(2L, 2L), recordsOf(restore.second).map { it.expectedVersion })
        assertEquals(setOf("s1", "s2"), s.state.value.spends.map { it.id }.toSet())
    }

    @Test fun filteredListsAreNeverWrittenOverTheOfflineCache() = runTest {
        val cacheDir = File(createTempDirectory("treasure-bulk").toFile(), "c")
        val cache = DiskCache(cacheDir)
        val engine = ScriptedEngine(mapOf("spends_search" to { _ -> Api.Ok(Page(listOf(spend(9)), 1, null)) }))
        val s = SpendsStore(engine, cache, Outbox(cache)) { Api.Ok(null) }
        s.setFilter(SpendFilter(kind = "refund"))
        s.reload()
        assertNull("a filtered page must not replace the newest-first first page", cache.load<List<Spend>>("spends"))
        assertEquals("refund", (engine.calls.last().second as SearchInput).kind)
    }
}

class TagDirectoryTest {
    private fun directory(engine: EngineApi) = Directory(engine, DiskCache(File(createTempDirectory("treasure-tags").toFile(), "c")))
    private fun page(vararg d: Dimension) = Api.Ok(Page(d.toList(), d.size, null))

    @Test fun aNewTagIsCreatedAndKnownAfterwards() = runTest {
        val engine = ScriptedEngine(mapOf("tags_create" to { _ -> Api.Ok(Dimension("t1", "Trip", null, 1)) }))
        val d = directory(engine)
        assertEquals("t1", (d.resolveTag("  Trip ") as Api.Ok).value.id); assertEquals("Trip", d.tag("t1"))
        assertEquals("t1", (d.resolveTag("trip") as Api.Ok).value.id) // asking again, in another case, must not create it twice
        assertEquals(1, engine.calls.count { it.first == "tags_create" })
    }

    @Test fun aTagCreatedElsewhereIsFoundInsteadOfFailing() = runTest {
        val engine = ScriptedEngine(mapOf("tags_create" to { _ -> Api.Failed("duplicate", "That name or alias is already in use") }, "tags_list" to { _ -> page(Dimension("t9", "Work", null, 1)) }))
        assertEquals("t9", (directory(engine).resolveTag("work") as Api.Ok).value.id)
    }

    @Test fun tagsAreOrderedByHowOftenTheyAreUsedAndUnknownIdsAreSkipped() = runTest {
        val empty = { _: Any? -> page() }
        val engine = ScriptedEngine(mapOf("tags_list" to { _ -> page(Dimension("a", "Alpha", null, 1), Dimension("b", "Beta", null, 1)) }, "merchants_list" to empty, "categories_list" to empty))
        val d = directory(engine); d.refresh()
        val used = Spend(id = "1", version = 1, occurredOn = "2026-10-04", kind = "expense", amountMinor = 1, currency = "USD", tagIds = listOf("b", "b"))
        assertEquals(listOf("Beta", "Alpha"), d.tagsByUse(listOf(used)).map { it.name })
        assertEquals(listOf("Beta", "Alpha"), d.tagNames(listOf("b", "gone", "a")))
    }
}
