package dev.local.longbox

import dev.local.longbox.download.Downloader
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class DownloaderTest {
  @get:Rule val tmp = TemporaryFolder()
  private val server = MockWebServer()
  private val downloader = Downloader(OkHttpClient())
  private val payload = ByteArray(700_000) { (it % 251).toByte() }

  @Before fun start() = server.start()

  @After fun stop() = server.close()

  private fun body(bytes: ByteArray) = okio.Buffer().write(bytes)

  @Test
  fun downloadsWholeFileAndReportsProgress() {
    server.enqueue(
      MockResponse.Builder().body(body(payload)).addHeader("Content-Disposition", "attachment; filename=\"../Nice Name.cbz\"").build()
    )
    val dest = File(tmp.root, "out")
    val progress = ArrayList<Pair<Long, Long?>>()
    val result = downloader.download(server.url("/f").toString(), dest) { done, total -> progress += done to total }
    assertArrayEquals(payload, dest.readBytes())
    assertEquals("Nice Name.cbz", result.suggestedName)
    assertEquals(payload.size.toLong() to payload.size.toLong(), progress.last())
    assertTrue(progress.size > 2)
    assertFalse(File(tmp.root, "out.part").exists())
    assertNull(server.takeRequest().headers["Range"])
  }

  @Test
  fun resumesFromPartialFile() {
    val dest = File(tmp.root, "out")
    File(tmp.root, "out.part").writeBytes(payload.copyOfRange(0, 300_000))
    server.enqueue(MockResponse.Builder().code(206).body(body(payload.copyOfRange(300_000, payload.size))).build())
    var firstReport: Pair<Long, Long?>? = null
    downloader.download(server.url("/f").toString(), dest) { done, total -> if (firstReport == null) firstReport = done to total }
    assertEquals("bytes=300000-", server.takeRequest().headers["Range"])
    assertArrayEquals(payload, dest.readBytes())
    assertEquals(300_000L to payload.size.toLong(), firstReport)
  }

  @Test
  fun serverIgnoringRangeRestartsCleanly() {
    val dest = File(tmp.root, "out")
    File(tmp.root, "out.part").writeBytes(ByteArray(1000) { 7 })
    server.enqueue(MockResponse.Builder().code(200).body(body(payload)).build())
    downloader.download(server.url("/f").toString(), dest)
    assertArrayEquals(payload, dest.readBytes())
  }

  @Test
  fun staleRangeIsDiscardedAndRetried() {
    val dest = File(tmp.root, "out")
    File(tmp.root, "out.part").writeBytes(ByteArray(900_000))
    server.enqueue(MockResponse.Builder().code(416).build())
    server.enqueue(MockResponse.Builder().body(body(payload)).build())
    downloader.download(server.url("/f").toString(), dest)
    assertArrayEquals(payload, dest.readBytes())
    assertEquals(2, server.requestCount)
  }

  @Test
  fun httpErrorThrowsAndLeavesNoFile() {
    server.enqueue(MockResponse.Builder().code(404).build())
    val dest = File(tmp.root, "out")
    val error = assertThrows(IOException::class.java) { downloader.download(server.url("/f").toString(), dest) }
    assertTrue(error.message!!.contains("HTTP 404"))
    assertFalse(dest.exists())
  }

  @Test
  fun cancelKeepsPartialFileForResume() {
    server.enqueue(MockResponse.Builder().body(body(payload)).build())
    val dest = File(tmp.root, "out")
    var calls = 0
    assertThrows(CancellationException::class.java) {
      downloader.download(server.url("/f").toString(), dest, isCancelled = { ++calls > 3 })
    }
    assertFalse(dest.exists())
    assertTrue(File(tmp.root, "out.part").length() in 1 until payload.size)
  }

  @Test
  fun fileNames() {
    assertEquals("My Comic #1.cbz", Downloader.fileNameFromUrl("https://x.org/dl/My%20Comic%20%231.cbz?sig=abc#frag"))
    assertNull(Downloader.fileNameFromUrl("https://x.org"))
    assertNull(Downloader.fileNameFromUrl("https://x.org/"))
    assertEquals("a b.cbr", Downloader.fileNameFromContentDisposition("attachment; filename*=UTF-8''a%20b.cbr"))
    assertEquals("plain.pdf", Downloader.fileNameFromContentDisposition("inline; filename=plain.pdf"))
    assertNull(Downloader.fileNameFromContentDisposition("attachment"))
  }
}
