package dev.local.longbox

import android.app.Application
import dev.local.longbox.download.DownloadQueue
import dev.local.longbox.download.Downloader
import dev.local.longbox.library.LibraryRepository
import dev.local.longbox.source.ComicSource
import dev.local.longbox.source.InternetArchiveSource
import dev.local.longbox.source.OpdsSource
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Holds the app's long-lived objects; screens reach them through the application context. */
class LongboxApp : Application() {
  val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", "Longbox/1.0 (Android comic reader)").build())
      }
      .build()
  }

  val library: LibraryRepository by lazy {
    // External app storage, so files are reachable over USB; internal storage as a fallback.
    val root = getExternalFilesDir(null) ?: filesDir
    LibraryRepository(File(root, "comics"), File(filesDir, "library.json"))
  }

  val downloader: Downloader by lazy { Downloader(httpClient) }

  val downloads: DownloadQueue by lazy { DownloadQueue(this) }

  private val internetArchive by lazy { InternetArchiveSource(httpClient) }

  /** Built-in sources followed by the user's OPDS catalogues. */
  fun sources(): List<ComicSource> = listOf<ComicSource>(internetArchive) + library.opdsCatalogs.value.map { OpdsSource(httpClient, it) }
}
