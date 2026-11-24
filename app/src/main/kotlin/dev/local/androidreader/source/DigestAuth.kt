package dev.local.androidreader.source

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * HTTP Digest sign-in (RFC 2617/7616, MD5), which Calibre's content server uses by default.
 * Unlike Basic sign-in, the password is never sent; each request proves knowledge of it using a
 * one-time value the server hands out.
 */
object DigestAuth {
  data class Challenge(val scheme: String, val params: Map<String, String>)

  /** Splits a `WWW-Authenticate` header into its scheme and `name="value"` parts. */
  fun parseChallenge(header: String): Challenge {
    val scheme = header.trim().substringBefore(' ')
    val params = HashMap<String, String>()
    val pattern = Regex("([A-Za-z0-9_-]+)\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^,\\s]+))")
    for (match in pattern.findAll(header.trim().substringAfter(' ', ""))) {
      params[match.groupValues[1].lowercase()] = match.groups[2]?.value?.replace("\\\"", "\"") ?: match.groupValues[3]
    }
    return Challenge(scheme, params)
  }

  /**
   * The `Authorization` header answering a Digest challenge, or null if the challenge asks for
   * something unsupported. [uri] is the request path with its query string.
   */
  fun authorization(challenge: Challenge, method: String, uri: String, username: String, password: String, cnonce: String, nonceCount: Int = 1): String? {
    val realm = challenge.params["realm"] ?: return null
    val nonce = challenge.params["nonce"] ?: return null
    val algorithm = challenge.params["algorithm"] ?: "MD5"
    if (!algorithm.equals("MD5", ignoreCase = true) && !algorithm.equals("MD5-sess", ignoreCase = true)) return null
    // The server may offer several protections; plain "auth" is the one supported here.
    val offered = challenge.params["qop"]?.split(',')?.map { it.trim().lowercase() }
    if (offered != null && "auth" !in offered) return null

    var ha1 = md5("$username:$realm:$password")
    if (algorithm.equals("MD5-sess", ignoreCase = true)) ha1 = md5("$ha1:$nonce:$cnonce")
    val ha2 = md5("$method:$uri")
    val nc = "%08x".format(nonceCount)
    val response = if (offered != null) md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2") else md5("$ha1:$nonce:$ha2")

    return buildString {
      append("Digest username=\"").append(quote(username)).append("\", realm=\"").append(quote(realm))
      append("\", nonce=\"").append(quote(nonce)).append("\", uri=\"").append(quote(uri))
      append("\", response=\"").append(response).append("\", algorithm=").append(algorithm)
      if (offered != null) append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"')
      challenge.params["opaque"]?.let { append(", opaque=\"").append(quote(it)).append('"') }
    }
  }

  private fun quote(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

  private fun md5(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

  fun newCnonce(): String = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
}

/**
 * Answers a server's request to sign in, for servers whose sign-in cannot simply be attached to
 * every request up front. [credentialsFor] returns the user name and password saved for a URL,
 * or null for an address the app has no sign-in for.
 */
class ServerAuthenticator(private val credentialsFor: (HttpUrl) -> Pair<String, String>?) : Authenticator {
  override fun authenticate(route: Route?, response: Response): Request? {
    val request = response.request
    val (username, password) = credentialsFor(request.url) ?: return null
    if (username.isEmpty()) return null
    val challenges = response.headers("WWW-Authenticate").map(DigestAuth::parseChallenge)
    val digest = challenges.firstOrNull { it.scheme.equals("Digest", ignoreCase = true) }
    // Already answered once and still refused: the details are wrong, unless the server only
    // says its one-time value expired.
    if (request.header("Authorization") != null && digest?.params?.get("stale")?.equals("true", ignoreCase = true) != true) return null
    if (attempts(response) >= 3) return null

    val header =
      when {
        digest != null -> {
          val uri = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
          DigestAuth.authorization(digest, request.method, uri, username, password, DigestAuth.newCnonce())
        }
        challenges.any { it.scheme.equals("Basic", ignoreCase = true) } -> Credentials.basic(username, password)
        else -> null
      } ?: return null
    return request.newBuilder().header("Authorization", header).build()
  }

  private fun attempts(response: Response): Int {
    var count = 1
    var prior = response.priorResponse
    while (prior != null) {
      count++
      prior = prior.priorResponse
    }
    return count
  }
}
