package dev.local.androidreader.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

enum class ComicFormat {
  CBZ,
  CBR,
  PDF,
  ZIP;

  companion object {
    /** Guesses the format from a file name or URL path; null when it is not a comic file. */
    fun fromName(name: String): ComicFormat? {
      // Only URLs have a query or fragment; in a plain file name "#" and "?" are ordinary characters.
      val path = if ("://" in name) name.substringBefore('#').substringBefore('?') else name
      return when (path.substringAfterLast('.').lowercase()) {
        "cbz" -> CBZ
        "cbr" -> CBR
        "pdf" -> PDF
        "zip" -> ZIP
        else -> null
      }
    }

    fun fromMimeType(type: String): ComicFormat? =
      when (type.substringBefore(';').trim().lowercase()) {
        "application/vnd.comicbook+zip", "application/x-cbz" -> CBZ
        "application/vnd.comicbook-rar", "application/x-cbr" -> CBR
        "application/pdf" -> PDF
        "application/zip" -> ZIP
        else -> null
      }
  }
}

@Serializable
data class RemoteFile(
  val name: String,
  val url: String,
  val format: ComicFormat,
  val sizeBytes: Long? = null,
)

/** One row in a catalogue: either a folder to open ([location] set) or a comic with files. */
@Serializable
data class CatalogEntry(
  val sourceId: String,
  val id: String,
  val title: String,
  val subtitle: String? = null,
  val thumbnailUrl: String? = null,
  /** Set for folders: pass to [ComicSource.browse]. */
  val location: String? = null,
  /** Files already known from the listing; empty means call [ComicSource.files]. */
  val files: List<RemoteFile> = emptyList(),
  /** Set when the source can show this book page by page without downloading the file. */
  val onlineBookId: String? = null,
) {
  val isFolder: Boolean
    get() = location != null
}

data class CatalogPage(val entries: List<CatalogEntry>, val nextLocation: String? = null)

/** A place comics can be listed and downloaded from. Add a source by implementing this. */
interface ComicSource {
  val id: String
  val name: String
  val supportsSearch: Boolean

  /** Headers (sign-in details) that requests to this source need, including thumbnails and downloads. */
  val requestHeaders: Map<String, String>
    get() = emptyMap()

  /** Lists a catalogue page. `null` is the source's start page. */
  suspend fun browse(location: String? = null): CatalogPage

  suspend fun search(query: String): CatalogPage

  /** The downloadable comic files for a non-folder entry. */
  suspend fun files(entry: CatalogEntry): List<RemoteFile>
}

internal suspend fun OkHttpClient.getText(url: String, headers: Map<String, String> = emptyMap()): String =
  withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
    newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${request.url.host}")
      response.body.string()
    }
  }
