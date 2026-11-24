package dev.local.androidreader

import dev.local.androidreader.browser.BlocklistStore
import dev.local.androidreader.browser.HostBlocklist
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HostBlocklistTest {
  @get:Rule val tmp = TemporaryFolder()
  private val server = MockWebServer()

  @Before fun start() = server.start()

  @After fun stop() = server.close()

  private val sample =
    """
    # Title: sample
    127.0.0.1 localhost
    0.0.0.0 0.0.0.0
    0.0.0.0 Ads.Example.com   # trailing comment
    127.0.0.1	tracker.net
    plain-domain.org
    ||adblock-style.io^
    ! adblock comment
    [Adblock Plus 2.0]
    0.0.0.0 not a domain!!
    com
    """.trimIndent()

  @Test
  fun parsesTheCommonListFormats() {
    val list = HostBlocklist.parse(sample.lineSequence())
    assertEquals(4, list.size)
    assertTrue(list.isBlocked("ads.example.com"))
    assertTrue(list.isBlocked("tracker.net"))
    assertTrue(list.isBlocked("plain-domain.org"))
    assertTrue(list.isBlocked("adblock-style.io"))
    assertFalse(list.isBlocked("localhost"))
  }

  @Test
  fun matchesSubdomainsButNotSiblingsOrParents() {
    val list = HostBlocklist.parse(sequenceOf("0.0.0.0 ads.example.com", "0.0.0.0 tracker.net"))
    assertTrue(list.isBlocked("cdn.ads.example.com"))
    assertTrue(list.isBlocked("ADS.EXAMPLE.COM."))
    assertTrue(list.isBlocked("a.b.tracker.net"))
    assertFalse(list.isBlocked("example.com"))
    assertFalse(list.isBlocked("www.example.com"))
    assertFalse(list.isBlocked("notads.example.com"))
    assertFalse(list.isBlocked("mytracker.net"))
    assertFalse(list.isBlocked("net"))
  }

  private fun bigList(extra: String) = (1..1200).joinToString("\n") { "0.0.0.0 ad$it.example" } + "\n0.0.0.0 $extra\n"

  @Test
  fun storeDownloadsOnceThenReadsFromDisk() = runBlocking {
    val file = File(tmp.root, "blocklist.txt")
    server.enqueue(MockResponse.Builder().body(bigList("first.example")).build())
    val store = BlocklistStore(file, OkHttpClient(), server.url("/hosts").toString())
    store.ensureLoaded()
    assertTrue(store.isBlocked("first.example"))
    assertEquals(1201, store.size)

    // A fresh store with a recent file must not touch the network.
    val reopened = BlocklistStore(file, OkHttpClient(), server.url("/hosts").toString())
    reopened.ensureLoaded()
    assertTrue(reopened.isBlocked("sub.first.example"))
    assertEquals(1, server.requestCount)
  }

  @Test
  fun staleListIsRefreshedAndBadUpdatesAreIgnored() = runBlocking {
    val file = File(tmp.root, "blocklist.txt").apply { writeText(bigList("old.example")) }
    val store = BlocklistStore(file, OkHttpClient(), server.url("/hosts").toString())
    val eightDays = System.currentTimeMillis() + 8L * 24 * 60 * 60 * 1000

    // An error page instead of a list: keep what we have.
    server.enqueue(MockResponse.Builder().body("<html>rate limited</html>").build())
    store.ensureLoaded(now = eightDays)
    assertTrue(store.isBlocked("old.example"))

    server.enqueue(MockResponse.Builder().code(500).build())
    store.ensureLoaded(now = eightDays)
    assertTrue(store.isBlocked("old.example"))

    server.enqueue(MockResponse.Builder().body(bigList("new.example")).build())
    store.ensureLoaded(now = eightDays)
    assertTrue(store.isBlocked("new.example"))
    assertFalse(store.isBlocked("old.example"))
    assertFalse(File(tmp.root, "blocklist.txt.tmp").exists())
  }

  @Test
  fun worksOfflineWithNoSavedList() = runBlocking {
    server.close()
    val store = BlocklistStore(File(tmp.root, "none.txt"), OkHttpClient(), "http://127.0.0.1:1/hosts")
    store.ensureLoaded()
    assertFalse(store.isBlocked("ads.example.com"))
  }
}
