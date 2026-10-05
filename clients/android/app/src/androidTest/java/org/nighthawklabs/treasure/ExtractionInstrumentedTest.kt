package org.nighthawklabs.treasure

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.nighthawklabs.treasure.ingest.*
import java.io.File
import java.time.LocalDate

/** The real on-device path: draw a document, then read it back with ML Kit / PdfRenderer and parse it. */
class ExtractionInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val opts = ParseOptions(false, true, "USD", LocalDate.of(2026, 10, 20))

    private fun paint(size: Float) = Paint().apply { color = Color.BLACK; textSize = size; typeface = Typeface.MONOSPACE; isAntiAlias = true }

    private fun drawnPng(lines: List<String>, w: Int = 900, h: Int = 1200, size: Float = 34f): Uri {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        lines.forEachIndexed { i, l -> c.drawText(l, 40f, 80f + i * size * 1.6f, paint(size)) }
        val f = File(context.cacheDir, "t-${System.nanoTime()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f)
    }

    private fun drawnPdf(lines: List<String>): Uri {
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create())
        lines.forEachIndexed { i, l -> page.canvas.drawText(l, 40f, 60f + i * 24f, paint(12f)) }
        pdf.finishPage(page)
        val f = File(context.cacheDir, "t-${System.nanoTime()}.pdf")
        f.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return Uri.fromFile(f)
    }

    @Test fun receiptPhotoIsReadAndParsed() = runBlocking {
        val uri = drawnPng(listOf("BLUE BOTTLE COFFEE", "123 Main Street", "10/04/2026 8:15 AM", "Latte 5.50", "Croissant 4.25", "Subtotal 9.75", "Tax 0.85", "Total ${'$'}10.60"))
        val doc = TextExtractor(context).extract(listOf(uri), "receipt")
        val items = ReceiptParser.parse(doc.text, opts)
        assertEquals("OCR text was:\n${doc.text}", 1060L, items.firstOrNull()?.amountMinor)
        assertEquals("OCR text was:\n${doc.text}", "2026-10-04", items.first().date)
        assertTrue("OCR text was:\n${doc.text}", items.first().description.uppercase().contains("BLUE BOTTLE"))
    }

    @Test fun pdfStatementIsRenderedAndRead() = runBlocking {
        val uri = drawnPdf(listOf("ACME BANK Statement", "Date Description Amount Balance", "10/02/2026 STARBUCKS SEATTLE -5.50 1,494.50", "10/04/2026 AMAZON MKTP -42.99 1,451.51", "10/06/2026 SHELL OIL -38.20 1,413.31"))
        val doc = TextExtractor(context).extract(uri)!!
        val items = StatementParser.parse(doc.text, opts)
        assertEquals("PDF text was:\n${doc.text}", listOf(550L, 4299L, 3820L), items.map { it.amountMinor })
        assertEquals("application/pdf", doc.mediaType)
        assertEquals(64, doc.hash.length)
    }

    @Test fun csvFileIsReadDirectly() = runBlocking {
        val f = File(context.cacheDir, "t.csv").apply { writeText("Date,Description,Amount\n2026-10-02,Coffee,-5.50\n") }
        val doc = TextExtractor(context).extract(Uri.fromFile(f))!!
        assertTrue(doc.isCSV)
        assertEquals(550L, CSVParser.items(doc.text, opts)!!.first().amountMinor)
    }
}
