package org.nighthawklabs.treasure

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Drives the real screens against a real local finance-engine (clients/dev/local-engine.sh start && seed). The app is launched
 * with the `dev_engine` extra, which skips Clerk; the emulator reaches the host's engine at 10.0.2.2. Slow, and needs that engine
 * running, so these are not part of the unit run.
 */
class FlowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun launch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).putExtra("dev_engine", "http://10.0.2.2:18091"))
    }

    @After fun close() { scenario?.close() }

    private fun waitForText(text: String, timeoutMs: Long = 15_000) =
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun waitGone(text: String, timeoutMs: Long = 15_000) =
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty() }
    private fun row(text: String) = rule.onAllNodes(hasTestTag("spend-row") and hasText(text, substring = true)).onFirst()
    private fun openSpends() { waitForText("Spends"); rule.onNodeWithText("Spends").performClick(); waitForText("Starbucks") }
    private fun overflow(item: String) { rule.onNodeWithContentDescription("More").performClick(); rule.onNodeWithText(item).performClick() }

    @Test fun spendsListShowsSeededSpendsWithTheirTags() {
        openSpends()
        waitForText("#work")
    }

    @Test fun filteringByTypeShowsOnlyRefundsAndAChipClearsIt() {
        openSpends()
        rule.onNodeWithTag("filter-button").performClick()
        rule.onNodeWithTag("kind-refund").performClick()
        rule.onAllNodesWithText("Apply").onFirst().performClick()
        waitForText("Ride refund")
        waitGone("Starbucks")
        rule.onNodeWithContentDescription("Remove filter Refunds").assertExists()
        rule.onNodeWithContentDescription("Remove filter Refunds").performClick()
        waitForText("Starbucks")
    }

    @Test fun bulkCategorizingTwoSpends() {
        openSpends()
        row("Blue Bottle").performTouchInput { longClick() }
        waitForText("1 selected")
        row("Whole Foods").performClick()
        waitForText("2 selected")
        rule.onNodeWithTag("bulk-category").performClick()
        rule.onNodeWithTag("pick-Groceries").assertExists()
        rule.onNodeWithTag("pick-Transport").performClick()
        waitGone("selected")
        waitForText("Transport")
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("spend-row") and hasText("Blue Bottle", substring = true) and hasText("Transport", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun bulkDeleteUndoThenTrashRestore() {
        openSpends()
        overflow("Select")
        row("Uber").performClick()
        row("Starbucks").performClick()
        waitForText("2 selected")
        rule.onNodeWithTag("bulk-delete").performClick()
        rule.onNodeWithTag("confirm-delete").performClick()
        waitForText("2 spends deleted")
        rule.onNodeWithText("Undo").performClick()
        waitForText("Uber")

        overflow("Select")
        row("Uber").performClick()
        rule.onNodeWithTag("bulk-delete").performClick()
        rule.onNodeWithTag("confirm-delete").performClick()
        waitForText("Spend deleted")
        overflow("Trash")
        waitForText("Trash")
        waitForText("Uber")
        overflow("Select")
        row("Uber").performClick()
        rule.onNodeWithTag("bulk-restore").performClick()
        // The restored spend leaves Trash (other deleted spends from earlier runs may still be there).
        try { rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("spend-row") and hasText("Uber", substring = true)).fetchSemanticsNodes().isEmpty() } }
        catch (e: Throwable) { rule.onRoot().printToLog("FLOWDBG"); throw e }
        overflow("Back to spends")
        waitForText("Uber")
    }

    @Test fun taggingASpendInTheEditor() {
        openSpends()
        rule.onNodeWithContentDescription("Add spend").performClick()
        listOf("1", "2", "5", "0").forEach { rule.onNodeWithTag("key-$it").performClick() }
        rule.onNodeWithTag("add-tags").performScrollTo().performClick()
        rule.onNodeWithTag("pick-gift").performClick()
        rule.onAllNodesWithText("Done").onFirst().performClick()
        rule.onNodeWithContentDescription("Remove tag gift").assertExists()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("spend-row") and hasText("#gift", substring = true) and hasText("12.50", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun splittingASpendAcrossTwoCategories() {
        openSpends()
        rule.onNodeWithContentDescription("Add spend").performClick()
        listOf("1", "0", "0", "0").forEach { rule.onNodeWithTag("key-$it").performClick() }
        rule.onNodeWithTag("split-start").performScrollTo().performClick()
        rule.onAllNodesWithTag("split-category").onFirst().performClick()
        rule.onNodeWithTag("pick-Coffee").performClick()
        rule.onAllNodesWithTag("split-category").onLast().performClick()
        rule.onNodeWithTag("pick-Groceries").performClick()
        rule.onAllNodesWithTag("split-amount").onFirst().performTextReplacement("6.00")
        rule.onAllNodesWithTag("split-amount").onLast().performTextReplacement("4.00")
        waitForText("Adds up")
        rule.onNodeWithTag("split-done").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("spend-row") and hasText("Coffee", substring = true) and hasText("Groceries", substring = true) and hasText("10.00", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun anUnbalancedSplitCannotBeSaved() {
        openSpends()
        rule.onNodeWithContentDescription("Add spend").performClick()
        listOf("1", "0", "0", "0").forEach { rule.onNodeWithTag("key-$it").performClick() }
        rule.onNodeWithTag("split-start").performScrollTo().performClick()
        rule.onAllNodesWithTag("split-category").onFirst().performClick()
        rule.onNodeWithTag("pick-Coffee").performClick()
        rule.onAllNodesWithTag("split-category").onLast().performClick()
        rule.onNodeWithTag("pick-Groceries").performClick()
        rule.onAllNodesWithTag("split-amount").onFirst().performTextReplacement("7.00")
        waitForText("Over by")
        rule.onNodeWithTag("split-done").assertIsNotEnabled()
    }

    @Test fun attachingAndDetachingAReceiptOnASpend() {
        openSpends()
        row("Starbucks").performClick()
        rule.onNodeWithTag("attach-document").performScrollTo().performClick()
        rule.onNodeWithTag("new-document").performClick()
        rule.onNodeWithTag("evidence-title").performTextInput("Latte receipt")
        rule.onNodeWithTag("evidence-ref").performTextInput("photos/latte.jpg")
        rule.onNodeWithTag("evidence-save").performClick()
        waitForText("Latte receipt")
        rule.onNodeWithText("Detach").performScrollTo().performClick()
        waitGone("Latte receipt")
    }

    @Test fun aDeletedTagCanBeFoundAndRestored() {
        waitForText("Spends"); rule.onAllNodesWithText("More").onFirst().performClick()
        waitForText("Tags"); rule.onNodeWithText("Tags").performClick()
        val name = "temp-" + (1000..9999).random()
        rule.onNodeWithContentDescription("Add tag").performClick()
        rule.onNode(hasSetTextAction()).performTextInput(name)
        rule.onNodeWithText("Save").performClick()
        waitForText(name)
        rule.onNodeWithContentDescription("Options for $name").performClick()
        rule.onNodeWithText("Delete").performClick()
        waitGone(name)
        rule.onNodeWithText("Deleted").performClick()
        waitForText(name)
        rule.onNodeWithContentDescription("Options for $name").performClick()
        rule.onNodeWithText("Restore").performClick()
        waitGone(name)
        rule.onNodeWithText("Active").performClick()
        waitForText(name)
    }

    @Test fun tappingAnInsightsRowOpensThoseSpends() {
        waitForText("Spends"); rule.onAllNodesWithText("Insights").onFirst().performClick()
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("breakdown-Coffee")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("breakdown-Coffee").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Remove filter Coffee").assertExists()
    }
}
