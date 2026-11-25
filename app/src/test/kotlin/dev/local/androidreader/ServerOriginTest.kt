package dev.local.androidreader

import dev.local.androidreader.source.KomgaConfig
import dev.local.androidreader.source.KomgaSource
import dev.local.androidreader.source.OpdsConfig
import dev.local.androidreader.source.OpdsSource
import dev.local.androidreader.source.ServerOrigin
import dev.local.androidreader.source.ServerOrigin.keepSignInsOnTheirServer
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerOriginTest {
  private val home = MockWebServer()
  private val elsewhere = MockWebServer()
  private val client = OkHttpClient.Builder().keepSignInsOnTheirServer().build()

  @Before
  fun start() {
    home.start()
    elsewhere.start()
  }

  @After
  fun stop() {
    home.close()
    elsewhere.close()
  }

  @Test
  fun sameServerMeansSameHostAndPortWithoutDroppingToHttp() {
    val base = "https://library.example:8443/opds"
    assertTrue(ServerOrigin.sameServer(base, "https://library.example:8443/covers/1.jpg"))
    assertTrue(ServerOrigin.sameServer(base, "https://LIBRARY.example:8443/"))
    assertFalse(ServerOrigin.sameServer(base, "https://library.example/covers/1.jpg"))
    assertFalse(ServerOrigin.sameServer(base, "https://cdn.library.example:8443/1.jpg"))
    assertFalse(ServerOrigin.sameServer(base, "https://library.example.evil.test:8443/1.jpg"))
    assertFalse(ServerOrigin.sameServer(base, "https://evil.test:8443/library.example/1.jpg"))
    // The same server over plain http would expose the sign-in.
    assertFalse(ServerOrigin.sameServer(base, "http://library.example:8443/covers/1.jpg"))
    // Going from http up to https on the same host and port is not a downgrade.
    assertTrue(ServerOrigin.sameServer("http://nas:8083/opds", "http://nas:8083/get/1"))
    assertFalse(ServerOrigin.sameServer("http://nas:8083/opds", "http://nas:8084/get/1"))
    // No server, or something that is not a web address: never.
    assertFalse(ServerOrigin.sameServer(null, base))
    assertFalse(ServerOrigin.sameServer(base, null))
    assertFalse(ServerOrigin.sameServer(base, "file:///data/secret"))
    assertFalse(ServerOrigin.sameServer("not a url", "not a url"))
  }

  @Test
  fun sourcesWithholdTheirSignInFromOtherServers() {
    val opds = OpdsSource(client, OpdsConfig("Home", home.url("/opds").toString(), "reader", "pw"))
    assertTrue(opds.headersFor(home.url("/opds/cover/1").toString())["Authorization"]!!.startsWith("Basic "))
    assertTrue(opds.headersFor(elsewhere.url("/cover/1").toString()).isEmpty())
    assertTrue(opds.headersFor("https://cdn.example/cover/1.jpg").isEmpty())

    val komga = KomgaSource(client, KomgaConfig("Home", home.url("/").toString(), apiKey = "secret"))
    assertEquals("secret", komga.headersFor(home.url("/api/v1/books/B1/file").toString())["X-API-Key"])
    assertTrue(komga.headersFor(elsewhere.url("/api/v1/books/B1/file").toString()).isEmpty())
  }

  @Test
  fun catalogueLinksToAnotherServerAreFollowedWithoutTheSignIn() = runBlocking {
    val feed = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>All</title><id>a</id>
      <link rel="subsection" href="all" type="application/atom+xml;profile=opds-catalog"/></entry></feed>"""
    home.enqueue(MockResponse.Builder().body(feed).build())
    elsewhere.enqueue(MockResponse.Builder().body(feed).build())
    val opds = OpdsSource(client, OpdsConfig("Home", home.url("/opds").toString(), "reader", "pw"))

    opds.browse(null)
    assertTrue(home.takeRequest().headers["Authorization"]!!.startsWith("Basic "))
    // A "next page" or folder link that points somewhere else.
    opds.browse(elsewhere.url("/opds/page2").toString())
    assertNull(elsewhere.takeRequest().headers["Authorization"])
  }

  private fun signedIn(url: okhttp3.HttpUrl) =
    Request.Builder().url(url)
      .header("Authorization", "Basic cmVhZGVyOnB3")
      .header("X-API-Key", "secret")
      .header("Cookie", "session=abc")
      .header("User-Agent", "OpenReader/1.0")
      .build()

  @Test
  fun aRedirectToAnotherServerLosesEverySignInHeader() {
    home.enqueue(MockResponse.Builder().code(302).addHeader("Location", elsewhere.url("/file.cbz").toString()).build())
    elsewhere.enqueue(MockResponse.Builder().body("data").build())
    client.newCall(signedIn(home.url("/download/1"))).execute().use { assertEquals("data", it.body.string()) }

    val first = home.takeRequest()
    assertEquals("secret", first.headers["X-API-Key"])
    assertEquals("session=abc", first.headers["Cookie"])
    val second = elsewhere.takeRequest()
    assertNull(second.headers["Authorization"])
    assertNull(second.headers["X-API-Key"])
    assertNull(second.headers["Cookie"])
    // Ordinary headers still travel.
    assertEquals("OpenReader/1.0", second.headers["User-Agent"])
  }

  @Test
  fun aRedirectWithinTheSameServerKeepsTheSignIn() {
    home.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/files/1.cbz").build())
    home.enqueue(MockResponse.Builder().body("data").build())
    client.newCall(signedIn(home.url("/download/1"))).execute().close()
    home.takeRequest()
    val second = home.takeRequest()
    assertEquals("/files/1.cbz", second.url.encodedPath)
    assertEquals("secret", second.headers["X-API-Key"])
    assertEquals("session=abc", second.headers["Cookie"])
    assertEquals("Basic cmVhZGVyOnB3", second.headers["Authorization"])
  }

  @Test
  fun aChainOfRedirectsIsJudgedAgainstWhereTheRequestStarted() {
    // home -> elsewhere -> home: the hop back to the first server is trusted again, the middle one never is.
    home.enqueue(MockResponse.Builder().code(302).addHeader("Location", elsewhere.url("/hop").toString()).build())
    elsewhere.enqueue(MockResponse.Builder().code(302).addHeader("Location", home.url("/final").toString()).build())
    home.enqueue(MockResponse.Builder().body("data").build())
    client.newCall(signedIn(home.url("/start"))).execute().close()
    home.takeRequest()
    assertNull(elsewhere.takeRequest().headers["X-API-Key"])
    assertEquals("/final", home.takeRequest().url.encodedPath)
  }
}
