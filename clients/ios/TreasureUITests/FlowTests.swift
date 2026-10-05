import XCTest

/// Drives the real screens against a real local finance-engine (clients/dev/local-engine.sh start && seed). The app is launched
/// with `-dev-engine`, which skips Clerk. These are slow and need that engine running, so they are not part of the unit run.
final class FlowTests: XCTestCase {
    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-dev-engine", "http://127.0.0.1:18091", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
    }

    func shot(_ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a)
    }

    func openSpends() {
        app.tabBars.buttons["Spends"].tap()
        XCTAssertTrue(app.navigationBars["Spends"].waitForExistence(timeout: 10))
    }

    func testSpendsListShowsSeededSpendsWithTheirTags() {
        openSpends()
        XCTAssertTrue(app.staticTexts["Starbucks"].firstMatch.waitForExistence(timeout: 10), "seeded merchants should load from the engine")
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS '#work'")).firstMatch.exists, "a spend's tags are shown on its row")
        shot("spends-list")
    }

    // MARK: Helpers

    /// A row's combined accessibility label (title, subtitle, amount), found by the text of its title.
    func rowLabel(_ title: String) -> String? {
        app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch.label
    }

    func scrollTo(_ element: XCUIElement, max: Int = 6) {
        var n = 0
        while !element.exists && n < max { app.swipeUp(); n += 1 }
    }

    func menuItem(_ title: String) { app.navigationBars.buttons["More"].tap(); app.buttons[title].tap() }

    // MARK: Filter

    func testFilteringByTypeShowsOnlyRefundsAndAChipClearsIt() {
        openSpends()
        app.buttons["Filter"].tap()
        XCTAssertTrue(app.navigationBars["Filter"].waitForExistence(timeout: 5))
        shot("filter-sheet")
        app.buttons["Refund"].tap()
        app.buttons["Apply"].tap()

        XCTAssertTrue(app.staticTexts["Ride refund"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["Starbucks"].exists, "only refunds should remain")
        let chip = app.buttons["Remove filter Refunds"]
        XCTAssertTrue(chip.exists, "the active filter is shown as a chip")
        shot("filtered-refunds")

        chip.tap()
        XCTAssertTrue(app.staticTexts["Starbucks"].firstMatch.waitForExistence(timeout: 10), "removing the chip brings the full list back")
    }

    // MARK: Bulk

    func testBulkCategorizingTwoSpends() {
        openSpends()
        menuItem("Select")
        XCTAssertTrue(app.navigationBars["Select spends"].waitForExistence(timeout: 5))
        app.staticTexts["Blue Bottle"].firstMatch.tap()
        app.staticTexts["Whole Foods"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["2 selected"].waitForExistence(timeout: 5))
        shot("selection")
        app.buttons["Category"].tap()
        XCTAssertTrue(app.buttons["Dining"].waitForExistence(timeout: 5))
        shot("category-picker")
        app.buttons["Dining"].tap()

        // Selection ends and both rows now read Dining.
        XCTAssertTrue(app.navigationBars["Spends"].waitForExistence(timeout: 10))
        let predicate = NSPredicate(format: "label CONTAINS 'Blue Bottle' AND label CONTAINS 'Dining'")
        XCTAssertTrue(app.staticTexts.matching(predicate).firstMatch.waitForExistence(timeout: 10), "Blue Bottle should now be Dining")
        shot("after-bulk-categorize")
    }

    func testBulkDeleteUndoThenTrashRestore() {
        openSpends()
        menuItem("Select")
        app.staticTexts["Blue Bottle"].firstMatch.tap()
        app.staticTexts["Whole Foods"].firstMatch.tap()
        app.buttons["Delete"].tap()
        app.buttons["Delete"].firstMatch.tap()   // the confirmation dialog's destructive button
        XCTAssertTrue(app.staticTexts["2 spends deleted"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["Blue Bottle"].exists)
        shot("deleted-with-undo")

        app.buttons["Undo"].tap()
        XCTAssertTrue(app.staticTexts["Blue Bottle"].firstMatch.waitForExistence(timeout: 10), "Undo brings them back")

        // Delete one by one into Trash, then restore from there.
        menuItem("Select")
        app.staticTexts["Blue Bottle"].firstMatch.tap()
        app.buttons["Delete"].tap()
        app.buttons["Delete"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Spend deleted"].waitForExistence(timeout: 10))
        menuItem("Trash")
        XCTAssertTrue(app.navigationBars["Trash"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Blue Bottle"].firstMatch.waitForExistence(timeout: 10), "the deleted spend is in Trash")
        shot("trash")
        menuItem("Select")
        app.staticTexts["Blue Bottle"].firstMatch.tap()
        app.buttons["Restore"].tap()
        // The restored spend leaves Trash (other deleted spends from earlier runs may still be there).
        XCTAssertTrue(app.staticTexts["Blue Bottle"].firstMatch.waitForNonExistence(timeout: 10), "restoring takes it out of the Trash list")
        menuItem("Back to spends")
        XCTAssertTrue(app.staticTexts["Blue Bottle"].firstMatch.waitForExistence(timeout: 10))
    }

    // MARK: Tags in the editor

    func testTaggingASpendInTheEditor() {
        openSpends()
        app.buttons["Add spend"].tap()
        for key in ["1", "2", "5", "0"] { app.buttons[key].tap() }
        app.buttons["Add tags"].tap()
        XCTAssertTrue(app.navigationBars["Tags"].waitForExistence(timeout: 5))
        shot("tag-picker")
        app.buttons["trip"].tap()
        app.buttons["Done"].tap()
        XCTAssertTrue(app.buttons["Remove tag trip"].waitForExistence(timeout: 5), "the chosen tag appears as a chip")
        shot("editor-with-tag")
        app.buttons["Save"].tap()

        XCTAssertTrue(app.navigationBars["Spends"].waitForExistence(timeout: 10))
        let predicate = NSPredicate(format: "label CONTAINS '#trip' AND label CONTAINS '12.50'")
        XCTAssertTrue(app.staticTexts.matching(predicate).firstMatch.waitForExistence(timeout: 10), "the new $12.50 spend shows #trip")
    }

    // MARK: Splits and refunds

    /// Replace a field's text: tap its right end (a tap elsewhere puts the cursor mid-text), delete it all, type the new value.
    func replaceText(in field: XCUIElement, with text: String) {
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.5)).tap()
        let current = (field.value as? String) ?? ""
        if !current.isEmpty { field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count)) }
        field.typeText(text)
    }

    func testSplittingASpendAcrossTwoCategories() {
        openSpends()
        app.buttons["Add spend"].tap()
        for key in ["1", "0", "0", "0"] { app.buttons[key].tap() }   // $10.00
        app.buttons["Split across categories"].tap()
        XCTAssertTrue(app.navigationBars["Split"].waitForExistence(timeout: 5))
        shot("split-start")

        // It opens as two even halves ($5.00 + $5.00). Choose the categories, then make it $6.00 + $4.00.
        app.buttons["No category"].firstMatch.tap(); app.buttons["Coffee"].tap()
        app.buttons["No category"].firstMatch.tap(); app.buttons["Groceries"].tap()
        // The sheet's amount fields (the editor's own fields are still in the hierarchy behind it).
        let amounts = app.textFields.matching(NSPredicate(format: "placeholderValue == '0'"))
        replaceText(in: amounts.element(boundBy: 0), with: "6.00")
        replaceText(in: amounts.element(boundBy: 1), with: "4.00")
        XCTAssertTrue(app.staticTexts["Adds up"].waitForExistence(timeout: 5), "a balanced split says so")
        shot("split-balanced")
        app.buttons["Done"].tap()
        app.buttons["Save"].tap()

        XCTAssertTrue(app.navigationBars["Spends"].waitForExistence(timeout: 10))
        let predicate = NSPredicate(format: "label CONTAINS 'Coffee, Groceries' AND label CONTAINS '10.00'")
        XCTAssertTrue(app.staticTexts.matching(predicate).firstMatch.waitForExistence(timeout: 10), "the row shows both categories")
    }

    func testAnUnbalancedSplitCannotBeClosedAsIfItWereFine() {
        openSpends()
        app.buttons["Add spend"].tap()
        for key in ["1", "0", "0", "0"] { app.buttons[key].tap() }   // $10.00
        app.buttons["Split across categories"].tap()
        XCTAssertTrue(app.navigationBars["Split"].waitForExistence(timeout: 5))
        // Two uncategorized halves are one category twice: not allowed.
        XCTAssertTrue(app.staticTexts["Each category can only be used once."].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Done"].isEnabled)

        app.buttons["No category"].firstMatch.tap(); app.buttons["Coffee"].tap()
        app.buttons["No category"].firstMatch.tap(); app.buttons["Groceries"].tap()
        XCTAssertTrue(app.staticTexts["Adds up"].waitForExistence(timeout: 5), "even halves with two categories are valid")
        XCTAssertTrue(app.buttons["Done"].isEnabled)

        let amounts = app.textFields.matching(NSPredicate(format: "placeholderValue == '0'"))
        replaceText(in: amounts.element(boundBy: 0), with: "3.00")   // 3.00 + 5.00 of 10.00
        XCTAssertTrue(app.staticTexts["$2.00 left to assign."].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Done"].isEnabled, "an unbalanced split cannot be closed as if it were fine")
        shot("split-unbalanced")
    }

    func testLinkingARefundToItsExpense() {
        openSpends()
        app.buttons["Add spend"].tap()
        app.buttons["Refund"].tap()
        for key in ["5", "0", "0"] { app.buttons[key].tap() }   // $5.00
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Refund of'")).firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Refund of"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS 'Whole Foods'")).firstMatch.waitForExistence(timeout: 10), "only expenses are offered")
        shot("refund-picker")
        app.buttons.matching(NSPredicate(format: "label CONTAINS 'Whole Foods'")).firstMatch.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS 'Whole Foods'")).firstMatch.waitForExistence(timeout: 5), "the chosen expense is shown on the refund row")
        app.buttons["Save"].tap()
        XCTAssertTrue(app.navigationBars["Spends"].waitForExistence(timeout: 10))

        // The expense now shows what came back.
        app.staticTexts["Whole Foods"].firstMatch.tap()
        // Section headers are drawn in capitals, so match without regard to case.
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label ==[c] 'refunds'")).firstMatch.waitForExistence(timeout: 10), "an expense lists its refunds")
        XCTAssertTrue(app.staticTexts["Net after refunds"].exists)
        shot("expense-with-refund")
    }

    func testAttachingAndDetachingAReceiptOnASpend() {
        openSpends()
        app.staticTexts["Starbucks"].firstMatch.tap()
        let attach = app.buttons["Attach a receipt or document"]
        scrollTo(attach); attach.tap()
        app.buttons["New document"].tap()
        let title = app.textFields["Title (e.g. Costco receipt)"]
        XCTAssertTrue(title.waitForExistence(timeout: 5)); title.tap(); title.typeText("Latte receipt")
        let ref = app.textFields["Where it lives (link or file name)"]; ref.tap(); ref.typeText("photos/latte.jpg")
        app.buttons["Save"].tap()
        let doc = app.staticTexts["Latte receipt"]
        scrollTo(doc)
        XCTAssertTrue(doc.waitForExistence(timeout: 10), "the new document is attached to the spend")
        shot("evidence-attached")
        doc.swipeLeft()
        app.buttons["Detach"].tap()
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: doc); waitForExpectations(timeout: 10)
    }

    func testADeletedTagCanBeFoundAndRestored() {
        app.tabBars.buttons["More"].tap()
        app.buttons["Tags"].tap()
        app.navigationBars.buttons["Add tag"].tap()
        let name = "temp-\(Int.random(in: 1000...9999))"
        let field = app.alerts.textFields["Name"]; XCTAssertTrue(field.waitForExistence(timeout: 5)); field.typeText(name)
        app.alerts.buttons["Save"].tap()
        let row = app.staticTexts[name]
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        row.swipeLeft(); app.buttons["Delete"].tap()
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: row); waitForExpectations(timeout: 10)

        app.buttons["Deleted"].tap()
        XCTAssertTrue(row.waitForExistence(timeout: 10), "the deleted tag is listed under Deleted")
        shot("organize-deleted")
        row.swipeLeft(); app.buttons["Restore"].tap()
        expectation(for: gone, evaluatedWith: row); waitForExpectations(timeout: 10)
        app.buttons["Active"].tap()
        XCTAssertTrue(row.waitForExistence(timeout: 10), "restoring brings it back")
    }

    func testTappingAnInsightsRowOpensThoseSpends() {
        app.tabBars.buttons["Insights"].tap()
        XCTAssertTrue(app.navigationBars["Insights"].waitForExistence(timeout: 10))
        let row = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Coffee'")).firstMatch
        scrollTo(row, max: 8)
        XCTAssertTrue(row.waitForExistence(timeout: 10), "the category breakdown lists Coffee")
        shot("insights-breakdown")
        row.tap()
        XCTAssertTrue(app.navigationBars["Spends"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Remove filter Coffee"].waitForExistence(timeout: 10), "the category is now a filter chip")
        shot("insights-drilldown")
    }
}
