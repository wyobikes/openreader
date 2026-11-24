package dev.local.androidreader

import dev.local.androidreader.source.CalibreConfig
import dev.local.androidreader.source.CalibreSource
import dev.local.androidreader.source.ComicFormat
import dev.local.androidreader.source.DigestAuth
import dev.local.androidreader.source.ServerAuthenticator
import dev.local.androidreader.source.ServerCheck
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DigestAuthTest {
  private val server = MockWebServer()

  @Before fun start() = server.start()

  @After fun stop() = server.close()

  @Test
  fun matchesTheWorkedExampleInTheStandard() {
    // RFC 2617 section 3.5.
    val challenge =
      DigestAuth.parseChallenge(
        """Digest realm="testrealm@host.com", qop="auth,auth-int", nonce="dcd98b7102dd2f0e8b11d0f600bfb0c093", opaque="5ccc069c403ebaf9f0171e9517f40e41""""
      )
    assertEquals("Digest", challenge.scheme)
    assertEquals("auth,auth-int", challenge.params["qop"])
    val header = DigestAuth.authorization(challenge, "GET", "/dir/index.html", "Mufasa", "Circle Of Life", cnonce = "0a4f113b")!!
    assertTrue(header.contains("""response="6629fae49393a05397450978507c4ef1""""))
    assertTrue(header.contains("""username="Mufasa""""))
    assertTrue(header.contains("""uri="/dir/index.html""""))
    assertTrue(header.contains("qop=auth, nc=00000001, cnonce=\"0a4f113b\""))
    assertTrue(header.contains("""opaque="5ccc069c403ebaf9f0171e9517f40e41""""))
  }

  @Test
  fun handlesChallengesWithoutQopAndRefusesUnsupportedOnes() {
    // The older form, with no qop: response = MD5(HA1:nonce:HA2).
    val plain = DigestAuth.parseChallenge("""Digest realm="r", nonce="n"""")
    val header = DigestAuth.authorization(plain, "GET", "/", "u", "p", cnonce = "c")!!
    assertFalse(header.contains("qop="))
    assertTrue(Regex("response=\"[0-9a-f]{32}\"").containsMatchIn(header))
    assertNull(DigestAuth.authorization(DigestAuth.parseChallenge("""Digest realm="r", nonce="n", algorithm=SHA-256"""), "GET", "/", "u", "p", "c"))
    assertNull(DigestAuth.authorization(DigestAuth.parseChallenge("""Digest realm="r", nonce="n", qop="auth-int""""), "GET", "/", "u", "p", "c"))
    assertNull(DigestAuth.authorization(DigestAuth.parseChallenge("""Digest nonce="n""""), "GET", "/", "u", "p", "c"))
    // Unquoted values and odd spacing, as some servers send.
    val loose = DigestAuth.parseChallenge("""Digest  realm = "a \"b\"" ,nonce=abc, algorithm=MD5,stale=TRUE""")
    assertEquals("a \"b\"", loose.params["realm"])
    assertEquals("abc", loose.params["nonce"])
    assertEquals("TRUE", loose.params["stale"])
  }

  private fun client(user: String = "reader", password: String = "pw") =
    OkHttpClient.Builder().authenticator(ServerAuthenticator { url -> if (url.encodedPath.startsWith("/calibre")) user to password else null }).build()

  private val calibreChallenge = """Digest realm="calibre", nonce="abc123:def", algorithm="MD5", qop="auth""""

  @Test
  fun answersACalibreStyleChallengeThenSucceeds() {
    server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", calibreChallenge).build())
    server.enqueue(MockResponse.Builder().body("ok").build())
    val response = client().newCall(Request.Builder().url(server.url("/calibre/ajax/search?num=40&offset=0")).build()).execute()
    assertEquals("ok", response.body.string())

    assertNull(server.takeRequest().headers["Authorization"])
    val signed = server.takeRequest().headers["Authorization"]!!
    assertTrue(signed.startsWith("Digest username=\"reader\", realm=\"calibre\", nonce=\"abc123:def\""))
    // The path is signed together with its query string.
    assertTrue(signed.contains("""uri="/calibre/ajax/search?num=40&offset=0""""))
    assertTrue(signed.contains("qop=auth, nc=00000001"))
  }

  @Test
  fun wrongPasswordStopsAfterOneAttemptAndUnknownServersAreLeftAlone() {
    // Still refused after answering: give up instead of looping.
    server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", calibreChallenge).build())
    server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", calibreChallenge).build())
    assertEquals(401, client().newCall(Request.Builder().url(server.url("/calibre/x")).build()).execute().code)
    assertEquals(2, server.requestCount)

    // No sign-in saved for this address: the credentials are never offered to it.
    server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", calibreChallenge).build())
    assertEquals(401, client().newCall(Request.Builder().url(server.url("/elsewhere")).build()).execute().code)
    assertEquals(3, server.requestCount)
  }

  @Test
  fun expiredOneTimeValueIsRetriedAndBasicIsAnswered() {
    server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", calibreChallenge).build())
    server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", """Digest realm="calibre", nonce="fresh", qop="auth", stale=true""").build())
    server.enqueue(MockResponse.Builder().body("ok").build())
    assertEquals("ok", client().newCall(Request.Builder().url(server.url("/calibre/x")).build()).execute().body.string())
    server.takeRequest()
    server.takeRequest()
    assertTrue(server.takeRequest().headers["Authorization"]!!.contains("nonce=\"fresh\""))

    server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", """Basic realm="calibre"""").build())
    server.enqueue(MockResponse.Builder().body("ok").build())
    client().newCall(Request.Builder().url(server.url("/calibre/x")).build()).execute().close()
    server.takeRequest()
    assertTrue(server.takeRequest().headers["Authorization"]!!.startsWith("Basic "))
  }
}

class CalibreTest {
  private val server = MockWebServer()

  @Before fun start() = server.start()

  @After fun stop() = server.close()

  private fun source() = CalibreSource(OkHttpClient(), CalibreConfig("Shelf", server.url("/").toString().trimEnd('/')))

  private fun respond(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

  private val books =
    """{"7":{"title":"Second: A/B","authors":["Ann Author","B. Other"],"formats":["azw3","epub","pdf"],
              "format_metadata":{"epub":{"size":123456},"pdf":{"size":"999"}},"series":"Saga","series_index":2.0},
        "3":{"title":"First","authors":[],"formats":["mobi"],"format_metadata":{}},
        "9":null}"""

  @Test
  fun singleLibraryGoesStraightToItsNewestBooks() = runBlocking {
    respond("""{"library_map":{"Calibre_Library":"Calibre Library"},"default_library":"Calibre_Library"}""")
    respond("""{"total_num":95,"offset":0,"num":3,"book_ids":[3,7,9]}""")
    respond(books)
    val page = source().browse(null)

    assertEquals("/ajax/library-info", server.takeRequest().url.encodedPath)
    val search = server.takeRequest().url
    assertEquals("/ajax/search/Calibre_Library", search.encodedPath)
    assertEquals(listOf("40", "0", "timestamp", "desc"), listOf("num", "offset", "sort", "sort_order").map(search::queryParameter))
    assertNull(search.queryParameter("query"))
    val details = server.takeRequest().url
    assertEquals("/ajax/books/Calibre_Library", details.encodedPath)
    assertEquals("3,7,9", details.queryParameter("ids"))

    // Shown in the order the search returned, skipping the id the server had no details for.
    assertEquals(listOf("First", "Second: A/B"), page.entries.map { it.title })
    val (unreadable, book) = page.entries
    assertEquals("Only in MOBI", unreadable.subtitle)
    assertTrue(unreadable.files.isEmpty())
    assertEquals("Ann Author, B. Other · Saga #2", book.subtitle)
    assertTrue(book.thumbnailUrl!!.endsWith("/get/thumb/7/Calibre_Library?sz=300x400"))
    assertEquals(listOf(ComicFormat.EPUB, ComicFormat.PDF), book.files.map { it.format })
    assertEquals("Second A B - Ann Author.epub", book.files[0].name)
    assertTrue(book.files[0].url.endsWith("/get/epub/7/Calibre_Library"))
    assertEquals(listOf(123456L, 999L), book.files.map { it.sizeBytes })
    // 95 books in all, 3 returned: more to load from offset 3.
    assertEquals(Triple("Calibre_Library", 3, ""), CalibreSource.decodeLocation(page.nextLocation!!))
  }

  @Test
  fun severalLibrariesAreListedFirstAndNamesAreEncodedInAddresses() = runBlocking {
    respond("""{"library_map":{"Comics & Art":"Comics & Art","Novels":"Novels"},"default_library":"Novels"}""")
    val calibre = source()
    val root = calibre.browse(null)
    assertEquals(listOf("Comics & Art", "Novels"), root.entries.map { it.title })
    assertTrue(root.entries.all { it.isFolder })

    respond("""{"total_num":1,"book_ids":[4]}""")
    respond("""{"4":{"title":"Solo","authors":["X"],"formats":["cbz"]}}""")
    val page = calibre.browse(root.entries[0].location)
    server.takeRequest()
    assertEquals("/ajax/search/Comics%20&%20Art", server.takeRequest().url.encodedPath)
    assertNull(page.nextLocation)
    assertTrue(page.entries.single().files.single().url.endsWith("/get/cbz/4/Comics%20&%20Art"))
  }

  @Test
  fun searchUsesTheDefaultLibraryAndTitleOrder() = runBlocking {
    respond("""{"library_map":{"A":"A","B":"B"},"default_library":"B"}""")
    respond("""{"total_num":0,"book_ids":[]}""")
    val page = source().search("  author:\"le guin\" ")
    assertTrue(page.entries.isEmpty())
    server.takeRequest()
    val search = server.takeRequest().url
    assertEquals("/ajax/search/B", search.encodedPath)
    assertEquals("author:\"le guin\"", search.queryParameter("query"))
    assertEquals("title", search.queryParameter("sort"))
    // Nothing matched, so the details request is skipped.
    assertEquals(2, server.requestCount)
  }

  @Test
  fun connectionTestExplainsWrongServersAndRefusedSignIns() {
    respond("""{"library_map":{"L":"Library"},"default_library":"L"}""")
    respond("""{"total_num":2,"book_ids":[1,2]}""")
    respond("""{"1":{"title":"A","formats":["epub"]},"2":{"title":"B","formats":["epub"]}}""")
    assertEquals(ServerCheck.Passed("Connected: 2 entries found"), runBlocking { ServerCheck.run(source(), "a Calibre server") })

    respond("<html>Router login</html>")
    assertEquals(ServerCheck.Failed("That address did not answer like a Calibre server."), runBlocking { ServerCheck.run(source(), "a Calibre server") })
    // Valid JSON from some other service.
    respond("""{"status":"ok"}""")
    assertEquals(ServerCheck.Failed("That address did not answer like a Calibre server."), runBlocking { ServerCheck.run(source(), "a Calibre server") })
    server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", """Digest realm="calibre", nonce="n", qop="auth"""").build())
    assertEquals(ServerCheck.Failed("The server refused the sign-in details."), runBlocking { ServerCheck.run(source(), "a Calibre server") })
  }

  @Test
  fun signInsAreOnlyOfferedToTheirOwnServer() {
    val config = CalibreConfig("Shelf", "http://192.168.1.10:8080/calibre")
    assertTrue(config.covers("http://192.168.1.10:8080/calibre/get/epub/7/L".toHttpUrl()))
    assertTrue(config.covers("http://192.168.1.10:8080/calibre".toHttpUrl()))
    assertFalse(config.covers("http://192.168.1.10:8081/calibre/x".toHttpUrl()))
    assertFalse(config.covers("http://192.168.1.11:8080/calibre/x".toHttpUrl()))
    assertFalse(config.covers("http://192.168.1.10:8080/other/x".toHttpUrl()))
    assertFalse(CalibreConfig("Bad", "not a url").covers("http://192.168.1.10:8080/".toHttpUrl()))
  }
}
