package dev.local.androidreader

import dev.local.androidreader.source.KomgaConfig
import dev.local.androidreader.source.KomgaSource
import dev.local.androidreader.source.OpdsConfig
import dev.local.androidreader.source.OpdsSource
import dev.local.androidreader.source.ServerCheck
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ServerCheckTest {
  private val server = MockWebServer()
  private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()

  @Before fun start() = server.start()

  @After fun stop() = server.close()

  private fun komga(url: String = server.url("/").toString()) =
    runBlocking { ServerCheck.run(KomgaSource(client, KomgaConfig("Home", url)), "a Komga server") }

  private fun opds(url: String = server.url("/opds").toString()) =
    runBlocking { ServerCheck.run(OpdsSource(client, OpdsConfig("Home", url)), "an OPDS catalogue") }

  private fun respond(code: Int = 200, body: String = "") = server.enqueue(MockResponse.Builder().code(code).body(body).build())

  @Test
  fun komgaPassesAndCountsLibraries() {
    respond(body = """[{"id":"L1","name":"Comics"},{"id":"L2","name":"Manga"}]""")
    assertEquals(ServerCheck.Passed("Connected: 2 libraries found"), komga())
    respond(body = """[{"id":"L1","name":"Comics"}]""")
    assertEquals(ServerCheck.Passed("Connected: 1 library found"), komga())
    respond(body = "[]")
    assertEquals(ServerCheck.Passed("Connected, but this account can see nothing there"), komga())
  }

  @Test
  fun wrongSignInIsNamedAsSuch() {
    respond(code = 401)
    assertEquals(ServerCheck.Failed("The server refused the sign-in details."), komga())
    respond(code = 403)
    assertEquals(ServerCheck.Failed("The server refused the sign-in details."), opds())
  }

  @Test
  fun anAddressThatIsNotTheRightKindOfServerFails() {
    // A web page, as a router or a different app at that address would send.
    respond(body = "<!doctype html><html><body>Welcome</body></html>")
    assertEquals(ServerCheck.Failed("That address did not answer like a Komga server."), komga())
    // Valid JSON, but not a library list.
    respond(body = """{"status":"ok"}""")
    assertEquals(ServerCheck.Failed("That address did not answer like a Komga server."), komga())
    respond(body = "<!doctype html><html><body><p>Welcome<br></body></html>")
    assertEquals(ServerCheck.Failed("That address did not answer like an OPDS catalogue."), opds())
    // Well-formed XML that is not a feed.
    respond(body = """<?xml version="1.0"?><html><body>Welcome</body></html>""")
    assertEquals(ServerCheck.Failed("That address did not answer like an OPDS catalogue."), opds())
  }

  @Test
  fun opdsPassesOnARealFeed() {
    respond(
      body =
        """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>All</title><id>a</id>
           <link rel="subsection" href="all" type="application/atom+xml;profile=opds-catalog"/></entry></feed>"""
    )
    assertEquals(ServerCheck.Passed("Connected: 1 entry found"), opds())
  }

  @Test
  fun serverErrorsAndMissingPathsAreExplained() {
    respond(code = 404)
    assertEquals(ServerCheck.Failed("Nothing found at that address. Check the path."), komga())
    respond(code = 502)
    assertEquals(ServerCheck.Failed("The server answered with an error (HTTP 502)."), opds())
  }

  @Test
  fun unreachableServersAreExplained() {
    // Nothing listens on port 1.
    assertEquals(
      ServerCheck.Failed("Could not connect. Check the address and port, and that the server is running."),
      komga("http://127.0.0.1:1/"),
    )
    assertEquals(ServerCheck.Failed("Server not found. Check the address."), komga("http://no-such-host.invalid/"))
  }
}
