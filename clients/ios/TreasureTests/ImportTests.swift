import XCTest
@testable import Treasure

final class ImportTests: XCTestCase {
    private var cal: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = .gmt; return c }
    private func opts(dayFirst: Bool = false, negativeIsSpend: Bool = true, currency: String = "USD") -> ParseOptions {
        ParseOptions(dayFirst: dayFirst, negativeIsSpend: negativeIsSpend, currency: currency,
                     today: cal.date(from: DateComponents(year: 2026, month: 10, day: 20))!, calendar: cal)
    }

    // MARK: Amounts

    func testAmountTokens() {
        func v(_ s: String) -> AmountParse.Value? { AmountParse.find(in: s).first?.value }
        XCTAssertEqual(v("Total $1,234.56")?.minor, 123_456)
        XCTAssertEqual(v("paid -12.34")?.negative, true)
        XCTAssertEqual(v("fee (12.34)")?.negative, true)
        XCTAssertEqual(v("12.34-")?.negative, true)
        XCTAssertEqual(v("12.34 CR")?.credit, true)
        XCTAssertEqual(v("12.34DR")?.debit, true)
        XCTAssertEqual(v("€ 5,50")?.minor, 550)
    }

    func testPlainNumbersAreNotMoney() {
        XCTAssertTrue(AmountParse.find(in: "Store 1234 Order 5678 2026 10/04").isEmpty)
        XCTAssertTrue(AmountParse.find(in: "10.04.2026").isEmpty)  // a dotted date, not 10.04
        XCTAssertEqual(AmountParse.find(in: "04 12.34").first?.value.minor, 1234)
    }

    func testLenientCells() {
        func m(_ s: String) -> Int64? { AmountParse.parseCell(s)?.minor }
        XCTAssertEqual(m("12"), 1200); XCTAssertEqual(m("12.5"), 1250); XCTAssertEqual(m("-1,234.50"), 123_450)
        XCTAssertEqual(m("1.234,50"), 123_450); XCTAssertEqual(m("1,234"), 123_400); XCTAssertEqual(m(""), nil)
        XCTAssertEqual(AmountParse.parseCell("(5.00)")?.negative, true)
    }

    // MARK: Dates

    func testDateFormats() {
        func d(_ s: String, dayFirst: Bool = false) -> String? { DateParse.find(in: s, options: opts(dayFirst: dayFirst))?.date }
        XCTAssertEqual(d("2026-10-04 purchase"), "2026-10-04")
        XCTAssertEqual(d("10/04/2026"), "2026-10-04")
        XCTAssertEqual(d("04/10/2026", dayFirst: true), "2026-10-04")
        XCTAssertEqual(d("25/10/2025"), "2025-10-25")          // 25 can only be a day
        XCTAssertEqual(d("10/04/26"), "2026-10-04")
        XCTAssertEqual(d("4 Oct 2026"), "2026-10-04")
        XCTAssertEqual(d("Oct 4, 2026"), "2026-10-04")
        XCTAssertEqual(d("31/02/2026", dayFirst: true), nil)   // not a real date
    }

    func testDateWithOcrSpacing() {
        XCTAssertEqual(DateParse.find(in: "10/04/ 2026 8:15 AM", options: opts())?.date, "2026-10-04")
        XCTAssertEqual(DateParse.find(in: "10 / 04 / 2026", options: opts())?.date, "2026-10-04")
    }

    func testMissingYearIsInferredAndNeverFarInTheFuture() {
        func d(_ s: String) -> String? { DateParse.find(in: s, options: opts())?.date }
        XCTAssertEqual(d("10/04 STARBUCKS"), "2026-10-04")
        XCTAssertEqual(d("12/28 STARBUCKS"), "2025-12-28")      // today is 20 Oct 2026: December is last year
    }

    // MARK: Receipts

    func testReceiptTotalDateAndMerchant() {
        let text = """
        BLUE BOTTLE COFFEE
        123 Main Street
        Tel 555-0100
        10/04/2026 8:15 AM
        Latte            5.50
        Croissant        4.25
        Subtotal         9.75
        Tax              0.85
        Total           $10.60
        VISA ****1234   10.60
        Cash            20.00
        """
        let r = ReceiptParser.parse(text, options: opts())
        XCTAssertEqual(r.count, 1)
        XCTAssertEqual(r[0].amountMinor, 1060)
        XCTAssertEqual(r[0].date, "2026-10-04")
        XCTAssertEqual(r[0].description, "BLUE BOTTLE COFFEE")
        XCTAssertEqual(r[0].kind, "expense")
    }

    func testReceiptWithoutTotalLabelUsesLargestPlainAmountButNotChangeOrCash() {
        let r = ReceiptParser.parse("Corner Shop\nBread 3.20\nMilk 1.80\nCash 10.00\nChange 5.00", options: opts())
        XCTAssertEqual(r.first?.amountMinor, 320)
    }

    func testReceiptCurrencyFromSymbol() {
        XCTAssertEqual(ReceiptParser.parse("Cafe Roma\nTotal €12,40", options: opts()).first?.currency, "EUR")
        XCTAssertEqual(ReceiptParser.parse("Tea House\nTotal ₹250.00", options: opts()).first?.currency, "INR")
        XCTAssertEqual(ReceiptParser.parse("Diner\nTotal $9.00", options: opts(currency: "CAD")).first?.currency, "CAD")
    }

    func testNoAmountMeansNoItem() { XCTAssertTrue(ReceiptParser.parse("Just words here", options: opts()).isEmpty) }

    // MARK: Statements

    private let bank = """
    ACME BANK  Statement period 10/01/2026 - 10/31/2026
    Date        Description                         Amount      Balance
    Opening balance                                            1,500.00
    10/02/2026  STARBUCKS #1234 SEATTLE WA          -5.50       1,494.50
    10/03/2026  PAYROLL ACME INC                    2,000.00    3,494.50
    10/04       AMAZON MKTP US*2K3                  -42.99      3,451.51
    10/05       REFUND AMAZON                       12.00       3,463.51
    10/06       ONLINE TRANSFER TO SAVINGS          -300.00     3,163.51
    Closing balance                                            3,163.51
    """

    func testBankStatement() {
        let o = opts(negativeIsSpend: StatementParser.detectNegativeIsSpend(bank, options: opts()))
        XCTAssertTrue(o.negativeIsSpend)
        let r = StatementParser.parse(bank, options: o)
        XCTAssertEqual(r.map(\.amountMinor), [550, 200_000, 4299, 1200, 30_000])   // balances are not read as amounts
        XCTAssertEqual(r.map(\.kind), ["expense", "transfer", "expense", "refund", "transfer"])
        XCTAssertEqual(r.map(\.include), [true, false, true, true, false])         // deposits and transfers are unchecked
        XCTAssertEqual(r[0].description, "STARBUCKS #1234 SEATTLE WA")
        XCTAssertEqual(r[2].date, "2026-10-04")
    }

    func testCardStatementChargesArePositive() {
        let card = """
        10/02 10/03 UBER *TRIP HELP.UBER.COM      18.40
        10/05 10/06 NETFLIX.COM                   15.49
        10/09 10/09 PAYMENT THANK YOU            -500.00
        10/11 10/12 WHOLEFDS #10234               64.20 
        """
        XCTAssertFalse(StatementParser.detectNegativeIsSpend(card, options: opts()))
        let r = StatementParser.parse(card, options: opts(negativeIsSpend: false))
        XCTAssertEqual(r.map(\.amountMinor), [1840, 1549, 50_000, 6420])
        XCTAssertEqual(r.map(\.kind), ["expense", "expense", "transfer", "expense"])
        XCTAssertEqual(r[0].description, "UBER *TRIP HELP.UBER.COM")   // the posting date beside the transaction date is dropped
    }

    func testCreditDebitSuffixesOverrideSign() {
        let r = StatementParser.parse("10/02/2026 SHOP 10.00 DR\n10/03/2026 REFUND SHOP 4.00 CR", options: opts())
        XCTAssertEqual(r.map(\.kind), ["expense", "refund"])
    }

    func testEuropeanAmountsAndDayFirstDates() {
        let r = StatementParser.parse("04/10/2026 BOULANGERIE -1.234,56", options: opts(dayFirst: true, currency: "EUR"))
        XCTAssertEqual(r.first?.amountMinor, 123_456)
        XCTAssertEqual(r.first?.date, "2026-10-04")
    }

    func testSummaryAndHeaderLinesAreSkipped() {
        let r = StatementParser.parse("Statement date 10/31/2026\nTotal fees 10/31/2026 12.00\n10/02/2026 SHOP -5.00", options: opts())
        XCTAssertEqual(r.map(\.description), ["SHOP"])
    }

    // MARK: CSV

    func testCSVQuotedFieldsAndSemicolons() {
        XCTAssertEqual(CSVParser.rows("a,b\n\"x, y\",\"he said \"\"hi\"\"\"\n"), [["a", "b"], ["x, y", "he said \"hi\""]])
        XCTAssertEqual(CSVParser.rows("a;b;c\n1;2;3"), [["a", "b", "c"], ["1", "2", "3"]])
    }

    func testCSVWithSignedAmountColumn() {
        let csv = "Date,Description,Amount\n2026-10-02,Coffee,-5.50\n2026-10-03,Salary,2000.00\n2026-10-04,\"Refund, Shop\",12.00\n"
        let r = CSVParser.items(csv, options: opts())!
        XCTAssertEqual(r.map(\.kind), ["expense", "transfer", "refund"])
        XCTAssertEqual(r.map(\.amountMinor), [550, 200_000, 1200])
        XCTAssertTrue(CSVParser.detectNegativeIsSpend(csv) == false)   // 2 positive vs 1 negative: reads as a card export
    }

    func testCSVWithDebitCreditColumns() {
        let csv = "Posted Date,Payee,Debit,Credit\n10/02/2026,Cafe,4.50,\n10/03/2026,Employer,,1000.00\n"
        let r = CSVParser.items(csv, options: opts())!
        XCTAssertEqual(r.map(\.kind), ["expense", "transfer"])
        XCTAssertEqual(r.map(\.amountMinor), [450, 100_000])
    }

    func testCSVWithoutKnownColumnsIsRejected() { XCTAssertNil(CSVParser.items("foo,bar\n1,2", options: opts())) }

    // MARK: Enrichment

    private func dim(_ id: String, _ name: String, _ aliases: [String] = []) -> Treasure.Dimension { Treasure.Dimension(id: id, name: name, aliases: aliases, version: 1) }
    private func existing(_ id: String, day: String, minor: Int64, merchant: String? = nil, category: String? = nil, desc: String? = nil) -> Spend {
        var s = Spend(id: id, version: 1, occurredOn: day, kind: "expense", amountMinor: minor, currency: "USD")
        s.merchantId = merchant; s.description = desc
        if let category { s.allocations = [Allocation(categoryId: category, amountMinor: minor)] }
        return s
    }
    private func item(_ desc: String, day: String = "2026-10-02", minor: Int64 = 550) -> ParsedItem {
        ParsedItem(date: day, description: desc, amountMinor: minor, kind: "expense", currency: "USD", sourceLine: desc)
    }

    func testNormalizeStripsNoise() {
        XCTAssertEqual(Enricher.normalize("SQ *BLUE BOTTLE #1234 OAKLAND CA"), "blue bottle oakland ca")
        XCTAssertEqual(Enricher.normalize("STARBUCKS STORE 05123 SEATTLE"), "starbucks store seattle")
        XCTAssertEqual(Enricher.cleanName("SQ *BLUE BOTTLE #1234 OAKLAND CA"), "Blue Bottle")
    }

    func testMerchantMatchByNameAliasAndLongestWins() {
        let e = Enricher(merchants: [dim("m1", "Starbucks"), dim("m2", "Amazon", ["AMZN MKTP US"]), dim("m3", "Amazon Prime")], spends: [])
        let r = e.enrich([item("STARBUCKS #1234 SEATTLE WA"), item("AMZN MKTP US*2K3"), item("AMAZON PRIME VIDEO"), item("RANDOM CAFE 77")])
        XCTAssertEqual(r.map(\.merchantId), ["m1", "m2", "m3", nil])
        XCTAssertEqual(r[3].merchantName, "Random Cafe")      // a new merchant would get this name
    }

    func testCategorySuggestionFromHistory() {
        let hist = [existing("a", day: "2026-09-01", minor: 1, merchant: "m1", category: "food"),
                    existing("b", day: "2026-09-02", minor: 2, merchant: "m1", category: "food"),
                    existing("c", day: "2026-09-03", minor: 3, merchant: "m1", category: "treats")]
        let e = Enricher(merchants: [dim("m1", "Starbucks")], spends: hist)
        XCTAssertEqual(e.enrich([item("STARBUCKS 123")]).first?.categoryId, "food")
    }

    func testLearnsFromEarlierImportedDescriptionsWithoutAMerchantName() {
        let hist = [existing("a", day: "2026-09-01", minor: 1, merchant: "m9", category: "fuel", desc: "SHELL OIL 5731")]
        let e = Enricher(merchants: [dim("m9", "Shell Gas")], spends: hist)
        let r = e.enrich([item("SHELL OIL 9912")]).first!
        XCTAssertEqual(r.merchantId, "m9"); XCTAssertEqual(r.categoryId, "fuel")
    }

    func testAnEntryWithNoMerchantOrNoteIsOnlyAHintAndStaysSelected() {
        // Same date and amount, but nothing says it is the same purchase: suggest, don't hide.
        let e = Enricher(merchants: [], spends: [existing("x", day: "2026-10-02", minor: 550)])
        let r = e.enrich([item("COFFEE")])
        XCTAssertNil(r[0].duplicateOf); XCTAssertEqual(r[0].possibleDuplicateOf, "x"); XCTAssertTrue(r[0].include)
    }

    func testFingerprintIsStableAndSeparatesRepeatedPurchases() {
        let a = Enricher(merchants: [], spends: []).enrich([item("COFFEE #1"), item("COFFEE #2")])
        let b = Enricher(merchants: [], spends: []).enrich([item("COFFEE #9"), item("COFFEE #3")])
        XCTAssertNotEqual(a[0].fingerprint, a[1].fingerprint)      // same coffee twice on one day stays two rows
        XCTAssertEqual(a.map(\.fingerprint), b.map(\.fingerprint)) // and re-importing yields the same keys
    }
}

final class ExtractionTests: XCTestCase {
    func testOCRFragmentsOnOneVisualRowAreJoinedLeftToRight() {
        // Vision boxes are normalized with the origin bottom-left: a larger y is higher on the page.
        let found: [(text: String, box: CGRect)] = [
            ("-5.50", CGRect(x: 0.80, y: 0.700, width: 0.1, height: 0.02)),
            ("10/02", CGRect(x: 0.05, y: 0.702, width: 0.1, height: 0.02)),
            ("STARBUCKS", CGRect(x: 0.20, y: 0.699, width: 0.3, height: 0.02)),
            ("Opening balance", CGRect(x: 0.05, y: 0.80, width: 0.3, height: 0.02)),
        ]
        XCTAssertEqual(TextExtractor.rows(found), ["Opening balance", "10/02  STARBUCKS  -5.50"])
    }

    func testOCRRowsParseLikeTheOriginalStatement() {
        let rows = TextExtractor.rows([("10/02/2026", CGRect(x: 0.05, y: 0.5, width: 0.2, height: 0.02)), ("COFFEE SHOP", CGRect(x: 0.3, y: 0.5, width: 0.3, height: 0.02)), ("-4.25", CGRect(x: 0.8, y: 0.5, width: 0.1, height: 0.02))])
        let items = StatementParser.parse(rows.joined(separator: "\n"), options: ParseOptions(dayFirst: false, negativeIsSpend: true, currency: "USD"))
        XCTAssertEqual(items.map(\.amountMinor), [425])
        XCTAssertEqual(items.first?.description, "COFFEE SHOP")
    }
}

/// The real on-device path: draw a document, then read it back with Vision / PDFKit and parse it.
final class EndToEndExtractionTests: XCTestCase {
    private func drawn(_ lines: [String], size: CGSize = CGSize(width: 900, height: 1200), fontSize: CGFloat = 34) -> UIImage {
        UIGraphicsImageRenderer(size: size).image { ctx in
            UIColor.white.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
            let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.monospacedSystemFont(ofSize: fontSize, weight: .regular), .foregroundColor: UIColor.black]
            for (i, l) in lines.enumerated() { (l as NSString).draw(at: CGPoint(x: 40, y: 40 + CGFloat(i) * (fontSize * 1.6)), withAttributes: attrs) }
        }
    }

    func testReceiptPhotoIsReadAndParsed() async throws {
        let image = drawn(["BLUE BOTTLE COFFEE", "123 Main Street", "10/04/2026 8:15 AM", "Latte 5.50", "Croissant 4.25", "Subtotal 9.75", "Tax 0.85", "Total $10.60"])
        let doc = await TextExtractor.extract(images: [image], name: "receipt")
        let o = ParseOptions(dayFirst: false, negativeIsSpend: true, currency: "USD")
        let items = ReceiptParser.parse(doc.text, options: o)
        XCTAssertEqual(items.first?.amountMinor, 1060, "OCR text was:\n\(doc.text)")
        XCTAssertEqual(items.first?.date, "2026-10-04")
        XCTAssertTrue(items.first?.description.uppercased().contains("BLUE BOTTLE") == true, "OCR text was:\n\(doc.text)")
    }

    func testTextPDFStatementUsesItsOwnTextLayer() async throws {
        let lines = ["ACME BANK Statement", "Date Description Amount Balance", "10/02/2026 STARBUCKS SEATTLE -5.50 1,494.50", "10/04/2026 AMAZON MKTP -42.99 1,451.51", "10/06/2026 SHELL OIL -38.20 1,413.31"]
        let pdfData = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792)).pdfData { ctx in
            ctx.beginPage()
            for (i, l) in lines.enumerated() { (l as NSString).draw(at: CGPoint(x: 40, y: 40 + CGFloat(i) * 24), withAttributes: [.font: UIFont.systemFont(ofSize: 12)]) }
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("stmt-\(UUID().uuidString).pdf")
        try pdfData.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let extracted = await TextExtractor.extract(url: url)
        let doc = try XCTUnwrap(extracted)
        let o = ParseOptions(dayFirst: false, negativeIsSpend: true, currency: "USD")
        let items = StatementParser.parse(doc.text, options: o)
        XCTAssertEqual(items.map(\.amountMinor), [550, 4299, 3820], "PDF text was:\n\(doc.text)")
        XCTAssertEqual(doc.mediaType, "application/pdf")
        XCTAssertEqual(doc.hash.count, 64)
    }

    func testScannedPDFFallsBackToOCR() async throws {
        let image = drawn(["10/02/2026 STARBUCKS SEATTLE -5.50", "10/04/2026 AMAZON MKTP -42.99", "10/06/2026 SHELL OIL -38.20"], size: CGSize(width: 1224, height: 1584), fontSize: 30)
        let pdfData = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792)).pdfData { ctx in
            ctx.beginPage(); image.draw(in: CGRect(x: 0, y: 0, width: 612, height: 792))   // an image, so the page has no text layer
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("scan-\(UUID().uuidString).pdf")
        try pdfData.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let extracted = await TextExtractor.extract(url: url)
        let doc = try XCTUnwrap(extracted)
        let items = StatementParser.parse(doc.text, options: ParseOptions(dayFirst: false, negativeIsSpend: true, currency: "USD"))
        XCTAssertEqual(items.map(\.amountMinor), [550, 4299, 3820], "OCR text was:\n\(doc.text)")
    }
}

/// Amounts are scaled by the currency's own decimal places: a yen has none, so "500" is 500 minor units, not 50,000.
final class CurrencyDigitsTests: XCTestCase {
    private func opts(_ currency: String, negativeIsSpend: Bool = true) -> ParseOptions {
        var c = Calendar(identifier: .gregorian); c.timeZone = .gmt
        return ParseOptions(dayFirst: false, negativeIsSpend: negativeIsSpend, currency: currency, today: c.date(from: DateComponents(year: 2026, month: 10, day: 20))!, calendar: c)
    }

    func testFractionDigitsPerCurrency() {
        XCTAssertEqual(AmountParse.fractionDigits("USD"), 2); XCTAssertEqual(AmountParse.fractionDigits("JPY"), 0)
        XCTAssertEqual(AmountParse.fractionDigits("KRW"), 0); XCTAssertEqual(AmountParse.fractionDigits("KWD"), 3)
    }

    func testYenCSVIsNotInflatedByAHundred() {
        let csv = "Date,Description,Amount\n2026-10-02,Ramen,-500\n2026-10-03,Train,\"-1,500\"\n"
        let r = CSVParser.items(csv, options: opts("JPY"))!
        XCTAssertEqual(r.map(\.amountMinor), [500, 1500])
        // The same file read as dollars is what used to happen for every currency.
        XCTAssertEqual(CSVParser.items(csv, options: opts("USD"))!.map(\.amountMinor), [50_000, 150_000])
    }

    func testCellsScaleToTheCurrency() {
        XCTAssertEqual(AmountParse.parseCell("1,500", digits: 0)?.minor, 1500)
        XCTAssertEqual(AmountParse.parseCell("1.500", digits: 0)?.minor, 1500)
        XCTAssertEqual(AmountParse.parseCell("12.345", digits: 3)?.minor, 12_345)
        XCTAssertEqual(AmountParse.parseCell("12.5", digits: 3)?.minor, 12_500)
        XCTAssertEqual(AmountParse.parseCell("12.5", digits: 2)?.minor, 1250)
    }

    func testYenStatementReadsTheTrailingAmountNotTheStoreNumber() {
        let text = "Date Description Amount Balance\n10/02/2026 SEVEN ELEVEN 1234 -500 12,345\n10/03/2026 RAMEN -1,200 11,145"
        let r = StatementParser.parse(text, options: opts("JPY"))
        XCTAssertEqual(r.map(\.amountMinor), [500, 1200])
        XCTAssertEqual(r.first?.description, "SEVEN ELEVEN 1234")
    }

    func testYenStatementWithoutABalanceColumn() {
        let r = StatementParser.parse("10/02/2026 SEVEN ELEVEN 1234 -500", options: opts("JPY"))
        XCTAssertEqual(r.map(\.amountMinor), [500])
    }

    func testYenReceiptIsDetectedFromTheSymbolAndNotScaled() {
        let r = ReceiptParser.parse("RAMEN ICHIRAN\n10/04/2026\nTotal ¥1,200", options: opts("USD"))
        XCTAssertEqual(r.first?.currency, "JPY"); XCTAssertEqual(r.first?.amountMinor, 1200)
    }

    func testCurrencyDetection() {
        XCTAssertEqual(CurrencyDetect.detect(in: "Total ¥500", default: "USD"), "JPY")
        XCTAssertEqual(CurrencyDetect.detect(in: "Amount EUR 12,40", default: "USD"), "EUR")
        XCTAssertEqual(CurrencyDetect.detect(in: "no hints here", default: "GBP"), "GBP")
        XCTAssertEqual(CurrencyDetect.detect(in: "Total $9.00", default: "CAD"), "CAD")
    }

    func testTwoDecimalCurrenciesAreUnchanged() {
        XCTAssertEqual(AmountParse.find(in: "STARBUCKS -5.50 1,494.50", digits: 2).map(\.value.minor), [550, 149_450])
    }
}


/// Which rows really are duplicates, and what makes a row's identity unique. Reproduces the reviewer's cases.
final class DuplicateTests: XCTestCase {
    private func dim(_ id: String, _ name: String) -> Treasure.Dimension { Treasure.Dimension(id: id, name: name, aliases: [], version: 1) }
    private func existing(_ id: String, kind: String = "expense", merchant: String? = nil, desc: String? = nil, source: String? = nil, record: String? = nil) -> Spend {
        var s = Spend(id: id, version: 1, occurredOn: "2026-10-02", kind: kind, amountMinor: 250, currency: "USD")
        s.merchantId = merchant; s.description = desc; s.source = source; s.sourceRecordId = record
        return s
    }
    private func item(_ desc: String, kind: String = "expense", externalId: String? = nil) -> ParsedItem {
        ParsedItem(date: "2026-10-02", description: desc, amountMinor: 250, kind: kind, currency: "USD", sourceLine: desc, externalId: externalId)
    }

    func testACoffeeIsNotADuplicateOfBusFareForTheSamePrice() {
        let e = Enricher(merchants: [dim("m1", "Starbucks")], spends: [existing("bus", desc: "Bus fare")])
        let r = e.enrich([item("STARBUCKS #1234")])
        XCTAssertNil(r[0].duplicateOf); XCTAssertNil(r[0].possibleDuplicateOf); XCTAssertTrue(r[0].include)
    }

    func testTheSameMerchantOnTheSameDayForTheSamePriceIsADuplicate() {
        let e = Enricher(merchants: [dim("m1", "Starbucks")], spends: [existing("coffee", merchant: "m1")])
        let r = e.enrich([item("STARBUCKS #1234")])
        XCTAssertEqual(r[0].duplicateOf, "coffee"); XCTAssertFalse(r[0].include)
    }

    func testAMatchingNoteAloneIsEnough() {
        let e = Enricher(merchants: [], spends: [existing("n", desc: "Starbucks latte")])
        XCTAssertEqual(e.enrich([item("STARBUCKS #77 SEATTLE")])[0].duplicateOf, "n")
    }

    func testARowAlreadyImportedIsRecognisedByItsSourceIdentity() {
        let probe = Enricher(merchants: [], spends: []).enrich([item("COFFEE SHOP")])[0].fingerprint
        let e = Enricher(merchants: [], spends: [existing("old", desc: "totally different words", source: "import", record: probe)])
        let r = e.enrich([item("COFFEE SHOP")])
        XCTAssertEqual(r[0].duplicateOf, "old"); XCTAssertFalse(r[0].include)
    }

    func testAnExpenseAndARefundOfTheSameThingDoNotShareAFingerprint() {
        let r = Enricher(merchants: [], spends: []).enrich([item("AMAZON MKTP", kind: "expense"), item("AMAZON MKTP", kind: "refund")])
        XCTAssertNotEqual(r[0].fingerprint, r[1].fingerprint)
    }

    func testTwoSeparateReceiptsForTheSameCoffeeAreTwoSpends() {
        let first = Enricher(merchants: [], spends: []).enrich([item("BLUE BOTTLE")], documentHash: "receipt-photo-1")[0]
        let second = Enricher(merchants: [], spends: []).enrich([item("BLUE BOTTLE")], documentHash: "receipt-photo-2")[0]
        XCTAssertNotEqual(first.fingerprint, second.fingerprint, "the second must not be skipped as 'already imported'")
        // Importing the very same receipt file again is still recognised.
        XCTAssertEqual(first.fingerprint, Enricher(merchants: [], spends: []).enrich([item("BLUE BOTTLE")], documentHash: "receipt-photo-1")[0].fingerprint)
    }

    func testOverlappingStatementsShareTheRowsTheyShare() {
        // A statement has no document hash, so the same row in two statements is the same transaction.
        let a = Enricher(merchants: [], spends: []).enrich([item("RAMEN SHOP")])[0]
        let b = Enricher(merchants: [], spends: []).enrich([item("RAMEN SHOP")])[0]
        XCTAssertEqual(a.fingerprint, b.fingerprint)
    }

    func testABankTransactionIdIsTheIdentityWhateverTheDescriptionSays() {
        let a = Enricher(merchants: [], spends: []).enrich([item("SQ *BLUE BOTTLE", externalId: "TXN-9001")])[0]
        let b = Enricher(merchants: [], spends: []).enrich([item("BLUE BOTTLE COFFEE OAKLAND", externalId: "TXN-9001")])[0]
        let c = Enricher(merchants: [], spends: []).enrich([item("BLUE BOTTLE COFFEE OAKLAND", externalId: "TXN-9002")])[0]
        XCTAssertEqual(a.fingerprint, b.fingerprint); XCTAssertNotEqual(a.fingerprint, c.fingerprint)
    }

    func testCSVReadsATransactionIdColumnButNotPaidIn() {
        var cal = Calendar(identifier: .gregorian); cal.timeZone = .gmt
        let o = ParseOptions(dayFirst: false, negativeIsSpend: true, currency: "USD", today: cal.date(from: DateComponents(year: 2026, month: 10, day: 20))!, calendar: cal)
        let withId = "Date,Transaction ID,Description,Amount\n2026-10-02,TXN-1,Coffee,-2.50\n"
        XCTAssertEqual(CSVParser.items(withId, options: o)?.first?.externalId, "TXN-1")
        let paidIn = "Date,Description,Paid out,Paid in\n2026-10-02,Coffee,2.50,\n"
        XCTAssertNil(CSVParser.items(paidIn, options: o)?.first?.externalId)
    }
}
