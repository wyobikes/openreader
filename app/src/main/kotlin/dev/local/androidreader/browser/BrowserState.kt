package dev.local.androidreader.browser

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.HttpUrl.Companion.toHttpUrl

/** A file download the browser engine was asked to start. */
data class DownloadRequest(val url: String, val fileName: String?, val headers: Map<String, String>)

/** What the screen can ask the engine to do. Each build variant supplies its own. */
interface BrowserControls {
  fun load(url: String)

  fun back()

  fun forward()

  fun reload()

  fun stop()
}

/** Page state shared between the address bar and whichever engine is compiled in. */
@Stable
class BrowserState(initialUrl: String = HOME_URL) {
  var url by mutableStateOf(initialUrl)
  var title by mutableStateOf("")
  var progress by mutableIntStateOf(0)
  var loading by mutableStateOf(false)
  var canGoBack by mutableStateOf(false)
  var canGoForward by mutableStateOf(false)

  /** Requests blocked on the current page; null when the engine cannot count them. */
  var blocked by mutableStateOf<Int?>(null)

  var controls: BrowserControls? = null

  companion object {
    const val HOME_URL = "https://duckduckgo.com/"

    /** Turns address-bar text into a URL: a link as typed, a bare domain, or a web search. */
    fun toUrl(input: String): String {
      val text = input.trim()
      return when {
        text.isEmpty() -> HOME_URL
        text.startsWith("http://") || text.startsWith("https://") -> text
        ' ' !in text && Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$").matches(text) -> "https://$text"
        else -> "https://duckduckgo.com/".toHttpUrl().newBuilder().addQueryParameter("q", text).build().toString()
      }
    }
  }
}
