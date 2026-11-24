package dev.local.androidreader.source

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/** The outcome of trying a server before it is saved. */
sealed interface ServerCheck {
  data object Running : ServerCheck

  data class Passed(val message: String) : ServerCheck

  data class Failed(val message: String) : ServerCheck

  companion object {
    /** Loads the source's start page, which proves the address, the sign-in and the server type. */
    suspend fun run(source: ComicSource, kind: String): ServerCheck =
      try {
        val count = source.browse(null).entries.size
        val what = if (source is KomgaSource) "library" to "libraries" else "entry" to "entries"
        Passed(
          when (count) {
            0 -> "Connected, but this account can see nothing there"
            1 -> "Connected: 1 ${what.first} found"
            else -> "Connected: $count ${what.second} found"
          }
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Failed(describe(e, kind))
      }

    fun describe(error: Exception, kind: String): String {
      val message = error.message.orEmpty()
      return when {
        error is UnknownHostException -> "Server not found. Check the address."
        error is SocketTimeoutException -> "The server did not answer in time."
        error is ConnectException -> "Could not connect. Check the address and port, and that the server is running."
        error is SSLException -> "Secure connection failed. If the server has no certificate, use http:// instead of https://."
        "refused the sign-in" in message || "HTTP 401" in message || "HTTP 403" in message ->
          "The server refused the sign-in details."
        "HTTP 404" in message -> "Nothing found at that address. Check the path."
        Regex("HTTP \\d{3}").containsMatchIn(message) -> "The server answered with an error (${Regex("HTTP \\d{3}").find(message)!!.value})."
        error is IllegalArgumentException && "url" in message.lowercase() -> "That is not a valid address."
        error is IOException && message.isNotEmpty() && error !is WrongServerException -> message
        else -> "That address did not answer like $kind."
      }
    }
  }
}

/** The address answered, but not with what this kind of server would send. */
class WrongServerException(message: String) : IOException(message)
