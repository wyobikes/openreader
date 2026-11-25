package dev.local.androidreader

import android.app.Application
import dev.local.androidreader.download.DownloadQueue
import dev.local.androidreader.download.Downloader
import dev.local.androidreader.library.KeystoreSecretBox
import dev.local.androidreader.library.LibraryRepository
import dev.local.androidreader.library.SecretBox
import dev.local.androidreader.library.SecretSealer
import dev.local.androidreader.source.ComicSource
import dev.local.androidreader.source.InternetArchiveSource
import dev.local.androidreader.source.KomgaSource
import dev.local.androidreader.source.OpdsSource
import dev.local.androidreader.source.ServerOrigin.keepSignInsOnTheirServer
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Holds the app's long-lived objects; screens reach them through the application context. */
class ReaderApp : Application() {
  val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      // A redirect to another server must not carry a sign-in with it.
      .keepSignInsOnTheirServer()
      .addInterceptor { chain ->
        // Browser downloads bring the page's own User-Agent; only fill it in when absent.
        val request = chain.request()
        chain.proceed(
          if (request.header("User-Agent") != null) request
          else request.newBuilder().header("User-Agent", "OpenReader/1.0").build()
        )
      }
      .build()
  }

  /** Encrypts sign-ins and cookies before they are stored, with a key held by the phone's keystore. */
  val secrets: SecretBox by lazy { KeystoreSecretBox() }

  val library: LibraryRepository by lazy {
    // External app storage, so files are reachable over USB; internal storage as a fallback.
    val root = getExternalFilesDir(null) ?: filesDir
    LibraryRepository(File(root, "comics"), File(filesDir, "library.json"), secrets)
  }

  val downloader: Downloader by lazy { Downloader(httpClient) }

  val downloads: DownloadQueue by lazy { DownloadQueue(this, SecretSealer(secrets)) }

  private val internetArchive by lazy { InternetArchiveSource(httpClient) }

  /** Built-in sources followed by the user's OPDS catalogues. */
  fun sources(): List<ComicSource> =
    listOf<ComicSource>(internetArchive) +
      library.komgaServers.value.map { KomgaSource(httpClient, it) } +
      library.opdsCatalogs.value.map { OpdsSource(httpClient, it) }
}
