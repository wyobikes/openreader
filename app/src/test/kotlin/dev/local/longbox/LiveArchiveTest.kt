package dev.local.longbox

import dev.local.longbox.download.Downloader
import dev.local.longbox.library.Importer
import dev.local.longbox.reader.PageSource
import dev.local.longbox.source.ComicFormat
import dev.local.longbox.source.InternetArchiveSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Exercises search, file listing, download and import against the real Internet Archive.
 * Skipped unless run with `./gradlew testDebugUnitTest -Plive`.
 */
class LiveArchiveTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun searchDownloadAndImportARealComic() = runBlocking {
    assumeTrue(System.getProperty("live") == "true")
    val client = OkHttpClient()
    val source = InternetArchiveSource(client)

    val page = source.search("comics")
    println("LIVE search returned ${page.entries.size} entries, more=${page.nextLocation != null}")
    assertTrue(page.entries.isNotEmpty())

    // Pick the smallest comic archive among the first results to keep the test quick.
    val candidates =
      page.entries.take(12).flatMap { entry ->
        runCatching { source.files(entry) }.getOrDefault(emptyList())
          .filter { it.format == ComicFormat.CBZ && (it.sizeBytes ?: Long.MAX_VALUE) < 40_000_000 }
          .map { entry to it }
      }
    assertTrue("no small CBZ among first results", candidates.isNotEmpty())
    // Individual archive storage nodes are sometimes unreachable, so try a few items.
    val library = tmp.newFolder("library")
    val temp = File(tmp.root, "download")
    val file =
      candidates.sortedBy { it.second.sizeBytes!! }.take(6).firstNotNullOfOrNull { (entry, candidate) ->
        println("LIVE downloading \"${candidate.name}\" (${candidate.sizeBytes} bytes) from item ${entry.id}")
        try {
          Downloader(client).download(candidate.url, temp)
          candidate
        } catch (e: IOException) {
          println("LIVE   unreachable: ${e.message}")
          null
        }
      }
    assertTrue("none of the candidate files could be downloaded", file != null)
    assertTrue(temp.length() == file!!.sizeBytes)

    val imported = Importer.import(temp, library, file.name)
    val pages = ZipFile(imported.single()).use { zip -> PageSource.pageNames(zip.entries().asSequence().map { it.name }.toList()) }
    println("LIVE imported ${imported.single().name} with ${pages.size} pages; first=${pages.first()}")
    assertTrue(pages.isNotEmpty())
  }
}
