package dev.local.androidreader

import android.app.Application
import dev.local.androidreader.download.DownloadQueue
import dev.local.androidreader.download.Downloader
import dev.local.androidreader.library.LibraryRepository
import dev.local.androidreader.source.ComicSource
import dev.local.androidreader.source.InternetArchiveSource
import dev.local.androidreader.source.CalibreConfig
import dev.local.androidreader.source.CalibreSource
import dev.local.androidreader.source.KomgaSource
import dev.local.androidreader.source.OpdsSource
import dev.local.androidreader.source.ServerAuthenticator
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Holds the app's long-lived objects; screens reach them through the application context. */
class ReaderApp : Application() {
  val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      // Calibre servers ask for a sign-in that has to be computed per request.
      .authenticator(ServerAuthenticator { url -> calibreSignIn(url) })
      .addInterceptor { chain ->
        // Browser downloads bring the page's own User-Agent; only fill it in when absent.
        val request = chain.request()
        chain.proceed(
          if (request.header("User-Agent") != null) request
          else request.newBuilder().header("User-Agent", "AndroidReader/1.0").build()
        )
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

  /** A server being tried in the add-server dialog, whose sign-in is not saved yet. */
  @Volatile var calibreBeingTested: CalibreConfig? = null

  private fun calibreSignIn(url: HttpUrl): Pair<String, String>? =
    (listOfNotNull(calibreBeingTested) + library.calibreServers.value).firstOrNull { it.covers(url) }?.let { it.username to it.password }

  /** Built-in sources followed by the user's OPDS catalogues. */
  fun sources(): List<ComicSource> =
    listOf<ComicSource>(internetArchive) +
      library.komgaServers.value.map { KomgaSource(httpClient, it) } +
      library.calibreServers.value.map { CalibreSource(httpClient, it) } +
      library.opdsCatalogs.value.map { OpdsSource(httpClient, it) }
}
