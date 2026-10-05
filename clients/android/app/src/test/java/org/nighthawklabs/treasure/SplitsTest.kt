package org.nighthawklabs.treasure

import org.junit.Assert.*
import org.junit.Test
import org.nighthawklabs.treasure.data.*
import java.util.Locale

class SplitsTest {
    private fun row(c: String?, m: Long) = SplitRow(c, m)

    @Test fun aValidSplitAddsUpExactly() {
        assertNull(Splits.problem(1000, listOf(row("a", 600), row("b", 400)), "USD"))
        assertEquals(listOf(600L, 400L), Splits.allocations(listOf(row("a", 600), row(null, 400))).map { it.amountMinor })
    }

    @Test fun messageSaysHowMuchIsLeftOrOver() {
        Locale.setDefault(Locale.US)
        assertEquals("$1.00 left to assign.", Splits.problem(1000, listOf(row("a", 600), row("b", 300)), "USD"))
        assertEquals("Over by $1.00.", Splits.problem(1000, listOf(row("a", 600), row("b", 500)), "USD"))
    }

    @Test fun everyShareNeedsAnAmountAndACategoryIsUsedOnce() {
        assertEquals("Each split needs an amount.", Splits.problem(1000, listOf(row("a", 1000), row("b", 0)), "USD"))
        assertEquals("Each category can only be used once.", Splits.problem(1000, listOf(row("a", 500), row("a", 500)), "USD"))
        assertEquals("Each category can only be used once.", Splits.problem(1000, listOf(row(null, 500), row(null, 500)), "USD"))
    }

    @Test fun aSingleLineIsNotASplit() {
        assertNull(Splits.problem(1000, listOf(row("a", 1)), "USD"))
        assertTrue(Splits.rows(listOf(Allocation("a", 5))).isEmpty())
    }

    @Test fun startingASplitOpensBalanced() {
        val rows = Splits.start("c1", 1000)
        assertEquals(listOf(500L, 500L), rows.map { it.amountMinor }); assertEquals("c1", rows[0].categoryId); assertNull(rows[1].categoryId)
        assertEquals(listOf(501L, 500L), Splits.start(null, 1001).map { it.amountMinor })
        assertEquals(0L, Splits.remaining(1001, Splits.start(null, 1001)))
    }

    @Test fun addingALineTakesWhatIsLeftAndNeverGoesNegative() {
        assertEquals(400L, Splits.adding(listOf(row("a", 600)), 1000).last().amountMinor)
        assertEquals(0L, Splits.adding(listOf(row("a", 1200)), 1000).last().amountMinor)
    }

    @Test fun amountsAreEditableAsPlainText() {
        assertEquals("12.50", Money.plain(1250, "USD")); assertEquals("1500", Money.plain(1500, "JPY"))
        assertEquals("1234567.89", Money.plain(123_456_789, "USD"))
        assertEquals(7005L, Money.parseMinor(Money.plain(7005, "USD"), "USD"))
    }

    @Test fun theFilterCanAskForRefundsOfOneExpense() {
        assertEquals("exp-1", SpendFilter(originalSpendId = "exp-1").input().originalSpendId)
    }
}

class DrillTest {
    private val w = Period.Window("2026-10-01", "2026-10-05", "2026-09-01", "2026-09-05")
    private fun row(k: String) = BreakdownRow(k, k, 1, 1.0)

    @Test fun tappingABreakdownRowNarrowsSpendsToThatGroupAndWindow() {
        val c = Drill.filter(row("cat-1"), Drill.Dimension.Category, w, "USD")!!
        assertEquals("cat-1", c.categoryId); assertEquals("2026-10-01", c.from); assertEquals("2026-10-05", c.to); assertEquals("USD", c.currency)
        assertTrue(Drill.filter(row("uncategorized"), Drill.Dimension.Category, w, "USD")!!.uncategorized)
        assertEquals("m1", Drill.filter(row("m1"), Drill.Dimension.Merchant, w, "USD")!!.merchantId)
        assertEquals(listOf("t1"), Drill.filter(row("t1"), Drill.Dimension.Tag, w, "USD")!!.tagIds)
        assertNull(Drill.filter(row("other"), Drill.Dimension.Category, w, "USD"))
        assertNull(Drill.filter(row("unknown"), Drill.Dimension.Merchant, w, "USD"))
        assertNull(Drill.filter(row("untagged"), Drill.Dimension.Tag, w, "USD"))
    }
}
