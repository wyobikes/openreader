package dev.local.androidreader.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Internet Archive items that contain a comic archive and carry a Creative Commons or
 * public-domain licence mark.
 */
class InternetArchiveSource(
  private val client: OkHttpClient,
  private val baseUrl: String = "https://archive.org",
) : ComicSource {
  override val id = ID
  override val name = "Internet Archive"
  override val supportsSearch = true

  override suspend fun browse(location: String?): CatalogPage {
    val (query, page) = decodeLocation(location)
    val url =
      "$baseUrl/advancedsearch.php".toHttpUrl().newBuilder()
        .addQueryParameter("q", buildQuery(query))
        .apply { listOf("identifier", "title", "creator", "year").forEach { addQueryParameter("fl[]", it) } }
        .addQueryParameter("sort[]", "downloads desc")
        .addQueryParameter("rows", PAGE_SIZE.toString())
        .addQueryParameter("page", page.toString())
        .addQueryParameter("output", "json")
        .build()
    return parseSearch(client.getText(url.toString()), query, page, baseUrl)
  }

  override suspend fun search(query: String): CatalogPage = browse(encodeLocation(query.trim(), 1))

  override suspend fun files(entry: CatalogEntry): List<RemoteFile> =
    parseFiles(entry.id, client.getText("$baseUrl/metadata/${entry.id}"), baseUrl)

  companion object {
    const val ID = "internet-archive"
    const val PAGE_SIZE = 30
    private const val SCOPE =
      "mediatype:texts AND format:(\"Comic Book ZIP\" OR \"Comic Book RAR\") AND " +
        "licenseurl:(*creativecommons* OR *publicdomain*)"
    private val json = Json { ignoreUnknownKeys = true }

    fun buildQuery(userQuery: String): String {
      // Strip characters that have meaning in the archive's query syntax.
      val cleaned = userQuery.replace(Regex("[^\\p{L}\\p{N}\\s'-]"), " ").trim()
      return if (cleaned.isEmpty()) SCOPE else "($cleaned) AND $SCOPE"
    }

    fun encodeLocation(query: String, page: Int) = "$page\n$query"

    fun decodeLocation(location: String?): Pair<String, Int> {
      if (location == null) return "" to 1
      val page = location.substringBefore('\n').toIntOrNull() ?: 1
      return location.substringAfter('\n', "") to page
    }

    fun parseSearch(body: String, query: String, page: Int, baseUrl: String = "https://archive.org"): CatalogPage {
      val response = json.parseToJsonElement(body).jsonObject["response"]?.jsonObject ?: return CatalogPage(emptyList())
      val total = response["numFound"].text()?.toLongOrNull() ?: 0
      val entries =
        (response["docs"] as? JsonArray).orEmpty().mapNotNull { doc ->
          val o = doc as? JsonObject ?: return@mapNotNull null
          val identifier = o["identifier"].text() ?: return@mapNotNull null
          CatalogEntry(
            sourceId = ID,
            id = identifier,
            title = o["title"].text() ?: identifier,
            subtitle = listOfNotNull(o["creator"].text(), o["year"].text()).joinToString(" · ").ifEmpty { null },
            thumbnailUrl = "$baseUrl/services/img/$identifier",
          )
        }
      val hasMore = page.toLong() * PAGE_SIZE < total
      return CatalogPage(entries, if (hasMore) encodeLocation(query, page + 1) else null)
    }

    fun parseFiles(identifier: String, body: String, baseUrl: String = "https://archive.org"): List<RemoteFile> {
      val files = json.parseToJsonElement(body).jsonObject["files"] as? JsonArray ?: return emptyList()
      val base = "$baseUrl/download".toHttpUrl()
      return files
        .mapNotNull { f ->
          val o = f as? JsonObject ?: return@mapNotNull null
          val name = o["name"].text() ?: return@mapNotNull null
          val format = ComicFormat.fromName(name) ?: return@mapNotNull null
          // Archive-generated zips (page images, OCR) are not comics; keep uploader originals only.
          val original = o["source"].text() == "original"
          if (format == ComicFormat.ZIP && !original) return@mapNotNull null
          val url = base.newBuilder().addPathSegment(identifier).addPathSegments(name).build()
          RemoteFile(name.substringAfterLast('/'), url.toString(), format, o["size"].text()?.toLongOrNull()) to original
        }
        // Original comic archives first, derived PDFs last.
        .sortedWith(compareBy({ !it.second }, { it.first.format.ordinal }, { it.first.name }))
        .map { it.first }
    }

    /** Archive metadata fields may be a string, a number, or a list of either. */
    private fun JsonElement?.text(): String? =
      when (this) {
        is JsonPrimitive -> content.takeIf { it.isNotBlank() && it != "null" }
        is JsonArray -> jsonArray.firstOrNull().text()
        else -> null
      }
  }
}
