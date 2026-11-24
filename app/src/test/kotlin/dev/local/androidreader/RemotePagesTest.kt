package dev.local.androidreader

import dev.local.androidreader.reader.RemotePageStore
import dev.local.androidreader.source.KomgaSource
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RemotePagesTest {
  @get:Rule val tmp = TemporaryFolder()
  private val server = MockWebServer()

  @Before fun start() = server.start()

  @After fun stop() = server.close()

  private fun store(dir: File = File(tmp.root, "book")) =
    RemotePageStore(OkHttpClient(), mapOf("X-API-Key" to "secret"), dir) { index, fallback ->
      KomgaSource.pageUrl(server.url("/komga").toString(), "B1", index, fallback)
    }

  private fun respond(code: Int = 200, body: String = "") = server.enqueue(MockResponse.Builder().code(code).body(body).build())

  @Test
  fun pageIsFetchedOnceWithSignInThenServedFromDisk() {
    respond(body = "image-bytes")
    val pages = store()
    val first = pages.page(0)
    assertEquals("image-bytes", first.readText())
    // Second read of the same page, and a fresh store over the same folder, use the saved copy.
    assertEquals(first, pages.page(0))
    assertEquals("image-bytes", store().page(0).readText())
    assertEquals(1, server.requestCount)

    val request = server.takeRequest()
    // Komga numbers pages from 1.
    assertEquals("/komga/api/v1/books/B1/pages/1", request.url.encodedPath)
    assertEquals(null, request.url.query)
    assertEquals("secret", request.headers["X-API-Key"])
  }

  @Test
  fun fallbackAsksForAConvertedCopyAndIsKeptSeparately() {
    respond(body = "original")
    respond(body = "converted")
    val pages = store()
    assertEquals("original", pages.page(4).readText())
    assertEquals("converted", pages.page(4, fallback = true).readText())
    server.takeRequest()
    val request = server.takeRequest()
    assertEquals("/komga/api/v1/books/B1/pages/5", request.url.encodedPath)
    assertEquals("convert=jpeg", request.url.query)
    assertEquals("original", pages.page(4).readText())
  }

  @Test
  fun failuresLeaveNothingBehindAndCanBeRetried() {
    val dir = File(tmp.root, "book")
    val pages = store(dir)
    respond(code = 500)
    assertTrue(assertThrows(IOException::class.java) { pages.page(2) }.message!!.contains("HTTP 500 for page 3"))
    respond(code = 401)
    assertEquals("The server refused the sign-in details", assertThrows(IOException::class.java) { pages.page(2) }.message)
    respond(body = "")
    assertTrue(assertThrows(IOException::class.java) { pages.page(2) }.message!!.contains("empty"))
    assertEquals(0, dir.list()!!.size)

    respond(body = "ok")
    assertEquals("ok", pages.page(2).readText())
    assertArrayEquals(arrayOf("2"), dir.list())
  }

  @Test
  fun pageCountComesFromThePageList() {
    assertEquals(3, KomgaSource.parsePageCount("""[{"number":1},{"number":2},{"number":3}]"""))
    assertEquals(0, KomgaSource.parsePageCount("[]"))
    assertThrows(IOException::class.java) { KomgaSource.parsePageCount("<html>") }
    assertThrows(IOException::class.java) { KomgaSource.parsePageCount("""{"error":"nope"}""") }
  }

  @Test
  fun cacheTrimRemovesOldestBooksFirstAndSparesTheOpenOne() {
    val root = tmp.newFolder("cache")
    fun book(name: String, bytes: Int, age: Long): File {
      val dir = File(root, name).apply { mkdirs() }
      File(dir, "0").writeBytes(ByteArray(bytes))
      dir.setLastModified(1_000_000_000_000L + age)
      return dir
    }
    val oldest = book("oldest", 400, 1_000)
    val open = book("open", 400, 2_000)
    val newer = book("newer", 400, 3_000)
    val newest = book("newest", 400, 4_000)

    // 1600 bytes held, 900 allowed: the two oldest would go, but one of them is being read.
    RemotePageStore.trim(root, maxBytes = 900, keep = open)
    assertFalse(oldest.exists())
    assertTrue(open.exists())
    assertFalse(newer.exists())
    assertTrue(newest.exists())

    // Under the limit: nothing is touched.
    RemotePageStore.trim(root, maxBytes = 900, keep = null)
    assertTrue(open.exists() && newest.exists())
    // A missing cache folder is not an error.
    RemotePageStore.trim(File(tmp.root, "absent"), maxBytes = 1)
  }

  @Test
  fun folderNamesAreStableAndFileSystemSafe() {
    val name = RemotePageStore.folderName("komga:http://192.168.1.10:25600|B1")
    assertEquals(name, RemotePageStore.folderName("komga:http://192.168.1.10:25600|B1"))
    assertTrue(Regex("[0-9a-f]{40}").matches(name))
    assertFalse(name == RemotePageStore.folderName("komga:http://192.168.1.10:25600|B2"))
  }
}
