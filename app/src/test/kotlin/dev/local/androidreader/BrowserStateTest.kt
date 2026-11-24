package dev.local.androidreader

import dev.local.androidreader.browser.BrowserState
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserStateTest {
  @Test
  fun addressBarTextBecomesAUrlOrASearch() {
    assertEquals("https://example.org/a b", BrowserState.toUrl("  https://example.org/a b "))
    assertEquals("http://nas.local:8080/opds", BrowserState.toUrl("http://nas.local:8080/opds"))
    assertEquals("https://archive.org", BrowserState.toUrl("archive.org"))
    assertEquals("https://example.org:8443/x?y=1", BrowserState.toUrl("example.org:8443/x?y=1"))
    assertEquals("https://duckduckgo.com/?q=golden%20age%20comics", BrowserState.toUrl("golden age comics"))
    assertEquals("https://duckduckgo.com/?q=what%20is%20opds.io", BrowserState.toUrl("what is opds.io"))
    assertEquals("https://duckduckgo.com/?q=comics", BrowserState.toUrl("comics"))
    assertEquals(BrowserState.HOME_URL, BrowserState.toUrl("   "))
  }
}
