package org.nighthawklabs.treasure

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.nighthawklabs.treasure.data.*
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.FinanceApi
import java.time.LocalDate
import java.util.Locale

private fun spend(id: String = "1", day: String = "2026-10-04", kind: String = "expense", minor: Long = 100, cur: String = "USD") =
    Spend(id = id, version = 1, occurredOn = day, kind = kind, amountMinor = minor, currency = cur)

private fun bucket(key: String, net: Long, cur: String = "USD") =
    Bucket(cur, key, key, net.toString(), "0", net.toString(), 1)

class MoneyTest {
    @Test fun formatUsesCurrencyFractionDigits() {
        assertEquals("$1,234.50", Money.format(123_450, "USD", Locale.US))
        assertEquals("¥500", Money.format(500, "JPY", Locale.US))
        assertEquals("$90,071,992,547,409.91", Money.format(9_007_199_254_740_991, "USD", Locale.US))
    }

    @Test fun signedDirection() {
        assertEquals(-700, Money.signed(spend(kind = "expense", minor = 700)))
        assertEquals(700, Money.signed(spend(kind = "refund", minor = 700)))
        assertEquals(0, Money.signed(spend(kind = "transfer", minor = 700)))
    }

    @Test fun amountEntryIsRegisterStyle() {
        var e = AmountEntry()
        e = e.press("0"); assertEquals(0, e.minor) // no leading zeros
        e = e.press("1").press("2").press("50")
        assertEquals(1250, e.minor)
        e = e.backspace(); assertEquals(125, e.minor)
        repeat(20) { e = e.press("9") }
        assertTrue(e.digits.length <= AmountEntry.MAX_DIGITS)
        assertEquals(1250, AmountEntry.of(1250).minor)
    }
}

class GroupingTest {
    @Test fun groupsConsecutiveDaysAndNetsRefunds() {
        val g = DayGroups.make(listOf(spend("a", "2026-10-04", "expense", 500), spend("b", "2026-10-04", "refund", 200), spend("c", "2026-10-03")))
        assertEquals(listOf("2026-10-04", "2026-10-03"), g.map { it.day })
        assertEquals(-300L, g[0].net?.first)
    }

    @Test fun mixedCurrencyDayHasNoNet() {
        assertNull(DayGroups.make(listOf(spend("a", cur = "USD"), spend("b", cur = "EUR")))[0].net)
    }

    @Test fun titleTodayAndYesterday() {
        val now = LocalDate.of(2026, 10, 4)
        assertEquals("Today", DayGroups.title("2026-10-04", now, Locale.US))
        assertEquals("Yesterday", DayGroups.title("2026-10-03", now, Locale.US))
        assertEquals("Mon, Sep 28", DayGroups.title("2026-09-28", now, Locale.US))
    }

    @Test fun upsertKeepsDayOrder() {
        val out = SpendOrder.upsert(listOf(spend("a", "2026-10-04"), spend("b", "2026-10-02")), spend("c", "2026-10-03"))
        assertEquals(listOf("a", "c", "b"), out.map { it.id })
        assertEquals(listOf("b", "a", "c"), SpendOrder.upsert(out, spend("b", "2026-10-05")).map { it.id })
    }

    @Test fun csvJoinKeepsOneHeader() {
        assertEquals("id,x\n1,a\n2,b\n", CSVJoin.join(listOf("id,x\n1,a\n", "id,x\n2,b\n")))
        assertEquals("id,x\n1,a\n", CSVJoin.join(listOf("id,x\n1,a\n")))
        assertEquals("", CSVJoin.join(emptyList()))
    }
}

class InsightsTest {
    @Test fun monthWindowComparesSameElapsedStretch() {
        assertEquals(Period.Window("2026-10-01", "2026-10-15", "2026-09-01", "2026-09-15"), Period.Month.window(LocalDate.of(2026, 10, 15)))
    }

    @Test fun monthEndClampsInShorterMonth() {
        assertEquals("2026-02-28", Period.Month.window(LocalDate.of(2026, 3, 31)).compareTo)
    }

    @Test fun yearAndQuarterWindows() {
        val today = LocalDate.of(2026, 10, 4)
        assertEquals("2026-01-01", Period.Year.window(today).from)
        val q = Period.Quarter.window(today)
        assertEquals("2026-08-01", q.from); assertEquals("2026-05-01", q.compareFrom)
    }

    @Test fun seriesFillsEmptyDaysWithZero() {
        val s = Trend.series(listOf(bucket("2026-10-02", 500)), "USD", "day", "2026-10-01", "2026-10-03")
        assertEquals(listOf(0L, 500L, 0L), s.map { it.minor })
    }

    @Test fun weekAndMonthBucketsMatchTheEngine() {
        // 2026-10-04 is a Sunday; its Monday-based week starts 2026-09-28.
        assertEquals(listOf("2026-09-28", "2026-10-05", "2026-10-12"), Trend.keys("week", "2026-10-04", "2026-10-12"))
        assertEquals(listOf("2026-08-01", "2026-09-01", "2026-10-01"), Trend.keys("month", "2026-08-15", "2026-10-01"))
    }

    @Test fun breakdownFoldsTailIntoOtherAndKeepsCurrenciesApart() {
        val b = (1..7).map { bucket("c$it", it * 100L) } + bucket("eur", 9_999, "EUR")
        val rows = Breakdown.top(b, "USD", 5)
        assertEquals(listOf("c7", "c6", "c5", "c4", "c3", "other"), rows.map { it.key })
        assertEquals(300L, rows.last().minor)
        assertEquals(2800L, rows.sumOf { it.minor })
    }

    @Test fun deltaWording() {
        assertEquals("12% more than last month", Delta.text(112, 100, "last month"))
        assertEquals("50% less than last week", Delta.text(50, 100, "last week"))
        assertNull(Delta.text(50, 0, "last week"))
        assertNull(Delta.text(50, null, "last week"))
    }

    @Test fun analysisDecodesStringAggregatesAndMissingComparison() {
        val json = """{"group_by":"day","groups_overlap":false,"current":[{"currency":"USD","key":"2026-10-01","label":"2026-10-01","expense_minor":"9007199254740991","refund_minor":"0","net_minor":"9007199254740991","count":1}]}"""
        val a = ApiJson.decodeFromString<Analysis>(json)
        assertEquals(9_007_199_254_740_991, a.current.first().net)
        assertTrue(a.comparison.isEmpty())
    }
}

class WireTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun pageDecodesSpendWithNullDeletedAt() {
        val json = """{"items":[{"id":"a","version":2,"occurred_on":"2026-10-04","kind":"expense","amount_minor":1250,"currency":"USD","deleted_at":null}],"total":1,"next_offset":null}"""
        val page = ApiJson.decodeFromString<Page<Spend>>(json)
        assertEquals(1250, page.items.first().amountMinor)
        assertNull(page.nextOffset)
    }

    @Test fun editCarriesUnseenFieldsAndOmitsNull() {
        val s = spend().copy(source = "import", sourceRecordId = "r1", accountRef = "chk", evidenceIds = listOf("e1"), version = 3)
        val j = obj(ApiJson.encodeToString(SpendInput(s)))
        assertEquals("r1", j["source_record_id"]?.jsonPrimitive?.content)
        assertEquals("chk", j["account_ref"]?.jsonPrimitive?.content)
        assertEquals("e1", (j["evidence_ids"] as JsonArray)[0].jsonPrimitive.content)
        assertFalse(j.containsKey("merchant_id")) // null is omitted, never sent as null
        assertFalse(j.containsKey("id"))          // not an editable field
    }

    @Test fun writeInputsFlattenIdempotencyKeyAndVersion() {
        val j = obj(ApiJson.encodeToString(LifecycleInput("k", "x", 2)))
        assertEquals("k", j["idempotency_key"]?.jsonPrimitive?.content)
        assertEquals(2, j["expected_version"]?.jsonPrimitive?.int)
    }

    @Test fun merchantRenameResendsAliasesAndVersion() {
        val j = obj(ApiJson.encodeToString(UpdateDimensionInput("k", "m", 4, "Cafe", listOf("Café"))))
        assertEquals("Café", j["aliases"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(4, j["expected_version"]!!.jsonPrimitive.int)
    }

    @Test fun pageInputAlwaysSendsOffset() {
        val j = obj(ApiJson.encodeToString(PageInput(200, 0)))
        assertEquals(0, j["offset"]!!.jsonPrimitive.int)
    }

    @Test fun operationPrefixes() {
        assertEquals("categories", DimensionKind.Category.plural)
        assertEquals("tags", DimensionKind.Tag.plural)
        assertEquals("merchants", DimensionKind.Merchant.plural)
    }

    @Test fun classifyMapsEngineErrors() {
        val conflict = FinanceApi.classify(409, """{"error":{"code":"conflict","message":"stale"}}""") { it }
        assertEquals("conflict", (conflict as Api.Failed).code)
        assertTrue(FinanceApi.classify(401, "") { it } is Api.Unauthorized)
        assertTrue(FinanceApi.classify(503, "") { it } is Api.Retry)
        assertTrue(FinanceApi.classify(404, """{"error":"no_tenant"}""") { it } is Api.NotProvisioned)
        assertTrue(FinanceApi.classify(200, "not json") { Json.parseToJsonElement(it) } is Api.Retry)
        assertEquals("router said no", (FinanceApi.classify(400, """{"error":"router said no"}""") { it } as Api.Failed).message)
    }
}

class OutboxTest {
    private val dirs = mutableListOf<java.io.File>()
    private fun cache() = DiskCache(kotlin.io.path.createTempDirectory("treasure").toFile().also { dirs += it })

    private fun item(id: String) = OutboxItem(id, SpendInput("2026-10-04", "expense", 100, "USD", source = "android", sourceRecordId = id), createdAt = 0)
    private fun made(id: String) = spend("srv-$id")

    @Test fun flushSendsOldestFirstAndEmptiesQueue() = runTest {
        val o = Outbox(cache()).also { it.add(item("a")); it.add(item("b")) }
        val sent = mutableListOf<String>()
        val created = o.flush { sent += it.id; Api.Ok(made(it.id)) }
        assertEquals(listOf("a", "b"), sent)
        assertEquals(listOf("srv-a", "srv-b"), created.map { it.id })
        assertTrue(o.items.value.isEmpty())
    }

    @Test fun offlineStopsAtFirstItemAndKeepsEverything() = runTest {
        val o = Outbox(cache()).also { it.add(item("a")); it.add(item("b")) }
        var calls = 0
        val created = o.flush { calls++; Api.Retry("offline") }
        assertEquals(1, calls)
        assertTrue(created.isEmpty())
        assertEquals(listOf("a", "b"), o.items.value.map { it.id })
    }

    @Test fun refusedItemIsMarkedAndTheRestStillSend() = runTest {
        val o = Outbox(cache()).also { it.add(item("a")); it.add(item("b")) }
        val created = o.flush { if (it.id == "a") Api.Failed("invalid_input", "bad amount") else Api.Ok(made(it.id)) }
        assertEquals(listOf("srv-b"), created.map { it.id })
        assertEquals(listOf("a"), o.items.value.map { it.id })
        assertEquals("bad amount", o.items.value.first().failure)
        var calls = 0
        o.flush { calls++; Api.Retry("x") } // a failed item is not retried automatically
        assertEquals(0, calls)
    }

    @Test fun queueSurvivesRelaunch() {
        val c = cache()
        Outbox(c).add(item("a"))
        assertEquals(listOf("a"), Outbox(c).items.value.map { it.id })
    }

    @Test fun pendingRowShowsTypedMerchantName() {
        assertEquals("Blue Bottle", item("a").copy(merchantName = "Blue Bottle").asSpend().description)
    }
}
