package dev.local.androidreader.epub

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.local.androidreader.library.Importer
import java.io.File
import java.io.IOException

/**
 * Makes a reflowable EPUB from the text of a PDF.
 *
 * Only the text is carried over: images, tables and page layout are not. A scanned PDF, which is
 * pictures of pages with no text behind them, cannot be converted.
 */
object PdfToEpub {
  const val PAGES_PER_CHAPTER = 20

  /** Fewer characters per page than this, on average, means there is no real text layer. */
  private const val MIN_CHARS_PER_PAGE = 40

  /**
   * Converts [pdf] and returns the new EPUB, placed next to it in [libraryDir]. The PDF is kept.
   * Blocking; [onProgress] receives pages done and the page total.
   */
  fun convert(context: Context, pdf: File, libraryDir: File, onProgress: (Int, Int) -> Unit = { _, _ -> }): File {
    PDFBoxResourceLoader.init(context.applicationContext)
    val temp = File.createTempFile("convert", ".tmp", libraryDir)
    try {
      // Temp-file buffering keeps a large PDF from being loaded into memory whole.
      val document =
        try {
          PDDocument.load(pdf, MemoryUsageSetting.setupTempFileOnly())
        } catch (e: Exception) {
          throw IOException(if ("password" in e.message.orEmpty().lowercase()) "This PDF is password protected" else "This PDF could not be read", e)
        }
      document.use {
        if (document.isEncrypted) throw IOException("This PDF is protected and its text cannot be copied")
        val stripper = PDFTextStripper()
        val title = document.documentInformation?.title?.trim()?.takeIf { it.length in 3..200 } ?: pdf.nameWithoutExtension
        write(title, document.numberOfPages, temp, onProgress) { page ->
          // The stripper numbers pages from 1.
          stripper.startPage = page + 1
          stripper.endPage = page + 1
          stripper.getText(document)
        }
      }
      val target = Importer.uniqueFile(libraryDir, pdf.nameWithoutExtension + ".epub")
      if (!temp.renameTo(target)) throw IOException("Could not save the converted book")
      return target
    } finally {
      temp.delete()
    }
  }

  /**
   * The conversion itself, independent of where the page text comes from: reflows each page,
   * groups pages into sections, and refuses a document with no usable text.
   */
  fun write(title: String, pageCount: Int, out: File, onProgress: (Int, Int) -> Unit = { _, _ -> }, pageText: (Int) -> String) {
    if (pageCount <= 0) throw IOException("This PDF has no pages")
    var characters = 0L
    EpubWriter(out, title).use { writer ->
      var start = 0
      while (start < pageCount) {
        val end = minOf(start + PAGES_PER_CHAPTER, pageCount)
        val paragraphs = ArrayList<String>()
        for (page in start until end) {
          // One unreadable page should not lose the rest of the book.
          val text = runCatching { pageText(page) }.getOrDefault("")
          paragraphs += joinAcrossPages(paragraphs, TextReflow.paragraphs(text))
          onProgress(page + 1, pageCount)
        }
        characters += paragraphs.sumOf { it.length }
        if (paragraphs.isNotEmpty()) {
          writer.addChapter(if (end - start == 1) "Page ${start + 1}" else "Pages ${start + 1}–$end", paragraphs)
        }
        start = end
      }
      if (writer.chapterCount == 0 || characters < MIN_CHARS_PER_PAGE.toLong() * pageCount) {
        // Still close the file properly; the caller deletes it.
        if (writer.chapterCount == 0) writer.addChapter("Empty", emptyList())
        throw IOException("This PDF has no text to convert. It is probably a scan, which is images of pages.")
      }
    }
  }

  /**
   * A paragraph often runs over a page break. If the text so far stops mid-sentence and the new
   * page starts in lower case, the two halves are joined; [previous] is updated in place.
   */
  private fun joinAcrossPages(previous: MutableList<String>, next: List<String>): List<String> {
    val last = previous.lastOrNull() ?: return next
    val first = next.firstOrNull() ?: return next
    if (last.last() in ".!?:\"”’)" || !first.first().isLowerCase()) return next
    previous[previous.size - 1] = if (last.endsWith("-") && last.length > 1 && last[last.length - 2].isLetter()) last.dropLast(1) + first else "$last $first"
    return next.drop(1)
  }
}
