package dev.local.androidreader.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import dev.local.androidreader.reader.PageSource
import dev.local.androidreader.reader.RemotePageStore
import dev.local.androidreader.reader.RemotePages
import java.io.File
import java.io.IOException

@Serializable
data class KomgaConfig(
  val name: String,
  val url: String,
  val username: String = "",
  val password: String = "",
  /** Used instead of the username and password when set. */
  val apiKey: String = "",
) {
  val id: String
    get() = "komga:$url"
}

/**
 * A Komga server, browsed through its REST API: libraries, then series, then books.
 * Written against the API of Komga 1.30 (the `/list` search endpoints).
 */
class KomgaSource(private val client: OkHttpClient, private val config: KomgaConfig) : ComicSource {
  override val id = config.id
  override val name = config.name
  override val supportsSearch = true

  private val base = config.url.trimEnd('/')

  override val requestHeaders: Map<String, String>
    get() =
      when {
        config.apiKey.isNotBlank() -> mapOf("X-API-Key" to config.apiKey.trim())
        config.username.isNotEmpty() -> mapOf("Authorization" to Credentials.basic(config.username, config.password))
        else -> emptyMap()
      }

  override suspend fun browse(location: String?): CatalogPage {
    if (location == null) return parseLibraries(client.getText("$base/api/v1/libraries", requestHeaders), id)
    val (kind, target, page) = decodeLocation(location)
    return when (kind) {
      LIBRARY -> parseSeries(post("series/list", page, "metadata.titleSort,asc", condition("libraryId", target)), location)
      SERIES -> parseBooks(post("books/list", page, "metadata.numberSort,asc", condition("seriesId", target)), location)
      SEARCH -> parseSeries(post("series/list", page, null, buildJsonObject { put("fullTextSearch", target) }), location)
      else -> throw IOException("Unknown location")
    }
  }

  override suspend fun search(query: String): CatalogPage = browse(encodeLocation(SEARCH, query.trim(), 0))

  override suspend fun files(entry: CatalogEntry): List<RemoteFile> = entry.files

  /**
   * Opens a book for reading straight from the server. Asks Komga for the page list, then pages
   * are fetched as they are turned to and cached under [cacheRoot].
   */
  suspend fun openBook(bookId: String, cacheRoot: File): PageSource {
    val pageCount = parsePageCount(client.getText("$base/api/v1/books/$bookId/pages", requestHeaders))
    if (pageCount == 0) throw IOException("The server lists no pages for this book")
    val dir = File(cacheRoot, RemotePageStore.folderName("$id|$bookId"))
    withContext(Dispatchers.IO) {
      RemotePageStore.trim(cacheRoot, PAGE_CACHE_BYTES, keep = dir)
      dir.mkdirs()
      // Marks the book as recently read, so it is the last to be cleared from the cache.
      dir.setLastModified(System.currentTimeMillis())
    }
    return RemotePages(pageCount, RemotePageStore(client, requestHeaders, dir) { index, fallback -> pageUrl(base, bookId, index, fallback) })
  }

  private fun parseSeries(body: String, location: String) = parseSeries(body, location, id, base)

  private fun parseBooks(body: String, location: String) = parseBooks(body, location, id, base)

  private suspend fun post(path: String, page: Int, sort: String?, body: JsonObject): String =
    withContext(Dispatchers.IO) {
      val url =
        "$base/api/v1/$path".toHttpUrl().newBuilder()
          .addQueryParameter("page", page.toString())
          .addQueryParameter("size", PAGE_SIZE.toString())
          .apply { if (sort != null) addQueryParameter("sort", sort) }
          .build()
      val request =
        Request.Builder().url(url)
          .apply { requestHeaders.forEach { (k, v) -> header(k, v) } }
          .post(body.toString().toRequestBody("application/json".toMediaType()))
          .build()
      client.newCall(request).execute().use { response ->
        if (response.code == 401 || response.code == 403) throw IOException("Komga refused the sign-in details")
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${request.url.host}")
        response.body.string()
      }
    }

  companion object {
    const val PAGE_SIZE = 40
    const val LIBRARY = "library"
    const val SERIES = "series"
    const val SEARCH = "search"
    private val json = Json { ignoreUnknownKeys = true }

    /** How much disk the pages of books read online may take before older books are cleared. */
    const val PAGE_CACHE_BYTES = 400L * 1024 * 1024

    /** Komga numbers pages from 1. `convert=jpeg` has the server re-encode formats the phone cannot show. */
    fun pageUrl(base: String, bookId: String, index: Int, convert: Boolean): String =
      "$base/api/v1/books/$bookId/pages/${index + 1}" + if (convert) "?convert=jpeg" else ""

    fun parsePageCount(body: String): Int =
      (runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray)?.size
        ?: throw WrongServerException("The server did not return a page list")

    fun condition(field: String, value: String): JsonObject = buildJsonObject {
      putJsonObject("condition") {
        putJsonObject(field) {
          put("operator", "is")
          put("value", value)
        }
      }
    }

    fun encodeLocation(kind: String, target: String, page: Int) = "$kind\n$page\n$target"

    fun decodeLocation(location: String): Triple<String, String, Int> {
      val parts = location.split('\n', limit = 3)
      return Triple(parts[0], parts.getOrElse(2) { "" }, parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    fun parseLibraries(body: String, sourceId: String): CatalogPage {
      val libraries =
        runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray
          ?: throw WrongServerException("The address did not return a Komga library list")
      return CatalogPage(
        libraries.mapNotNull { element ->
          val o = element as? JsonObject ?: return@mapNotNull null
          val libraryId = o["id"].text() ?: return@mapNotNull null
          CatalogEntry(sourceId, "library:$libraryId", o["name"].text() ?: "Library", location = encodeLocation(LIBRARY, libraryId, 0))
        }
      )
    }

    fun parseSeries(body: String, location: String, sourceId: String, base: String): CatalogPage {
      val root = json.parseToJsonElement(body).jsonObject
      val entries =
        (root["content"] as? JsonArray).orEmpty().mapNotNull { element ->
          val o = element as? JsonObject ?: return@mapNotNull null
          val seriesId = o["id"].text() ?: return@mapNotNull null
          val count = o["booksCount"].text()?.toIntOrNull()
          CatalogEntry(
            sourceId = sourceId,
            id = "series:$seriesId",
            title = (o["metadata"] as? JsonObject)?.get("title").text() ?: o["name"].text() ?: "Series",
            subtitle = count?.let { if (it == 1) "1 book" else "$it books" },
            thumbnailUrl = "$base/api/v1/series/$seriesId/thumbnail",
            location = encodeLocation(SERIES, seriesId, 0),
          )
        }
      return CatalogPage(entries, nextPage(root, location))
    }

    fun parseBooks(body: String, location: String, sourceId: String, base: String): CatalogPage {
      val root = json.parseToJsonElement(body).jsonObject
      val entries =
        (root["content"] as? JsonArray).orEmpty().mapNotNull { element ->
          val o = element as? JsonObject ?: return@mapNotNull null
          val bookId = o["id"].text() ?: return@mapNotNull null
          val path = o["url"].text().orEmpty()
          val media = o["media"] as? JsonObject
          // Books in a format the app cannot read are left out.
          val format =
            ComicFormat.fromName(path.substringAfterLast('/'))
              ?: ComicFormat.fromMimeType(media?.get("mediaType").text().orEmpty())
              ?: return@mapNotNull null
          val metadata = o["metadata"] as? JsonObject
          val title = metadata?.get("title").text() ?: o["name"].text() ?: "Book"
          val fileName =
            path.substringAfterLast('/').takeIf { ComicFormat.fromName(it) != null }
              ?: "${OpdsSource.safeName(o["name"].text() ?: title)}.${format.name.lowercase()}"
          val pages = media?.get("pagesCount").text()?.toIntOrNull()
          CatalogEntry(
            sourceId = sourceId,
            id = "book:$bookId",
            title = listOfNotNull(metadata?.get("number").text()?.let { "#$it" }, title).joinToString(" · "),
            subtitle = listOfNotNull(o["seriesTitle"].text(), pages?.let { "$it pages" }).joinToString(" · ").ifEmpty { null },
            thumbnailUrl = "$base/api/v1/books/$bookId/thumbnail",
            // Page-by-page reading is for books made of images; an EPUB has to be downloaded.
            onlineBookId = bookId.takeIf { format != ComicFormat.EPUB },
            files = listOf(RemoteFile(fileName, "$base/api/v1/books/$bookId/file", format, o["sizeBytes"].text()?.toLongOrNull())),
          )
        }
      return CatalogPage(entries, nextPage(root, location))
    }

    /** Komga pages are zero-based and say whether they are the last one. */
    private fun nextPage(root: JsonObject, location: String): String? {
      if (root["last"].text() != "false") return null
      val (kind, target, page) = decodeLocation(location)
      return encodeLocation(kind, target, page + 1)
    }

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
  }
}
