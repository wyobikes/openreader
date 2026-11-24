package dev.local.androidreader.epub

import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a plain-text book as an EPUB 3 file, one chapter at a time so a long book never has to
 * be held in memory. Also writes the older NCX contents file, for readers that predate EPUB 3.
 */
class EpubWriter(file: File, private val title: String, private val language: String = "en") : AutoCloseable {
  private val zip = ZipOutputStream(file.outputStream().buffered())
  private val chapterTitles = ArrayList<String>()
  private val id = "urn:uuid:${UUID.nameUUIDFromBytes(title.toByteArray())}"

  init {
    // The format requires this entry first and uncompressed, so tools can recognise the file.
    val mimetype = "application/epub+zip".toByteArray()
    zip.putNextEntry(
      ZipEntry("mimetype").apply {
        method = ZipEntry.STORED
        size = mimetype.size.toLong()
        compressedSize = mimetype.size.toLong()
        crc = CRC32().apply { update(mimetype) }.value
      }
    )
    zip.write(mimetype)
    zip.closeEntry()
    entry(
      "META-INF/container.xml",
      """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""",
    )
    entry(
      "OEBPS/style.css",
      "body { line-height: 1.5; margin: 0 5%; }\nh1 { font-size: 1.3em; margin: 1.5em 0 1em; }\np { margin: 0 0 0.9em; }\n",
    )
  }

  val chapterCount: Int
    get() = chapterTitles.size

  fun addChapter(chapterTitle: String, paragraphs: List<String>) {
    chapterTitles += chapterTitle
    val body = StringBuilder()
    for (paragraph in paragraphs) body.append("<p>").append(escape(paragraph)).append("</p>\n")
    entry(
      "OEBPS/${fileName(chapterTitles.size)}",
      """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" lang="${escape(language)}">
<head><meta charset="UTF-8"/><title>${escape(chapterTitle)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
<h1>${escape(chapterTitle)}</h1>
$body</body>
</html>""",
    )
  }

  /** Writes the contents pages and package file, which list every chapter, then finishes the file. */
  override fun close() {
    val numbers = 1..chapterTitles.size
    entry(
      "OEBPS/nav.xhtml",
      """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="${escape(language)}">
<head><meta charset="UTF-8"/><title>Contents</title></head>
<body><nav epub:type="toc"><h1>Contents</h1><ol>
${numbers.joinToString("\n") { """<li><a href="${fileName(it)}">${escape(chapterTitles[it - 1])}</a></li>""" }}
</ol></nav></body>
</html>""",
    )
    entry(
      "OEBPS/toc.ncx",
      """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head><meta name="dtb:uid" content="$id"/></head>
<docTitle><text>${escape(title)}</text></docTitle>
<navMap>
${numbers.joinToString("\n") { """<navPoint id="n$it" playOrder="$it"><navLabel><text>${escape(chapterTitles[it - 1])}</text></navLabel><content src="${fileName(it)}"/></navPoint>""" }}
</navMap>
</ncx>""",
    )
    entry(
      "OEBPS/content.opf",
      """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
  <dc:identifier id="book-id">$id</dc:identifier>
  <dc:title>${escape(title)}</dc:title>
  <dc:language>${escape(language)}</dc:language>
  <meta property="dcterms:modified">2000-01-01T00:00:00Z</meta>
</metadata>
<manifest>
  <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
  <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  <item id="css" href="style.css" media-type="text/css"/>
${numbers.joinToString("\n") { """  <item id="c$it" href="${fileName(it)}" media-type="application/xhtml+xml"/>""" }}
</manifest>
<spine toc="ncx">
${numbers.joinToString("\n") { """  <itemref idref="c$it"/>""" }}
</spine>
</package>""",
    )
    zip.close()
  }

  private fun entry(name: String, content: String) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(content.toByteArray())
    zip.closeEntry()
  }

  companion object {
    private fun fileName(number: Int) = "chapter-%04d.xhtml".format(number)

    /** Makes text safe to place inside XML: escapes markup and drops characters XML forbids. */
    fun escape(text: String): String {
      val out = StringBuilder(text.length + 16)
      var i = 0
      while (i < text.length) {
        val code = text.codePointAt(i)
        i += Character.charCount(code)
        when {
          code == '&'.code -> out.append("&amp;")
          code == '<'.code -> out.append("&lt;")
          code == '>'.code -> out.append("&gt;")
          code == '"'.code -> out.append("&quot;")
          code == 0x9 || code == 0xA || code == 0xD -> out.append(' ')
          code < 0x20 || code == 0xFFFE || code == 0xFFFF || code in 0xD800..0xDFFF -> Unit
          else -> out.appendCodePoint(code)
        }
      }
      return out.toString()
    }

  }
}

/** Turns the hard-wrapped lines a PDF gives back into flowing paragraphs. */
object TextReflow {
  private val pageNumber = Regex("^[\\s\\-–—]*\\d{1,5}[\\s\\-–—]*$")
  private val sentenceEnd = Regex("[.!?:;…\"'”’)\\]]$")

  fun paragraphs(pageText: String): List<String> {
    val lines = pageText.lines().map { it.trim() }
    // Bare numbers on their own line are page numbers from the header or footer.
    val kept = lines.filterNot { pageNumber.matches(it) }
    val longest = kept.maxOfOrNull { it.length } ?: return emptyList()

    val result = ArrayList<String>()
    val current = StringBuilder()
    fun flush() {
      if (current.isNotBlank()) result += current.toString().replace(Regex("\\s+"), " ").trim()
      current.clear()
    }
    for (line in kept) {
      if (line.isEmpty()) {
        flush()
        continue
      }
      // A word split across lines with a hyphen is put back together.
      if (current.endsWith("-") && current.length > 1 && current[current.length - 2].isLetter() && line[0].isLowerCase()) {
        current.setLength(current.length - 1)
      } else if (current.isNotEmpty()) {
        current.append(' ')
      }
      current.append(line)
      // A line that stops well short of the margin at the end of a sentence closes a paragraph.
      if (line.length < longest * 0.7 && sentenceEnd.containsMatchIn(line)) flush()
    }
    flush()
    return result
  }
}
