package dev.local.androidreader.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Serializable
data class CalibreConfig(
  val name: String,
  val url: String,
  val username: String = "",
  val password: String = "",
) {
  val id: String
    get() = "calibre:$url"

  /** Whether [candidate] is an address on this server, for deciding which sign-in to use. */
  fun covers(candidate: HttpUrl): Boolean {
    val base = runCatching { url.toHttpUrl() }.getOrNull() ?: return false
    return base.host == candidate.host && base.port == candidate.port && candidate.encodedPath.startsWith(base.encodedPath.trimEnd('/'))
  }
}

/**
 * A Calibre content server (what `calibre-server`, or "Sharing over the net" in Calibre, runs),
 * browsed through its JSON interface. Sign-in is answered by [ServerAuthenticator], because
 * Calibre asks for Digest sign-in by default.
 */
class CalibreSource(private val client: OkHttpClient, private val config: CalibreConfig) : ComicSource {
  override val id = config.id
  override val name = config.name
  override val supportsSearch = true

  private val base = config.url.trimEnd('/')
  private var defaultLibrary: String? = null

  override suspend fun browse(location: String?): CatalogPage {
    if (location == null) {
      val (libraries, default) = parseLibraries(client.getText("$base/ajax/library-info"))
      defaultLibrary = default ?: libraries.keys.firstOrNull()
      // With a single library there is nothing to choose, so go straight to its books.
      if (libraries.size <= 1) return books(defaultLibrary.orEmpty(), 0, "")
      return CatalogPage(
        libraries.map { (libraryId, label) -> CatalogEntry(id, "library:$libraryId", label, location = encodeLocation(libraryId, 0, "")) }
      )
    }
    val (library, offset, query) = decodeLocation(location)
    return books(library, offset, query)
  }

  override suspend fun search(query: String): CatalogPage {
    if (defaultLibrary == null) browse(null)
    return books(defaultLibrary.orEmpty(), 0, query.trim())
  }

  override suspend fun files(entry: CatalogEntry): List<RemoteFile> = entry.files

  private suspend fun books(library: String, offset: Int, query: String): CatalogPage {
    val searchUrl =
      url("ajax", "search", library)
        .addQueryParameter("num", PAGE_SIZE.toString())
        .addQueryParameter("offset", offset.toString())
        // Newest additions first when browsing; best title order when searching.
        .addQueryParameter("sort", if (query.isEmpty()) "timestamp" else "title")
        .addQueryParameter("sort_order", if (query.isEmpty()) "desc" else "asc")
        .apply { if (query.isNotEmpty()) addQueryParameter("query", query) }
        .build()
    val found = parseSearch(client.getText(searchUrl.toString()))
    if (found.ids.isEmpty()) return CatalogPage(emptyList())
    val booksUrl = url("ajax", "books", library).addQueryParameter("ids", found.ids.joinToString(",")).build()
    val entries = parseBooks(client.getText(booksUrl.toString()), found.ids, library, id, base)
    val next = offset + found.ids.size
    return CatalogPage(entries, if (next < found.total) encodeLocation(library, next, query) else null)
  }

  private fun url(vararg segments: String): HttpUrl.Builder =
    base.toHttpUrl().newBuilder().apply { segments.filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }

  data class SearchResult(val ids: List<String>, val total: Int)

  companion object {
    const val PAGE_SIZE = 40
    private val json = Json { ignoreUnknownKeys = true }

    /** Calibre format names the app can open, and what each is treated as. */
    private val READABLE =
      linkedMapOf("epub" to ComicFormat.EPUB, "cbz" to ComicFormat.CBZ, "cbr" to ComicFormat.CBR, "pdf" to ComicFormat.PDF, "zip" to ComicFormat.ZIP)

    fun encodeLocation(library: String, offset: Int, query: String) = "$offset\n$library\n$query"

    fun decodeLocation(location: String): Triple<String, Int, String> {
      val parts = location.split('\n', limit = 3)
      return Triple(parts.getOrElse(1) { "" }, parts[0].toIntOrNull() ?: 0, parts.getOrElse(2) { "" })
    }

    private fun root(body: String): JsonObject =
      runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        ?: throw WrongServerException("The address did not answer like a Calibre server")

    /** Library ids mapped to their names, and the id of the default library. */
    fun parseLibraries(body: String): Pair<Map<String, String>, String?> {
      val o = root(body)
      val map = o["library_map"] as? JsonObject ?: throw WrongServerException("The address did not return a Calibre library list")
      return map.entries.associate { (key, value) -> key to (value.text() ?: key) } to o["default_library"].text()
    }

    fun parseSearch(body: String): SearchResult {
      val o = root(body)
      val ids = (o["book_ids"] as? JsonArray ?: throw WrongServerException("The address did not return a Calibre book list")).mapNotNull { it.text() }
      return SearchResult(ids, o["total_num"].text()?.toIntOrNull() ?: ids.size)
    }

    /** Book details arrive keyed by id, in no order; [ids] gives the order to show them in. */
    fun parseBooks(body: String, ids: List<String>, library: String, sourceId: String, base: String): List<CatalogEntry> {
      val books = root(body)
      val serverUrl = base.toHttpUrl()
      fun get(what: String, bookId: String): HttpUrl.Builder =
        serverUrl.newBuilder().addPathSegment("get").addPathSegment(what).addPathSegment(bookId).apply {
          if (library.isNotEmpty()) addPathSegment(library)
        }
      return ids.mapNotNull { bookId ->
        val o = books[bookId] as? JsonObject ?: return@mapNotNull null
        val title = o["title"].text() ?: "Book $bookId"
        val authors = (o["authors"] as? JsonArray).orEmpty().mapNotNull { it.text() }
        val formats = (o["formats"] as? JsonArray).orEmpty().mapNotNull { it.text()?.lowercase() }
        val sizes = o["format_metadata"] as? JsonObject
        val fileBase = OpdsSource.safeName(if (authors.isEmpty()) title else "$title - ${authors.first()}")
        val files =
          READABLE.filterKeys { it in formats }.map { (format, kind) ->
            val size = ((sizes?.get(format) as? JsonObject)?.get("size")).text()?.toLongOrNull()
            RemoteFile("$fileBase.$format", get(format, bookId).build().toString(), kind, size)
          }
        val series = o["series"].text()?.let { name -> o["series_index"].text()?.toDoubleOrNull()?.let { "$name #${trimIndex(it)}" } ?: name }
        // A book held only in formats the app cannot open is still listed, saying why.
        val unreadable = if (files.isEmpty()) "Only in ${formats.joinToString(", ") { it.uppercase() }.ifEmpty { "no format" }}" else null
        CatalogEntry(
          sourceId = sourceId,
          id = "book:$library:$bookId",
          title = title,
          subtitle = listOfNotNull(authors.joinToString(", ").ifEmpty { null }, series, unreadable).joinToString(" · ").ifEmpty { null },
          thumbnailUrl = get("thumb", bookId).addQueryParameter("sz", "300x400").build().toString(),
          files = files,
        )
      }
    }

    private fun trimIndex(index: Double): String = if (index == index.toLong().toDouble()) index.toLong().toString() else index.toString()

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
  }
}
