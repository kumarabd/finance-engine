package org.nighthawklabs.treasure.ingest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/** What a file or set of photos turned into: plain text lines, read entirely on this device. */
data class ExtractedDocument(
    val text: String,
    val isCSV: Boolean,
    val hash: String,      // SHA-256 of the file bytes; identifies the evidence record
    val name: String,
    val mediaType: String,
)

/** Turns a picked file or scanner pages into text. Separate from [TextExtractor] so the import logic can be tested without a Context. */
interface DocumentReader {
    suspend fun extract(uri: Uri): ExtractedDocument?
    suspend fun extract(uris: List<Uri>, name: String): ExtractedDocument
}

/**
 * ML Kit's bundled text recognizer runs on-device with no network. Android has no built-in PDF text extraction, so a PDF is
 * drawn page by page and read the same way as a photo.
 * ponytail: always OCR; add pdfbox-android for the exact text layer if OCR of digital PDFs proves too lossy.
 */
class TextExtractor(private val context: Context) : DocumentReader {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun extract(uri: Uri): ExtractedDocument? = withContext(Dispatchers.IO) {
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@withContext null
        val name = displayName(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = context.contentResolver.getType(uri).orEmpty()
        val hash = Enricher.hash(bytes)
        when {
            ext == "csv" || ext == "txt" || mime.startsWith("text/") -> {
                val csv = ext == "csv" || mime.contains("csv")
                ExtractedDocument(bytes.toString(Charsets.UTF_8), csv, hash, name, if (csv) "text/csv" else "text/plain")
            }
            ext == "pdf" || mime == "application/pdf" -> ExtractedDocument(pdfText(bytes), false, hash, name, "application/pdf")
            else -> ExtractedDocument(ocr(InputImage.fromFilePath(context, uri)), false, hash, name, mime.ifEmpty { "image/jpeg" })
        }
    }

    /** Photos or scanner pages, in order. */
    override suspend fun extract(uris: List<Uri>, name: String): ExtractedDocument = withContext(Dispatchers.IO) {
        val pages = mutableListOf<String>()
        val all = java.io.ByteArrayOutputStream()
        for (u in uris) {
            runCatching { pages += ocr(InputImage.fromFilePath(context, u)) }
            runCatching { context.contentResolver.openInputStream(u)?.use { all.write(it.readBytes()) } }
        }
        ExtractedDocument(pages.joinToString("\n"), false, Enricher.hash(all.toByteArray()), name, "image/jpeg")
    }

    private suspend fun pdfText(bytes: ByteArray): String {
        val file = File.createTempFile("import", ".pdf", context.cacheDir)
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val pages = mutableListOf<String>()
                    for (i in 0 until minOf(renderer.pageCount, 40)) {
                        renderer.openPage(i).use { page ->
                            val scale = (1800f / page.width).coerceAtMost(3f)
                            val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE) // a PDF page is transparent until drawn on
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            pages += ocr(InputImage.fromBitmap(bmp, 0))
                            bmp.recycle()
                        }
                    }
                    return pages.joinToString("\n")
                }
            }
        } finally { file.delete() }
    }

    private suspend fun ocr(image: InputImage): String {
        val result = recognizer.process(image).await()
        val frags = result.textBlocks.flatMap { it.lines }.mapNotNull { l ->
            l.boundingBox?.let { Frag(l.text, it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
        }
        return rows(frags).joinToString("\n")
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "document"

    data class Frag(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val midY get() = (top + bottom) / 2
        val height get() = bottom - top
    }

    companion object {
        /**
         * Recognizer lines come with boxes (origin top-left, y grows downward). Fragments on the same visual row are joined
         * left to right, so "date, description, amount" columns stay on one line.
         */
        fun rows(found: List<Frag>): List<String> {
            val rows = mutableListOf<MutableList<Frag>>()
            for (f in found.sortedBy { it.midY }) {
                val ref = rows.lastOrNull()?.first()
                if (ref != null && Math.abs(f.midY - ref.midY) <= maxOf(f.height, ref.height) * 0.5f) rows.last().add(f) else rows.add(mutableListOf(f))
            }
            return rows.map { r -> r.sortedBy { it.left }.joinToString("  ") { it.text } }
        }
    }
}
