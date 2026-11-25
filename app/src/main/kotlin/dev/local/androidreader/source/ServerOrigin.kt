package dev.local.androidreader.source

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Decides whether a sign-in may accompany a request.
 *
 * Addresses often come from outside the app: a catalogue lists where its covers and files are,
 * a server answers with a redirect, a web page offers a download. A sign-in saved for one server
 * must never follow such an address to a different one.
 */
object ServerOrigin {
  /** Headers that identify the user to a server. */
  val SIGN_IN_HEADERS = listOf("Authorization", "Cookie", "X-API-Key", "Proxy-Authorization")

  /**
   * True when [target] is on the server at [base]: the same host and port, and not a step down
   * from https to plain http, where the sign-in would become readable on the network.
   */
  fun sameServer(base: String?, target: String?): Boolean {
    val from = base?.toHttpUrlOrNull() ?: return false
    val to = target?.toHttpUrlOrNull() ?: return false
    return sameServer(from, to)
  }

  fun sameServer(from: HttpUrl, to: HttpUrl): Boolean =
    from.host.equals(to.host, ignoreCase = true) && from.port == to.port && !(from.isHttps && !to.isHttps)

  /** Where a request was first sent, carried along with it so redirects can be compared. */
  private class FirstAddress(val url: HttpUrl)

  /**
   * Makes the client drop sign-in headers when a server redirects a request to a different
   * server. OkHttp already does this for `Authorization` alone; API keys and cookies need it too.
   */
  fun OkHttpClient.Builder.keepSignInsOnTheirServer(): OkHttpClient.Builder =
    // Runs once per call: remember where the caller meant the request to go.
    addInterceptor(
        Interceptor { chain ->
          val request = chain.request()
          chain.proceed(
            if (request.tag(FirstAddress::class.java) != null) request
            else request.newBuilder().tag(FirstAddress::class.java, FirstAddress(request.url)).build()
          )
        }
      )
      // Runs for every hop, including each redirect: check the hop is still the same server.
      .addNetworkInterceptor(
        Interceptor { chain ->
          val request = chain.request()
          val first = request.tag(FirstAddress::class.java)?.url
          if (first == null || sameServer(first, request.url)) return@Interceptor chain.proceed(request)
          val stripped = request.newBuilder().apply { SIGN_IN_HEADERS.forEach { removeHeader(it) } }.build()
          chain.proceed(stripped)
        }
      )
}
