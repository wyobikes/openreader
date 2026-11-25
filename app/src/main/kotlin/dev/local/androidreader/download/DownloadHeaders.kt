package dev.local.androidreader.download

import dev.local.androidreader.library.SecretSealer
import dev.local.androidreader.source.ServerOrigin
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** How the headers of a browser download are prepared and stored until the download runs. */
object DownloadHeaders {
  /**
   * The referrer to send when the page at [pageUrl] starts a download of [targetUrl], following
   * what browsers do by default: the full page address within one site, only the site's address
   * across sites, and nothing at all when stepping down from https to http.
   */
  fun referer(pageUrl: String, targetUrl: String): String? {
    val page = pageUrl.toHttpUrlOrNull() ?: return null
    val target = targetUrl.toHttpUrlOrNull() ?: return null
    if (page.isHttps && !target.isHttps) return null
    val sameSite = page.scheme == target.scheme && page.host.equals(target.host, ignoreCase = true) && page.port == target.port
    // Never include a user name, password or fragment from the page address.
    val clean = page.newBuilder().username("").password("").fragment(null)
    return if (sameSite) clean.build().toString() else clean.encodedPath("/").query(null).build().toString()
  }

  /**
   * Turns headers into one string for the download queue, which Android saves to disk. The
   * string is encrypted. If encryption is not available on this phone, cookies and other
   * sign-in headers are left out instead of being written where they could be read.
   */
  fun pack(headers: Map<String, String>, sealer: SecretSealer): String {
    val usable = headers.filter { (name, value) -> value.isNotBlank() && '\n' !in name + value && '\r' !in name + value && ':' !in name }
    if (usable.isEmpty()) return ""
    val token = sealer.seal(lines(usable))
    if (sealer.isEncrypted(token)) return token
    val harmless = usable.filterKeys { name -> ServerOrigin.SIGN_IN_HEADERS.none { it.equals(name, ignoreCase = true) } }
    return if (harmless.isEmpty()) "" else sealer.seal(lines(harmless))
  }

  /** The reverse of [pack]. Headers that can no longer be decrypted are simply absent. */
  fun unpack(token: String?, sealer: SecretSealer): Map<String, String> {
    val text = token?.let(sealer::open).orEmpty()
    return text.lines().filter { ": " in it }.associate { it.substringBefore(": ") to it.substringAfter(": ") }
  }

  private fun lines(headers: Map<String, String>) = headers.entries.joinToString("\n") { (name, value) -> "$name: $value" }
}
