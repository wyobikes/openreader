package dev.local.androidreader.download

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import kotlin.coroutines.cancellation.CancellationException

/** Downloads a URL to a file, resuming from a `.part` file left by an interrupted attempt. */
class Downloader(private val client: OkHttpClient) {

  data class Result(val file: File, val suggestedName: String?)

  /** Blocking; call from an IO thread. [onProgress] receives bytes done and the total if known. */
  fun download(
    url: String,
    dest: File,
    headers: Map<String, String> = emptyMap(),
    isCancelled: () -> Boolean = { false },
    onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> },
  ): Result {
    val part = File(dest.parentFile, dest.name + ".part")
    dest.parentFile?.mkdirs()
    var allowResume = true
    while (true) {
      val existing = if (allowResume && part.exists()) part.length() else 0L
      val request =
        Request.Builder().url(url)
          .apply { headers.forEach { (k, v) -> header(k, v) } }
          .apply { if (existing > 0) header("Range", "bytes=$existing-") }
          .build()
      client.newCall(request).execute().use { response ->
        if (response.code == 416 && existing > 0) {
          // The partial file no longer matches what the server has; start over.
          part.delete()
          allowResume = false
          return@use
        }
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${request.url.host}")
        val resumed = response.code == 206 && existing > 0
        val length = response.body.contentLength().takeIf { it >= 0 }
        val total = length?.let { if (resumed) existing + it else it }
        var done = if (resumed) existing else 0L
        onProgress(done, total)
        FileOutputStream(part, resumed).use { out ->
          val source = response.body.byteStream()
          val buffer = ByteArray(64 * 1024)
          var lastReport = done
          while (true) {
            if (isCancelled()) throw CancellationException("Download cancelled")
            val read = source.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            done += read
            if (done - lastReport >= 256 * 1024) {
              onProgress(done, total)
              lastReport = done
            }
          }
        }
        if (total != null && done < total) throw IOException("Connection closed after $done of $total bytes")
        onProgress(done, total ?: done)
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("Could not move download into place")
        return Result(dest, fileNameFromContentDisposition(response.header("Content-Disposition")))
      }
    }
  }

  companion object {
    fun fileNameFromContentDisposition(header: String?): String? {
      if (header == null) return null
      Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)").find(header)?.let {
        return runCatching { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") }.getOrNull()?.let(::baseName)
      }
      return Regex("filename\\s*=\\s*\"?([^\";]+)\"?").find(header)?.groupValues?.get(1)?.trim()?.let(::baseName)
    }

    /** The last path segment of a URL, decoded; null when the URL has no usable name. */
    fun fileNameFromUrl(url: String): String? =
      url.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotEmpty() }?.let(::baseName)

    /** Strips any directory part so a server-supplied name can never escape the target folder. */
    private fun baseName(name: String): String? =
      name.replace('\\', '/').substringAfterLast('/').trim().takeIf { it.isNotEmpty() && it != "." && it != ".." }
  }
}
