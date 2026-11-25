package dev.local.androidreader.epub

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * An open EPUB: its title, its chapters in reading order, and access to the files inside.
 * Reads the parts of the format that readers depend on (container, package, navigation).
 */
class EpubBook private constructor(
  private val zip: ZipFile,
  val title: String,
  /** Paths inside the archive, in reading order. */
  val chapters: List<Chapter>,
  /** Path of the cover image inside the archive, if the book declares one. */
  val coverPath: String?,
  private val mediaTypes: Map<String, String>,
) : Closeable {

  data class Chapter(val path: String, val title: String)

  fun open(path: String): InputStream? = zip.getEntry(path)?.takeIf { !it.isDirectory }?.let(zip::getInputStream)

  /** One section's text, for styling before display, or null if it is too large to hold in memory. */
  fun readSection(path: String): ByteArray? =
    open(path)?.use { stream -> runCatching { readLimited(stream, MAX_SECTION_BYTES) }.getOrNull() }

  fun mediaType(path: String): String =
    mediaTypes[path]
      ?: when (path.substringAfterLast('.').lowercase()) {
        "xhtml", "html", "htm" -> "application/xhtml+xml"
        "css" -> "text/css"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
      }

  fun chapterIndex(path: String): Int = chapters.indexOfFirst { it.path == path }

  override fun close() = zip.close()

  companion object {
    fun open(file: File): EpubBook {
      val zip = ZipFile(file)
      try {
        return read(zip, file.nameWithoutExtension)
      } catch (e: Exception) {
        zip.close()
        throw if (e is IOException) e else IOException("This EPUB could not be read (${e.message ?: e.javaClass.simpleName})", e)
      }
    }

    private fun read(zip: ZipFile, fallbackTitle: String): EpubBook {
      // These small index files are read into memory whole, so a book cannot be allowed to
      // present one that expands without end.
      fun text(path: String): String {
        val entry = zip.getEntry(path) ?: throw IOException("This EPUB is missing $path")
        return zip.getInputStream(entry).use { s -> readLimited(s, MAX_INDEX_BYTES).toString(Charsets.UTF_8) }
      }

      val container = parseXml(text("META-INF/container.xml"))
      val opfPath =
        container.descendants("rootfile").firstOrNull()?.getAttribute("full-path")?.takeIf { it.isNotBlank() }
          ?: throw IOException("This EPUB does not say where its contents are")
      val opf = parseXml(text(opfPath))

      // Manifest: every file in the book, by id.
      data class Item(val path: String, val type: String, val properties: String)
      val items = LinkedHashMap<String, Item>()
      for (item in opf.descendants("item")) {
        val href = item.getAttribute("href").takeIf { it.isNotBlank() } ?: continue
        items[item.getAttribute("id")] = Item(resolve(opfPath, href), item.getAttribute("media-type"), item.getAttribute("properties"))
      }

      // Spine: the reading order, as references into the manifest.
      val spine = opf.descendants("spine").firstOrNull()
      val order = opf.descendants("itemref").mapNotNull { items[it.getAttribute("idref")]?.path }.filter { zip.getEntry(it) != null }
      if (order.isEmpty()) throw IOException("This EPUB has no readable chapters")

      // Chapter names come from the EPUB 3 navigation document, or the older NCX file.
      val names = HashMap<String, String>()
      items.values.firstOrNull { "nav" in it.properties.split(' ') }?.let { nav ->
        runCatching {
          for (a in parseXml(text(nav.path)).descendants("a")) {
            val href = a.getAttribute("href").takeIf { it.isNotBlank() } ?: continue
            names.putIfAbsent(resolve(nav.path, href), a.textContent.clean())
          }
        }
      }
      val ncx = items[spine?.getAttribute("toc").orEmpty()] ?: items.values.firstOrNull { it.type == "application/x-dtbncx+xml" }
      if (names.isEmpty() && ncx != null) {
        runCatching {
          for (point in parseXml(text(ncx.path)).descendants("navPoint")) {
            val src = point.descendants("content").firstOrNull()?.getAttribute("src")?.takeIf { it.isNotBlank() } ?: continue
            val label = point.descendants("text").firstOrNull()?.textContent?.clean().orEmpty()
            names.putIfAbsent(resolve(ncx.path, src), label)
          }
        }
      }

      val title = opf.descendants("title").firstOrNull()?.textContent?.clean()?.takeIf { it.isNotEmpty() } ?: fallbackTitle
      val cover =
        items.values.firstOrNull { "cover-image" in it.properties.split(' ') }?.path
          ?: opf.descendants("meta").firstOrNull { it.getAttribute("name") == "cover" }?.getAttribute("content")?.let { items[it]?.path }
      val chapters = order.mapIndexed { i, path -> Chapter(path, names[path]?.takeIf { it.isNotEmpty() } ?: "Section ${i + 1}") }
      return EpubBook(zip, title, chapters, cover?.takeIf { zip.getEntry(it) != null }, items.values.associate { it.path to it.type })
    }

    const val MAX_INDEX_BYTES = 16 * 1024 * 1024
    const val MAX_SECTION_BYTES = 32 * 1024 * 1024

    /** Reads a stream fully, failing instead of growing past [limit] bytes. */
    fun readLimited(stream: InputStream, limit: Int): ByteArray {
      val out = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = stream.read(buffer)
        if (read < 0) return out.toByteArray()
        if (out.size() + read > limit) throw IOException("This part of the book is too large to open")
        out.write(buffer, 0, read)
      }
    }

    /** Resolves a link found in [fromPath] to a path inside the archive, without its #fragment. */
    fun resolve(fromPath: String, href: String): String {
      val clean = href.substringBefore('#').substringBefore('?')
      val decoded = runCatching { URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8") }.getOrDefault(clean)
      val base = fromPath.substringBeforeLast('/', "")
      return normalize(if (decoded.startsWith("/")) decoded else if (base.isEmpty()) decoded else "$base/$decoded")
    }

    /** Collapses "." and ".." in an already-decoded path, so it always stays within the archive. */
    fun normalize(path: String): String {
      val parts = ArrayList<String>()
      for (part in path.split('/')) {
        when (part) {
          "", "." -> Unit
          ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
          else -> parts.add(part)
        }
      }
      return parts.joinToString("/")
    }

    /** Written as a character code so the invisible character itself never appears in this file. */
    private val BYTE_ORDER_MARK = 0xFEFF.toChar().toString()

    private fun String.clean(): String = replace(Regex("\\s+"), " ").trim()

    private fun parseXml(xml: String): Element {
      val factory = DocumentBuilderFactory.newInstance()
      factory.isNamespaceAware = true
      // Book files are untrusted and often carry a DOCTYPE; never fetch what it points to.
      for (feature in
        listOf(
          "http://xml.org/sax/features/external-general-entities",
          "http://xml.org/sax/features/external-parameter-entities",
          "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        )) {
        runCatching { factory.setFeature(feature, false) }
      }
      runCatching { factory.isExpandEntityReferences = false }
      val builder = factory.newDocumentBuilder()
      builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
      // A leading byte-order mark makes strict parsers reject the file.
      return builder.parse(InputSource(StringReader(xml.removePrefix(BYTE_ORDER_MARK)))).documentElement
    }

    private fun Element.descendants(name: String): List<Element> {
      val list = getElementsByTagNameNS("*", name)
      return (0 until list.length).mapNotNull { list.item(it) as? Element }
    }

  }
}
