package dev.local.androidreader

import dev.local.androidreader.epub.EpubBook
import dev.local.androidreader.epub.EpubWriter
import dev.local.androidreader.epub.TextReflow
import dev.local.androidreader.library.Importer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class EpubTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun zip(name: String, entries: Map<String, String>): File {
    // Some tests build the same sample book more than once.
    val file = File(tmp.newFolder(), name)
    ZipOutputStream(file.outputStream()).use { out ->
      entries.forEach { (entryName, text) ->
        out.putNextEntry(ZipEntry(entryName))
        out.write(text.toByteArray())
        out.closeEntry()
      }
    }
    return file
  }

  private val container =
    """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
       <rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

  /** An EPUB 2 style book: DOCTYPE on the NCX, encoded file names, a cover named by a meta tag. */
  private fun epub2() =
    zip(
      "old.epub",
      mapOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to container,
        "OPS/package.opf" to
          """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>  A   Short
            Book </dc:title><meta name="cover" content="cover-img"/></metadata>
          <manifest>
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            <item id="one" href="text/ch%201.xhtml" media-type="application/xhtml+xml"/>
            <item id="two" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
            <item id="gone" href="text/missing.xhtml" media-type="application/xhtml+xml"/>
            <item id="cover-img" href="../OPS/images/cover.jpg" media-type="image/jpeg"/>
          </manifest>
          <spine toc="ncx"><itemref idref="one"/><itemref idref="gone"/><itemref idref="two"/><itemref idref="unknown"/></spine>
          </package>""",
        "OPS/toc.ncx" to
          """<?xml version="1.0"?>
          <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
          <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
            <navPoint id="a"><navLabel><text>The Beginning</text></navLabel><content src="text/ch%201.xhtml#start"/></navPoint>
          </navMap></ncx>""",
        "OPS/text/ch 1.xhtml" to "<html><body><p>One</p></body></html>",
        "OPS/text/ch2.xhtml" to "<html><body><p>Two</p></body></html>",
        "OPS/images/cover.jpg" to "jpeg-bytes",
      ),
    )

  @Test
  fun readsAnOlderEpubWithNcxContents() {
    EpubBook.open(epub2()).use { book ->
      assertEquals("A Short Book", book.title)
      // The chapter whose file is missing and the dangling reference are skipped.
      assertEquals(listOf("OPS/text/ch 1.xhtml", "OPS/text/ch2.xhtml"), book.chapters.map { it.path })
      assertEquals(listOf("The Beginning", "Section 2"), book.chapters.map { it.title })
      assertEquals("OPS/images/cover.jpg", book.coverPath)
      assertEquals("image/jpeg", book.mediaType("OPS/images/cover.jpg"))
      assertEquals("text/css", book.mediaType("OPS/unlisted.css"))
      assertEquals("<html><body><p>Two</p></body></html>", book.open("OPS/text/ch2.xhtml")!!.reader().readText())
      assertNull(book.open("OPS/nope.xhtml"))
      assertEquals(1, book.chapterIndex("OPS/text/ch2.xhtml"))
      assertEquals(-1, book.chapterIndex("OPS/other.xhtml"))
    }
  }

  @Test
  fun linksResolveInsideTheArchiveOnly() {
    assertEquals("OPS/text/ch2.xhtml", EpubBook.resolve("OPS/text/ch1.xhtml", "ch2.xhtml#note-3"))
    assertEquals("OPS/images/a b.png", EpubBook.resolve("OPS/text/ch1.xhtml", "../images/a%20b.png"))
    assertEquals("top.xhtml", EpubBook.resolve("top.opf", "./top.xhtml"))
    // Links cannot climb out of the book.
    assertEquals("etc/passwd", EpubBook.resolve("OPS/ch.xhtml", "../../../../etc/passwd"))
    assertEquals("abs/file.css", EpubBook.resolve("OPS/ch.xhtml", "/abs/file.css"))
  }

  @Test
  fun brokenBooksAreRejectedWithAReason() {
    val notEpub = zip("comic.epub", mapOf("1.jpg" to "x"))
    assertTrue(assertThrows(IOException::class.java) { EpubBook.open(notEpub) }.message!!.contains("META-INF/container.xml"))
    val noChapters =
      zip(
        "empty.epub",
        mapOf(
          "META-INF/container.xml" to container,
          "OPS/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf"><manifest/><spine/></package>""",
        ),
      )
    assertEquals("This EPUB has no readable chapters", assertThrows(IOException::class.java) { EpubBook.open(noChapters) }.message)
    val garbage = zip("bad.epub", mapOf("META-INF/container.xml" to "<<<not xml"))
    assertThrows(IOException::class.java) { EpubBook.open(garbage) }
  }

  @Test
  fun writtenBooksReadBackWithTitlesChaptersAndEscaping() {
    val file = File(tmp.root, "out.epub")
    EpubWriter(file, "Fish & <Chips>").use { writer ->
      writer.addChapter("Pages 1–2", listOf("He said \"1 < 2 & 3 > 2\".", "Second\u0000 para\u0007graph\twith controls."))
      writer.addChapter("Pages 3–4", listOf("Ünïcödé and emoji 📚 survive."))
      assertEquals(2, writer.chapterCount)
    }

    ZipFile(file).use { zip ->
      // The format requires the mimetype entry first and uncompressed.
      val first = zip.entries().nextElement()
      assertEquals("mimetype", first.name)
      assertEquals(ZipEntry.STORED, first.method)
      assertEquals("application/epub+zip", zip.getInputStream(first).reader().readText())
    }

    EpubBook.open(file).use { book ->
      assertEquals("Fish & <Chips>", book.title)
      assertEquals(listOf("Pages 1–2", "Pages 3–4"), book.chapters.map { it.title })
      assertNull(book.coverPath)
      val first = book.open(book.chapters[0].path)!!.reader().readText()
      assertTrue(first.contains("<p>He said &quot;1 &lt; 2 &amp; 3 &gt; 2&quot;.</p>"))
      assertTrue(first.contains("<p>Second paragraph with controls.</p>"))
      assertTrue(book.open(book.chapters[1].path)!!.reader().readText().contains("Ünïcödé and emoji 📚 survive."))
      assertEquals("application/xhtml+xml", book.mediaType(book.chapters[0].path))
    }
  }

  @Test
  fun reflowJoinsWrappedLinesAndSplitsParagraphs() {
    val page =
      """
      |12
      |It was a long sentence that had been wrapped by the type-
      |setter across several lines of roughly the same width, and
      |it ended short.
      |The next paragraph begins here and also runs on for a good
      |while before it stops.
      |
      |After a blank line comes another one, with a hy-
      |Phenated proper name kept as it was
      |— 13 —
      """.trimMargin()
    assertEquals(
      listOf(
        "It was a long sentence that had been wrapped by the typesetter across several lines of roughly the same width, and it ended short.",
        "The next paragraph begins here and also runs on for a good while before it stops.",
        "After a blank line comes another one, with a hy- Phenated proper name kept as it was",
      ),
      TextReflow.paragraphs(page),
    )
    assertEquals(emptyList<String>(), TextReflow.paragraphs("  \n 7 \n"))
    assertEquals(listOf("One line only."), TextReflow.paragraphs("One line only."))
  }

  @Test
  fun importerRecognisesEpubsByContent() {
    val library = tmp.newFolder("lib")
    // Downloaded with no useful name or extension.
    val source = epub2().let { original -> File(tmp.root, "download.bin").also { original.copyTo(it) } }
    val result = Importer.import(source, library, "My Book")
    assertEquals(listOf("My Book.epub"), result.map { it.name })
    assertFalse(source.exists())
    EpubBook.open(result[0]).use { assertEquals(2, it.chapters.size) }

    // A zip bundle holding a book and a comic yields both.
    val inner = epub2().readBytes()
    val bundle = tmp.newFile("bundle.zip")
    ZipOutputStream(bundle.outputStream()).use { out ->
      out.putNextEntry(ZipEntry("pack/Novel.epub"))
      out.write(inner)
      out.closeEntry()
    }
    assertEquals(listOf("Novel.epub"), Importer.import(bundle, library, "bundle.zip").map { it.name })
  }
}

class PdfToEpubTest {
  @get:Rule val tmp = TemporaryFolder()

  private val sentence = "This is a full line of body text that runs to about the margin width."

  @Test
  fun pagesBecomeSectionsOfTwentyAndTextFlowsAcrossPageBreaks() {
    val out = File(tmp.root, "out.epub")
    val progress = ArrayList<Pair<Int, Int>>()
    dev.local.androidreader.epub.PdfToEpub.write("Manual", 45, out, { done, total -> progress += done to total }) { page ->
      when (page) {
        0 -> "$sentence\nIt carries on over the page and the sen-"
        1 -> "tence finishes here.\n\n$sentence"
        7 -> throw IllegalStateException("damaged page")
        else -> "$sentence\n$sentence\nPage $page ends."
      }
    }
    assertEquals(45 to 45, progress.last())
    assertEquals(45, progress.size)
    EpubBook.open(out).use { book ->
      assertEquals("Manual", book.title)
      assertEquals(listOf("Pages 1–20", "Pages 21–40", "Pages 41–45"), book.chapters.map { it.title })
      val first = book.open(book.chapters[0].path)!!.reader().readText()
      assertTrue(first.contains("<p>$sentence It carries on over the page and the sentence finishes here.</p>"))
      assertTrue(first.contains("Page 6 ends."))
    }
  }

  @Test
  fun scannedPdfsWithNoTextAreRefused() {
    val out = File(tmp.root, "scan.epub")
    val error =
      assertThrows(IOException::class.java) {
        dev.local.androidreader.epub.PdfToEpub.write("Scan", 30, out) { page -> if (page == 3) "12" else "" }
      }
    assertTrue(error.message!!.contains("probably a scan"))
    // Stray marks on a few pages do not count as a text layer either.
    assertThrows(IOException::class.java) {
      dev.local.androidreader.epub.PdfToEpub.write("Scan", 30, File(tmp.root, "scan2.epub")) { page -> if (page < 2) "Stamp: received." else "" }
    }
    assertThrows(IOException::class.java) { dev.local.androidreader.epub.PdfToEpub.write("None", 0, File(tmp.root, "none.epub")) { "" } }
  }

  @Test
  fun aSinglePageDocumentIsNamedAsOnePage() {
    val out = File(tmp.root, "one.epub")
    dev.local.androidreader.epub.PdfToEpub.write("Letter", 1, out) { "$sentence\n$sentence" }
    EpubBook.open(out).use { assertEquals(listOf("Page 1"), it.chapters.map { c -> c.title }) }
  }
}

class BookHtmlTest {
  @Test
  fun bookAddressesRoundTripAndForeignAddressesAreRejected() {
    val path = "OPS/text/ch 1 #2 ü.xhtml"
    val url = dev.local.androidreader.epub.BookHtml.url(path)
    assertTrue(url.startsWith("https://book.invalid/OPS/text/"))
    assertFalse(url.contains(" ") || url.contains("#"))
    assertEquals(path, dev.local.androidreader.epub.BookHtml.path(url))
    assertEquals(path, dev.local.androidreader.epub.BookHtml.path("$url#footnote-3"))
    assertNull(dev.local.androidreader.epub.BookHtml.path("https://example.org/OPS/text/ch1.xhtml"))
    assertNull(dev.local.androidreader.epub.BookHtml.path("file:///data/secret"))
    // An address cannot be used to reach outside the book.
    assertEquals("etc/passwd", dev.local.androidreader.epub.BookHtml.path("https://book.invalid/a/../../../etc/passwd"))
  }

  @Test
  fun stylesGoInsideTheHeadOrFailingThatTheBody() {
    val inject = dev.local.androidreader.epub.BookHtml::inject
    assertEquals("<html><head><title>T</title><style>x{}</style></HEAD ><body>B</body></html>", inject("<html><head><title>T</title></HEAD ><body>B</body></html>", "x{}"))
    assertEquals("<html><body class=\"a\"><style>x{}</style>B</body></html>", inject("<html><body class=\"a\">B</body></html>", "x{}"))
    assertEquals("<style>x{}</style>plain text", inject("plain text", "x{}"))
  }

  @Test
  fun darkStyleForcesReadableColoursAndLightStyleDoesNot() {
    val dark = dev.local.androidreader.epub.BookHtml.css(dark = true)
    val light = dev.local.androidreader.epub.BookHtml.css(dark = false)
    assertTrue(dark.contains("#121212!important") && dark.contains("color:#e3e3e3!important"))
    assertFalse(light.contains("#121212"))
    assertTrue(light.contains("max-width:100%!important") && dark.contains("max-width:100%!important"))
    assertTrue(dev.local.androidreader.epub.BookHtml.isChapterType("application/xhtml+xml"))
    assertFalse(dev.local.androidreader.epub.BookHtml.isChapterType("image/png"))
  }
}
