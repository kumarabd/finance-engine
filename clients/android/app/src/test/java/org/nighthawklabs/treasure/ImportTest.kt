package org.nighthawklabs.treasure

import org.junit.Assert.*
import org.junit.Test
import org.nighthawklabs.treasure.data.Allocation
import org.nighthawklabs.treasure.data.Dimension
import org.nighthawklabs.treasure.data.Spend
import org.nighthawklabs.treasure.ingest.*
import java.time.LocalDate

private val TODAY = LocalDate.of(2026, 10, 20)
private fun opts(dayFirst: Boolean = false, negativeIsSpend: Boolean = true, currency: String = "USD") = ParseOptions(dayFirst, negativeIsSpend, currency, TODAY)

class AmountAndDateTest {
    @Test fun amountTokens() {
        fun v(s: String) = AmountParse.find(s).firstOrNull()?.value
        assertEquals(123_456L, v("Total $1,234.56")?.minor)
        assertEquals(true, v("paid -12.34")?.negative)
        assertEquals(true, v("fee (12.34)")?.negative)
        assertEquals(true, v("12.34-")?.negative)
        assertEquals(true, v("12.34 CR")?.credit)
        assertEquals(true, v("12.34DR")?.debit)
        assertEquals(550L, v("€ 5,50")?.minor)
    }

    @Test fun plainNumbersAreNotMoney() {
        assertTrue(AmountParse.find("Store 1234 Order 5678 2026 10/04").isEmpty())
        assertTrue(AmountParse.find("10.04.2026").isEmpty()) // a dotted date, not 10.04
        assertEquals(1234L, AmountParse.find("04 12.34").first().value.minor)
    }

    @Test fun lenientCells() {
        fun m(s: String) = AmountParse.parseCell(s)?.minor
        assertEquals(1200L, m("12")); assertEquals(1250L, m("12.5")); assertEquals(123_450L, m("-1,234.50"))
        assertEquals(123_450L, m("1.234,50")); assertEquals(123_400L, m("1,234")); assertNull(m(""))
        assertEquals(true, AmountParse.parseCell("(5.00)")?.negative)
    }

    @Test fun dateFormats() {
        fun d(s: String, dayFirst: Boolean = false) = DateParse.find(s, opts(dayFirst))?.date
        assertEquals("2026-10-04", d("2026-10-04 purchase"))
        assertEquals("2026-10-04", d("10/04/2026"))
        assertEquals("2026-10-04", d("04/10/2026", dayFirst = true))
        assertEquals("2025-10-25", d("25/10/2025"))          // 25 can only be a day
        assertEquals("2026-10-04", d("10/04/26"))
        assertEquals("2026-10-04", d("4 Oct 2026"))
        assertEquals("2026-10-04", d("Oct 4, 2026"))
        assertNull(d("31/02/2026", dayFirst = true))          // not a real date
    }

    @Test fun dateWithOcrSpacing() {
        assertEquals("2026-10-04", DateParse.find("10/04/ 2026 8:15 AM", opts())?.date)
        assertEquals("2026-10-04", DateParse.find("10 / 04 / 2026", opts())?.date)
    }

    @Test fun missingYearIsInferredAndNeverFarInTheFuture() {
        assertEquals("2026-10-04", DateParse.find("10/04 STARBUCKS", opts())?.date)
        assertEquals("2025-12-28", DateParse.find("12/28 STARBUCKS", opts())?.date) // today is 20 Oct 2026: December is last year
    }
}

class ReceiptTest {
    @Test fun totalDateAndMerchant() {
        val text = """
            BLUE BOTTLE COFFEE
            123 Main Street
            Tel 555-0100
            10/04/2026 8:15 AM
            Latte            5.50
            Croissant        4.25
            Subtotal         9.75
            Tax              0.85
            Total           ${'$'}10.60
            VISA ****1234   10.60
            Cash            20.00
        """.trimIndent()
        val r = ReceiptParser.parse(text, opts())
        assertEquals(1, r.size)
        assertEquals(1060L, r[0].amountMinor)
        assertEquals("2026-10-04", r[0].date)
        assertEquals("BLUE BOTTLE COFFEE", r[0].description)
        assertEquals("expense", r[0].kind)
    }

    @Test fun noTotalLabelUsesLargestPlainAmountButNotChangeOrCash() {
        assertEquals(320L, ReceiptParser.parse("Corner Shop\nBread 3.20\nMilk 1.80\nCash 10.00\nChange 5.00", opts()).first().amountMinor)
    }

    @Test fun currencyFromSymbol() {
        assertEquals("EUR", ReceiptParser.parse("Cafe Roma\nTotal €12,40", opts()).first().currency)
        assertEquals("INR", ReceiptParser.parse("Tea House\nTotal ₹250.00", opts()).first().currency)
        assertEquals("CAD", ReceiptParser.parse("Diner\nTotal ${'$'}9.00", opts(currency = "CAD")).first().currency)
    }

    @Test fun noAmountMeansNoItem() { assertTrue(ReceiptParser.parse("Just words here", opts()).isEmpty()) }
}

class StatementTest {
    private val bank = """
        ACME BANK  Statement period 10/01/2026 - 10/31/2026
        Date        Description                         Amount      Balance
        Opening balance                                            1,500.00
        10/02/2026  STARBUCKS #1234 SEATTLE WA          -5.50       1,494.50
        10/03/2026  PAYROLL ACME INC                    2,000.00    3,494.50
        10/04       AMAZON MKTP US*2K3                  -42.99      3,451.51
        10/05       REFUND AMAZON                       12.00       3,463.51
        10/06       ONLINE TRANSFER TO SAVINGS          -300.00     3,163.51
        Closing balance                                            3,163.51
    """.trimIndent()

    @Test fun bankStatement() {
        val neg = StatementParser.detectNegativeIsSpend(bank, opts())
        assertTrue(neg)
        val r = StatementParser.parse(bank, opts(negativeIsSpend = neg))
        assertEquals(listOf(550L, 200_000L, 4299L, 1200L, 30_000L), r.map { it.amountMinor }) // balances are not read as amounts
        assertEquals(listOf("expense", "transfer", "expense", "refund", "transfer"), r.map { it.kind })
        assertEquals(listOf(true, false, true, true, false), r.map { it.include })            // deposits and transfers are unchecked
        assertEquals("STARBUCKS #1234 SEATTLE WA", r[0].description)
        assertEquals("2026-10-04", r[2].date)
    }

    @Test fun cardStatementChargesArePositive() {
        val card = """
            10/02 10/03 UBER *TRIP HELP.UBER.COM      18.40
            10/05 10/06 NETFLIX.COM                   15.49
            10/09 10/09 PAYMENT THANK YOU            -500.00
            10/11 10/12 WHOLEFDS #10234               64.20 
        """.trimIndent()
        assertFalse(StatementParser.detectNegativeIsSpend(card, opts()))
        val r = StatementParser.parse(card, opts(negativeIsSpend = false))
        assertEquals(listOf(1840L, 1549L, 50_000L, 6420L), r.map { it.amountMinor })
        assertEquals(listOf("expense", "expense", "transfer", "expense"), r.map { it.kind })
        assertEquals("UBER *TRIP HELP.UBER.COM", r[0].description) // the posting date beside the transaction date is dropped
    }

    @Test fun creditDebitSuffixesOverrideSign() {
        val r = StatementParser.parse("10/02/2026 SHOP 10.00 DR\n10/03/2026 REFUND SHOP 4.00 CR", opts())
        assertEquals(listOf("expense", "refund"), r.map { it.kind })
    }

    @Test fun europeanAmountsAndDayFirstDates() {
        val r = StatementParser.parse("04/10/2026 BOULANGERIE -1.234,56", opts(dayFirst = true, currency = "EUR"))
        assertEquals(123_456L, r.first().amountMinor)
        assertEquals("2026-10-04", r.first().date)
    }

    @Test fun summaryAndHeaderLinesAreSkipped() {
        val r = StatementParser.parse("Statement date 10/31/2026\nTotal fees 10/31/2026 12.00\n10/02/2026 SHOP -5.00", opts())
        assertEquals(listOf("SHOP"), r.map { it.description })
    }
}

class CsvTest {
    @Test fun quotedFieldsAndSemicolons() {
        assertEquals(listOf(listOf("a", "b"), listOf("x, y", "he said \"hi\"")), CSVParser.rows("a,b\n\"x, y\",\"he said \"\"hi\"\"\"\n"))
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), CSVParser.rows("a;b;c\n1;2;3"))
    }

    @Test fun signedAmountColumn() {
        val csv = "Date,Description,Amount\n2026-10-02,Coffee,-5.50\n2026-10-03,Salary,2000.00\n2026-10-04,\"Refund, Shop\",12.00\n"
        val r = CSVParser.items(csv, opts())!!
        assertEquals(listOf("expense", "transfer", "refund"), r.map { it.kind })
        assertEquals(listOf(550L, 200_000L, 1200L), r.map { it.amountMinor })
        assertFalse(CSVParser.detectNegativeIsSpend(csv)) // 2 positive vs 1 negative: reads as a card export
    }

    @Test fun debitCreditColumns() {
        val csv = "Posted Date,Payee,Debit,Credit\n10/02/2026,Cafe,4.50,\n10/03/2026,Employer,,1000.00\n"
        val r = CSVParser.items(csv, opts())!!
        assertEquals(listOf("expense", "transfer"), r.map { it.kind })
        assertEquals(listOf(450L, 100_000L), r.map { it.amountMinor })
    }

    @Test fun unknownColumnsAreRejected() { assertNull(CSVParser.items("foo,bar\n1,2", opts())) }
}

class EnrichTest {
    private fun dim(id: String, name: String, aliases: List<String> = emptyList()) = Dimension(id, name, aliases, 1)
    private fun existing(id: String, day: String, minor: Long, merchant: String? = null, category: String? = null, desc: String? = null) =
        Spend(id = id, version = 1, occurredOn = day, kind = "expense", amountMinor = minor, currency = "USD", merchantId = merchant, description = desc,
            allocations = category?.let { listOf(Allocation(it, minor)) })
    private fun item(desc: String, day: String = "2026-10-02", minor: Long = 550) = ParsedItem(date = day, description = desc, amountMinor = minor, kind = "expense", currency = "USD", sourceLine = desc)

    @Test fun normalizeStripsNoise() {
        assertEquals("blue bottle oakland ca", Enricher.normalize("SQ *BLUE BOTTLE #1234 OAKLAND CA"))
        assertEquals("starbucks store seattle", Enricher.normalize("STARBUCKS STORE 05123 SEATTLE"))
        assertEquals("Blue Bottle", Enricher.cleanName("SQ *BLUE BOTTLE #1234 OAKLAND CA"))
    }

    @Test fun merchantMatchByNameAliasAndLongestWins() {
        val e = Enricher(listOf(dim("m1", "Starbucks"), dim("m2", "Amazon", listOf("AMZN MKTP US")), dim("m3", "Amazon Prime")), emptyList())
        val r = e.enrich(listOf(item("STARBUCKS #1234 SEATTLE WA"), item("AMZN MKTP US*2K3"), item("AMAZON PRIME VIDEO"), item("RANDOM CAFE 77")))
        assertEquals(listOf("m1", "m2", "m3", null), r.map { it.merchantId })
        assertEquals("Random Cafe", r[3].merchantName) // a new merchant would get this name
    }

    @Test fun categorySuggestionFromHistory() {
        val hist = listOf(existing("a", "2026-09-01", 1, "m1", "food"), existing("b", "2026-09-02", 2, "m1", "food"), existing("c", "2026-09-03", 3, "m1", "treats"))
        assertEquals("food", Enricher(listOf(dim("m1", "Starbucks")), hist).enrich(listOf(item("STARBUCKS 123"))).first().categoryId)
    }

    @Test fun learnsFromEarlierImportedDescriptionsWithoutAMerchantName() {
        val hist = listOf(existing("a", "2026-09-01", 1, "m9", "fuel", "SHELL OIL 5731"))
        val r = Enricher(listOf(dim("m9", "Shell Gas")), hist).enrich(listOf(item("SHELL OIL 9912"))).first()
        assertEquals("m9", r.merchantId); assertEquals("fuel", r.categoryId)
    }

    @Test fun anEntryWithNoMerchantOrNoteIsOnlyAHintAndStaysSelected() {
        // Same date and amount, but nothing says it is the same purchase: suggest, don't hide.
        val r = Enricher(emptyList(), listOf(existing("x", "2026-10-02", 550))).enrich(listOf(item("COFFEE")))
        assertNull(r[0].duplicateOf); assertEquals("x", r[0].possibleDuplicateOf); assertTrue(r[0].include)
    }

    @Test fun fingerprintIsStableAndSeparatesRepeatedPurchases() {
        val a = Enricher(emptyList(), emptyList()).enrich(listOf(item("COFFEE #1"), item("COFFEE #2")))
        val b = Enricher(emptyList(), emptyList()).enrich(listOf(item("COFFEE #9"), item("COFFEE #3")))
        assertNotEquals(a[0].fingerprint, a[1].fingerprint) // same coffee twice on one day stays two rows
        assertEquals(a.map { it.fingerprint }, b.map { it.fingerprint }) // and re-importing yields the same keys
    }
}

/** Amounts are scaled by the currency's own decimal places: a yen has none, so "500" is 500 minor units, not 50,000. */
class CurrencyDigitsTest {
    private fun o(currency: String) = ParseOptions(false, true, currency, TODAY)

    @Test fun fractionDigitsPerCurrency() {
        assertEquals(2, AmountParse.fractionDigits("USD")); assertEquals(0, AmountParse.fractionDigits("JPY"))
        assertEquals(0, AmountParse.fractionDigits("KRW")); assertEquals(3, AmountParse.fractionDigits("KWD"))
    }

    @Test fun yenCsvIsNotInflatedByAHundred() {
        val csv = "Date,Description,Amount\n2026-10-02,Ramen,-500\n2026-10-03,Train,\"-1,500\"\n"
        assertEquals(listOf(500L, 1500L), CSVParser.items(csv, o("JPY"))!!.map { it.amountMinor })
        assertEquals(listOf(50_000L, 150_000L), CSVParser.items(csv, o("USD"))!!.map { it.amountMinor }) // what used to happen for every currency
    }

    @Test fun cellsScaleToTheCurrency() {
        assertEquals(1500L, AmountParse.parseCell("1,500", 0)?.minor)
        assertEquals(1500L, AmountParse.parseCell("1.500", 0)?.minor)
        assertEquals(12_345L, AmountParse.parseCell("12.345", 3)?.minor)
        assertEquals(12_500L, AmountParse.parseCell("12.5", 3)?.minor)
        assertEquals(1250L, AmountParse.parseCell("12.5", 2)?.minor)
    }

    @Test fun yenStatementReadsTheTrailingAmountNotTheStoreNumber() {
        val text = "Date Description Amount Balance\n10/02/2026 SEVEN ELEVEN 1234 -500 12,345\n10/03/2026 RAMEN -1,200 11,145"
        val r = StatementParser.parse(text, o("JPY"))
        assertEquals(listOf(500L, 1200L), r.map { it.amountMinor })
        assertEquals("SEVEN ELEVEN 1234", r.first().description)
    }

    @Test fun yenStatementWithoutABalanceColumn() {
        assertEquals(listOf(500L), StatementParser.parse("10/02/2026 SEVEN ELEVEN 1234 -500", o("JPY")).map { it.amountMinor })
    }

    @Test fun yenReceiptIsDetectedFromTheSymbolAndNotScaled() {
        val r = ReceiptParser.parse("RAMEN ICHIRAN\n10/04/2026\nTotal ¥1,200", o("USD")).first()
        assertEquals("JPY", r.currency); assertEquals(1200L, r.amountMinor)
    }

    @Test fun currencyDetection() {
        assertEquals("JPY", CurrencyDetect.detect("Total ¥500", "USD"))
        assertEquals("EUR", CurrencyDetect.detect("Amount EUR 12,40", "USD"))
        assertEquals("GBP", CurrencyDetect.detect("no hints here", "GBP"))
        assertEquals("CAD", CurrencyDetect.detect("Total ${'$'}9.00", "CAD"))
    }

    @Test fun twoDecimalCurrenciesAreUnchanged() {
        assertEquals(listOf(550L, 149_450L), AmountParse.find("STARBUCKS -5.50 1,494.50", 2).map { it.value.minor })
    }
}

/** Which rows really are duplicates, and what makes a row's identity unique. Reproduces the reviewer's cases. */
class DuplicateTest {
    private fun dim(id: String, name: String) = Dimension(id, name, emptyList(), 1)
    private fun existing(id: String, kind: String = "expense", merchant: String? = null, desc: String? = null, source: String? = null, record: String? = null) =
        Spend(id = id, version = 1, occurredOn = "2026-10-02", kind = kind, amountMinor = 250, currency = "USD", merchantId = merchant, description = desc, source = source, sourceRecordId = record)
    private fun item(desc: String, kind: String = "expense", externalId: String? = null) =
        ParsedItem(date = "2026-10-02", description = desc, amountMinor = 250, kind = kind, currency = "USD", sourceLine = desc, externalId = externalId)
    private fun enrich(vararg items: ParsedItem, spends: List<Spend> = emptyList(), merchants: List<Dimension> = emptyList(), doc: String? = null) =
        Enricher(merchants, spends).enrich(items.toList(), doc)

    @Test fun aCoffeeIsNotADuplicateOfBusFareForTheSamePrice() {
        val r = enrich(item("STARBUCKS #1234"), spends = listOf(existing("bus", desc = "Bus fare")), merchants = listOf(dim("m1", "Starbucks")))
        assertNull(r[0].duplicateOf); assertNull(r[0].possibleDuplicateOf); assertTrue(r[0].include)
    }

    @Test fun theSameMerchantOnTheSameDayForTheSamePriceIsADuplicate() {
        val r = enrich(item("STARBUCKS #1234"), spends = listOf(existing("coffee", merchant = "m1")), merchants = listOf(dim("m1", "Starbucks")))
        assertEquals("coffee", r[0].duplicateOf); assertFalse(r[0].include)
    }

    @Test fun aMatchingNoteAloneIsEnough() {
        assertEquals("n", enrich(item("STARBUCKS #77 SEATTLE"), spends = listOf(existing("n", desc = "Starbucks latte")))[0].duplicateOf)
    }

    @Test fun aRowAlreadyImportedIsRecognisedByItsSourceIdentity() {
        val probe = enrich(item("COFFEE SHOP"))[0].fingerprint
        val r = enrich(item("COFFEE SHOP"), spends = listOf(existing("old", desc = "totally different words", source = "import", record = probe)))
        assertEquals("old", r[0].duplicateOf); assertFalse(r[0].include)
    }

    @Test fun anExpenseAndARefundOfTheSameThingDoNotShareAFingerprint() {
        val r = enrich(item("AMAZON MKTP", "expense"), item("AMAZON MKTP", "refund"))
        assertNotEquals(r[0].fingerprint, r[1].fingerprint)
    }

    @Test fun twoSeparateReceiptsForTheSameCoffeeAreTwoSpends() {
        val first = enrich(item("BLUE BOTTLE"), doc = "receipt-photo-1")[0]
        val second = enrich(item("BLUE BOTTLE"), doc = "receipt-photo-2")[0]
        assertNotEquals("the second must not be skipped as 'already imported'", first.fingerprint, second.fingerprint)
        assertEquals(first.fingerprint, enrich(item("BLUE BOTTLE"), doc = "receipt-photo-1")[0].fingerprint) // the same file again is still recognised
    }

    @Test fun overlappingStatementsShareTheRowsTheyShare() {
        assertEquals(enrich(item("RAMEN SHOP"))[0].fingerprint, enrich(item("RAMEN SHOP"))[0].fingerprint)
    }

    @Test fun aBankTransactionIdIsTheIdentityWhateverTheDescriptionSays() {
        val a = enrich(item("SQ *BLUE BOTTLE", externalId = "TXN-9001"))[0]
        val b = enrich(item("BLUE BOTTLE COFFEE OAKLAND", externalId = "TXN-9001"))[0]
        val c = enrich(item("BLUE BOTTLE COFFEE OAKLAND", externalId = "TXN-9002"))[0]
        assertEquals(a.fingerprint, b.fingerprint); assertNotEquals(a.fingerprint, c.fingerprint)
    }

    @Test fun csvReadsATransactionIdColumnButNotPaidIn() {
        val o = ParseOptions(false, true, "USD", TODAY)
        assertEquals("TXN-1", CSVParser.items("Date,Transaction ID,Description,Amount\n2026-10-02,TXN-1,Coffee,-2.50\n", o)?.first()?.externalId)
        assertNull(CSVParser.items("Date,Description,Paid out,Paid in\n2026-10-02,Coffee,2.50,\n", o)?.first()?.externalId)
    }
}
