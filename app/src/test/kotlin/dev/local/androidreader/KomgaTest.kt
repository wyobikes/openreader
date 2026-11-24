package dev.local.androidreader

import dev.local.androidreader.source.ComicFormat
import dev.local.androidreader.source.KomgaConfig
import dev.local.androidreader.source.KomgaSource
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class KomgaTest {
  private val server = MockWebServer()

  @Before fun start() = server.start()

  @After fun stop() = server.close()

  private fun source(config: KomgaConfig.() -> KomgaConfig = { this }) =
    KomgaSource(OkHttpClient(), KomgaConfig("Home", server.url("/komga/").toString()).config())

  private val seriesPage =
    """{"content":[
      {"id":"S1","name":"folder name","booksCount":12,"metadata":{"title":"Planet Stories"}},
      {"id":"S2","name":"Only Name","booksCount":1,"metadata":{"title":""}}],
      "last":false,"number":0}"""

  private val booksPage =
    """{"content":[
      {"id":"B1","name":"Planet Stories 001","url":"/data/Planet Stories/Planet Stories #001.cbz","sizeBytes":28371918,
       "seriesTitle":"Planet Stories","media":{"mediaType":"application/zip","pagesCount":36},
       "metadata":{"title":"The Golden Amazons","number":"1"}},
      {"id":"B2","name":"No Extension","url":"","sizeBytes":5,
       "media":{"mediaType":"application/pdf","pagesCount":0},"metadata":{"title":"No Extension","number":""}},
      {"id":"B3","name":"A novel","url":"/data/novel.epub","media":{"mediaType":"application/epub+zip"},"metadata":{"title":"A novel"}}],
      "last":true}"""

  @Test
  fun librariesAreTheStartPageAndApiKeyIsSent() = runBlocking {
    server.enqueue(MockResponse.Builder().body("""[{"id":"L1","name":"Comics"},{"id":"L2","name":"Manga"}]""").build())
    val page = source { copy(apiKey = " secret ", username = "ignored", password = "ignored") }.browse(null)
    assertEquals(listOf("Comics", "Manga"), page.entries.map { it.title })
    assertTrue(page.entries.all { it.isFolder })
    assertNull(page.nextLocation)
    val request = server.takeRequest()
    assertEquals("/komga/api/v1/libraries", request.url.encodedPath)
    assertEquals("secret", request.headers["X-API-Key"])
    assertNull(request.headers["Authorization"])
  }

  @Test
  fun libraryListsSeriesWithPagingAndBasicAuth() = runBlocking {
    server.enqueue(MockResponse.Builder().body("""[{"id":"L1","name":"Comics"}]""").build())
    server.enqueue(MockResponse.Builder().body(seriesPage).build())
    val komga = source { copy(username = "me@example.org", password = "pw") }
    val library = komga.browse(null).entries.single()
    val page = komga.browse(library.location)

    server.takeRequest()
    val request = server.takeRequest()
    assertEquals("POST", request.method)
    assertEquals("/komga/api/v1/series/list", request.url.encodedPath)
    assertEquals("0", request.url.queryParameter("page"))
    assertEquals("metadata.titleSort,asc", request.url.queryParameter("sort"))
    assertEquals("""{"condition":{"libraryId":{"operator":"is","value":"L1"}}}""", request.body!!.utf8())
    assertTrue(request.headers["Authorization"]!!.startsWith("Basic "))

    assertEquals(listOf("Planet Stories", "Only Name"), page.entries.map { it.title })
    assertEquals(listOf("12 books", "1 book"), page.entries.map { it.subtitle })
    assertTrue(page.entries[0].thumbnailUrl!!.endsWith("/komga/api/v1/series/S1/thumbnail"))
    assertEquals(Triple(KomgaSource.LIBRARY, "L1", 1), KomgaSource.decodeLocation(page.nextLocation!!))
  }

  @Test
  fun seriesListsReadableBooksWithDownloadLinks() = runBlocking {
    server.enqueue(MockResponse.Builder().body(booksPage).build())
    val komga = source()
    val page = komga.browse(KomgaSource.encodeLocation(KomgaSource.SERIES, "S1", 2))

    val request = server.takeRequest()
    assertEquals("/komga/api/v1/books/list", request.url.encodedPath)
    assertEquals("2", request.url.queryParameter("page"))
    assertEquals("metadata.numberSort,asc", request.url.queryParameter("sort"))
    assertEquals("""{"condition":{"seriesId":{"operator":"is","value":"S1"}}}""", request.body!!.utf8())

    assertEquals(3, page.entries.size)
    assertNull(page.nextLocation)
    val (first, second, third) = page.entries
    // Image-based books can be read online; an EPUB is download only.
    assertEquals("B1", first.onlineBookId)
    assertNull(third.onlineBookId)
    assertEquals(ComicFormat.EPUB, third.files.single().format)
    assertEquals("novel.epub", third.files.single().name)
    assertEquals("#1 · The Golden Amazons", first.title)
    assertEquals("Planet Stories · 36 pages", first.subtitle)
    val file = komga.files(first).single()
    assertEquals("Planet Stories #001.cbz", file.name)
    assertEquals(ComicFormat.CBZ, file.format)
    assertEquals(28371918L, file.sizeBytes)
    assertTrue(file.url.endsWith("/komga/api/v1/books/B1/file"))
    assertEquals("No Extension", second.title)
    assertEquals("No Extension.pdf", second.files.single().name)
  }

  @Test
  fun searchSendsFullTextQuery() = runBlocking {
    server.enqueue(MockResponse.Builder().body("""{"content":[],"last":true}""").build())
    val page = source().search("  planet \"stories\" ")
    assertTrue(page.entries.isEmpty())
    val request = server.takeRequest()
    assertEquals("/komga/api/v1/series/list", request.url.encodedPath)
    assertNull(request.url.queryParameter("sort"))
    assertEquals("""{"fullTextSearch":"planet \"stories\""}""", request.body!!.utf8())
  }

  @Test
  fun rejectedSignInGivesAClearMessage() {
    server.enqueue(MockResponse.Builder().code(401).build())
    val error =
      assertThrows(IOException::class.java) {
        runBlocking { source().browse(KomgaSource.encodeLocation(KomgaSource.LIBRARY, "L1", 0)) }
      }
    assertEquals("Komga refused the sign-in details", error.message)
  }
}
