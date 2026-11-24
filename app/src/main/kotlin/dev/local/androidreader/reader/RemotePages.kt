package dev.local.androidreader.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Fetches the pages of a book held on a server, one at a time, and keeps them on disk so a page
 * is only downloaded once. Has no Android dependencies.
 */
class RemotePageStore(
  private val client: OkHttpClient,
  private val headers: Map<String, String>,
  /** Folder for this book's pages. */
  private val dir: File,
  /** The URL of a page; `fallback` asks the server to convert it to a widely supported format. */
  private val pageUrl: (index: Int, fallback: Boolean) -> String,
) {
  /** Returns the page's file, downloading it first if needed. Blocking. */
  fun page(index: Int, fallback: Boolean = false): File {
    val file = File(dir, if (fallback) "$index.converted" else index.toString())
    if (file.exists() && file.length() > 0) return file
    dir.mkdirs()
    // Pages are fetched from several threads at once; a unique temp name keeps them apart.
    val temp = File.createTempFile("page$index", ".part", dir)
    try {
      val request = Request.Builder().url(pageUrl(index, fallback)).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
      client.newCall(request).execute().use { response ->
        if (response.code == 401 || response.code == 403) throw IOException("The server refused the sign-in details")
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} for page ${index + 1}")
        temp.outputStream().use { response.body.byteStream().copyTo(it) }
      }
      if (temp.length() == 0L) throw IOException("Page ${index + 1} came back empty")
      if (!temp.renameTo(file)) throw IOException("Could not save page ${index + 1}")
      return file
    } finally {
      temp.delete()
    }
  }

  companion object {
    /** A stable folder name for a book, safe for any server address or identifier. */
    fun folderName(key: String): String =
      MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Deletes the least recently used book folders until the cache is under [maxBytes].
     * [keep] is the folder about to be read and is never removed.
     */
    fun trim(cacheRoot: File, maxBytes: Long, keep: File? = null) {
      val folders = cacheRoot.listFiles { f -> f.isDirectory }.orEmpty().map { it to it.walkBottomUp().filter(File::isFile).sumOf(File::length) }
      var total = folders.sumOf { it.second }
      for ((folder, size) in folders.sortedBy { it.first.lastModified() }) {
        if (total <= maxBytes) break
        if (folder == keep) continue
        folder.deleteRecursively()
        total -= size
      }
    }
  }
}

/** A book read page by page from a server rather than from a file on the device. */
class RemotePages(override val pageCount: Int, private val store: RemotePageStore) : PageSource {

  override fun decode(index: Int, maxSide: Int): Bitmap =
    decodeFile(store.page(index), maxSide)
      // Some formats a server holds (JPEG XL, for one) cannot be shown directly; ask for a converted copy.
      ?: decodeFile(store.page(index, fallback = true), maxSide)
      ?: throw IOException("Page ${index + 1} is not a supported image")

  private fun decodeFile(file: File, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    val options =
      BitmapFactory.Options().apply {
        inSampleSize = PageSource.sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        inPreferredConfig = Bitmap.Config.RGB_565
      }
    return BitmapFactory.decodeFile(file.path, options)
  }

  override fun close() = Unit
}
