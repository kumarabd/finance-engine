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
        XCTAssertTrue(app.staticTexts["Trash is empty"].waitForExistence(timeout: 10), "restoring empties the Trash list")
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
}
