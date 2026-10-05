package org.nighthawklabs.treasure

import org.junit.Assert.*
import org.junit.Test
import org.nighthawklabs.treasure.data.*
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.FinanceApi

/**
 * The app's models against real responses captured from a running finance-engine (clients/fixtures), so a change on either
 * side that would break decoding fails here instead of on a phone.
 */
class ContractTest {
    private fun text(name: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream("$name.json")) { "missing fixture $name.json" }.bufferedReader().use { it.readText() }
    private inline fun <reified T> decode(name: String): T = ApiJson.decodeFromString(text(name))

    @Test fun spend() {
        val s = decode<Spend>("spend")
        assertEquals(1L, s.version); assertEquals(550L, s.amountMinor); assertEquals("2026-10-04", s.occurredOn)
        assertEquals("android", s.source); assertEquals(550L, s.allocations?.first()?.amountMinor)
        assertNull(s.deletedAt)
        assertEquals("fx-1", SpendInput(s).sourceRecordId) // an edit resends what it doesn't show
    }

    @Test fun spendsPage() {
        val p = decode<Page<Spend>>("spends_page")
        assertEquals(3, p.items.size); assertNull(p.nextOffset)
        assertTrue(p.items.any { it.kind == "refund" })
    }

    @Test fun analysisByDayHasExactStringAggregatesAndNegativeNet() {
        val a = decode<Analysis>("analysis_by_day")
        assertTrue(a.comparison.isEmpty())
        assertEquals(-200L, a.current.first { it.key == "2026-10-03" }.net) // a refund-only day is negative; charts clamp it to zero
        assertEquals(1234L, a.current.first { it.key == "2026-10-02" }.expense)
        val series = Trend.series(a.current, "USD", "day", "2026-10-01", "2026-10-05")
        assertEquals(listOf(0L, 1234L, -200L, 550L, 0L), series.map { it.minor })
    }

    @Test fun analysisByCategory() {
        val a = decode<Analysis>("analysis_by_category")
        assertTrue(a.current.map { it.label }.containsAll(listOf("Coffee", "Uncategorized")))
        assertTrue(Breakdown.top(a.current, "USD").isNotEmpty())
    }

    @Test fun historyDimensionsBulkEvidenceExport() {
        val h = decode<Page<Change>>("history")
        assertEquals("spends_create", h.items.first().operation); assertTrue(h.items.first().actor.isNotEmpty())
        val d = decode<Page<Dimension>>("dimensions_page")
        assertEquals(listOf("BLUEBOTTLE"), d.items.first().aliases); assertEquals(1L, d.items.first().version)
        assertEquals(1, decode<SpendsResult>("bulk_result").items.size)
        assertTrue(decode<EvidenceRecord>("evidence").id.isNotEmpty())
        val e = decode<ExportResult>("export")
        assertTrue(e.csv.startsWith("id,version,occurred_on")); assertNull(e.nextOffset)
    }

    @Test fun engineErrorsAreClassified() {
        val c = FinanceApi.classify(409, text("error_conflict")) { it } as Api.Failed
        assertEquals("conflict", c.code); assertTrue(c.message.contains("Stale version"))
        assertEquals("invalid_input", (FinanceApi.classify(400, text("error_invalid")) { it } as Api.Failed).code)
    }

    @Test fun duplicateAnswersFromTheRealEngine() {
        // A source identity imported before, and a merchant name already taken: both are "duplicate", not "conflict".
        for (name in listOf("error_duplicate", "error_name_in_use")) {
            val f = FinanceApi.classify(409, text(name)) { it } as Api.Failed
            assertEquals(name, "duplicate", f.code)
            assertTrue(org.nighthawklabs.treasure.ingest.ImportSession.alreadyThere(f.code))
        }
    }
}
