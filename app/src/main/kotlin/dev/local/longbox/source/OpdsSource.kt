package dev.local.longbox.source

import kotlinx.serialization.Serializable
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

@Serializable
data class OpdsConfig(
  val name: String,
  val url: String,
  val username: String = "",
  val password: String = "",
) {
  val id: String
    get() = "opds:$url"
}

/** An OPDS 1.x (Atom) catalogue, for example a self-hosted Komga or Kavita server. */
class OpdsSource(private val client: OkHttpClient, private val config: OpdsConfig) : ComicSource {
  override val id = config.id
  override val name = config.name
  override val supportsSearch = true

  private var searchTemplate: String? = null

  private val headers: Map<String, String>
    get() =
      if (config.username.isEmpty()) emptyMap()
      else mapOf("Authorization" to Credentials.basic(config.username, config.password))

  override suspend fun browse(location: String?): CatalogPage {
    val url = location ?: config.url
    val feed = parseFeed(client.getText(url, headers), url, id)
    feed.searchUrl?.let { searchTemplate = resolveSearchTemplate(it) }
    return feed.page
  }

  override suspend fun search(query: String): CatalogPage {
    if (searchTemplate == null) browse(null)
    val template = searchTemplate ?: throw IllegalStateException("This catalogue does not offer search")
    val encoded = "http://x/".toHttpUrl().newBuilder().addQueryParameter("q", query).build().encodedQuery!!.removePrefix("q=")
    return browse(template.replace(Regex("\\{searchTerms\\??\\}"), encoded).replace(Regex("\\{[^}]*\\}"), ""))
  }

  override suspend fun files(entry: CatalogEntry): List<RemoteFile> = entry.files

  /** A search link may point at an OpenSearch description document instead of a template. */
  private suspend fun resolveSearchTemplate(url: String): String? {
    if ("{searchTerms" in url) return url
    return runCatching {
        val doc = parseXml(client.getText(url, headers))
        doc.elements("Url")
          .firstOrNull { it.getAttribute("type").contains("atom") }
          ?.getAttribute("template")
          ?.let { resolve(url, it) }
      }
      .getOrNull()
  }

  data class Feed(val page: CatalogPage, val searchUrl: String?)

  companion object {
    private const val ACQUISITION = "http://opds-spec.org/acquisition"
    private const val IMAGE = "http://opds-spec.org/image"

    fun parseFeed(xml: String, feedUrl: String, sourceId: String): Feed {
      val root = parseXml(xml)
      val feedLinks = root.childElements("link")
      val next = feedLinks.firstOrNull { it.getAttribute("rel") == "next" }?.href(feedUrl)
      val search = feedLinks.firstOrNull { it.getAttribute("rel") == "search" }?.href(feedUrl)

      val entries =
        root.childElements("entry").mapNotNull { entry ->
          val title = entry.childText("title") ?: return@mapNotNull null
          val links = entry.childElements("link")
          val files =
            links
              .filter { it.getAttribute("rel").startsWith(ACQUISITION) }
              .mapNotNull { link ->
                val href = link.href(feedUrl) ?: return@mapNotNull null
                val format =
                  ComicFormat.fromMimeType(link.getAttribute("type")) ?: ComicFormat.fromName(href)
                    ?: return@mapNotNull null
                val size = link.getAttribute("length").toLongOrNull()
                RemoteFile("${safeName(title)}.${format.name.lowercase()}", href, format, size)
              }
          val folder =
            if (files.isNotEmpty()) null
            else
              links
                .firstOrNull { l ->
                  l.getAttribute("type").contains("atom+xml") && l.getAttribute("rel").let { it != "self" && it != "alternate" }
                }
                ?.href(feedUrl)
          if (files.isEmpty() && folder == null) return@mapNotNull null
          val thumbnail = links.firstOrNull { it.getAttribute("rel").startsWith(IMAGE) }?.href(feedUrl)
          CatalogEntry(
            sourceId = sourceId,
            id = entry.childText("id") ?: (folder ?: files.first().url),
            title = title,
            subtitle = entry.childElements("author").firstOrNull()?.childText("name") ?: entry.childText("content"),
            // Inline data: URIs can be huge; only keep real links.
            thumbnailUrl = thumbnail?.takeIf { it.startsWith("http") },
            location = folder,
            files = files,
          )
        }
      return Feed(CatalogPage(entries, next), search)
    }

    fun safeName(title: String): String =
      title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(120).ifEmpty { "comic" }

    private fun parseXml(xml: String): Element {
      val factory = DocumentBuilderFactory.newInstance()
      factory.isNamespaceAware = true
      // Feeds are untrusted input: never resolve external entities.
      runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
      runCatching { factory.isExpandEntityReferences = false }
      return factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
    }

    private fun resolve(base: String, href: String): String? =
      // Search templates contain braces, which a strict URL parser rejects; resolve around them.
      if (href.startsWith("http://") || href.startsWith("https://")) href
      else base.toHttpUrl().resolve(href.replace("{", "%7B").replace("}", "%7D"))?.toString()?.replace("%7B", "{")?.replace("%7D", "}")

    private fun Element.href(base: String): String? = getAttribute("href").takeIf { it.isNotBlank() }?.let { resolve(base, it) }

    private fun Element.childElements(name: String): List<Element> {
      val out = ArrayList<Element>()
      var node = firstChild
      while (node != null) {
        if (node is Element && (node.localName ?: node.tagName) == name) out.add(node)
        node = node.nextSibling
      }
      return out
    }

    private fun Element.elements(name: String): List<Element> {
      val list = getElementsByTagNameNS("*", name)
      return (0 until list.length).mapNotNull { list.item(it) as? Element }
    }

    private fun Element.childText(name: String): String? =
      childElements(name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
  }
}
