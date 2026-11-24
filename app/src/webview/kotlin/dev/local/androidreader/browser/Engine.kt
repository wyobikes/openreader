package dev.local.androidreader.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.local.androidreader.ReaderApp
import dev.local.androidreader.download.Downloader
import java.io.ByteArrayInputStream
import java.io.File

const val ENGINE_LABEL = "WebView · host blocklist"

private var sharedBlocklist: BlocklistStore? = null

private fun blocklist(app: ReaderApp): BlocklistStore =
  sharedBlocklist ?: BlocklistStore(File(app.filesDir, "blocklist.txt"), app.httpClient).also { sharedBlocklist = it }

/** The system WebView, with requests to listed ad hosts dropped and pop-up windows refused. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserEngineView(state: BrowserState, onDownload: (DownloadRequest) -> Unit, modifier: Modifier = Modifier) {
  val app = LocalContext.current.applicationContext as ReaderApp
  val blocklist = remember { blocklist(app) }
  val currentOnDownload by rememberUpdatedState(onDownload)
  LaunchedEffect(Unit) { blocklist.ensureLoaded() }

  AndroidView(
    modifier = modifier,
    factory = { context ->
      WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        // Ask us before opening a window, so pop-ups can be refused in onCreateWindow.
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

        // Read on WebView's network threads, written on the main thread.
        var pageHost: String? = null
        state.blocked = 0

        webViewClient =
          object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
              val host = request.url.host ?: return null
              // The page itself and its own host are never blocked; the user chose to go there.
              if (request.isForMainFrame || host == pageHost || !blocklist.isBlocked(host)) return null
              view.post { state.blocked = (state.blocked ?: 0) + 1 }
              return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
              val scheme = request.url.scheme
              // Refuse links that would leave the browser (app stores, intents, dialers).
              if (scheme != "http" && scheme != "https") return true
              val host = request.url.host ?: return false
              // Clicking through to, or being redirected to, a listed ad host.
              if (request.isForMainFrame && host != pageHost && blocklist.isBlocked(host)) {
                state.blocked = (state.blocked ?: 0) + 1
                return true
              }
              return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
              pageHost = runCatching { android.net.Uri.parse(url).host }.getOrNull()
              state.url = url
              state.loading = true
              state.blocked = 0
            }

            override fun onPageFinished(view: WebView, url: String) {
              state.loading = false
              state.title = view.title.orEmpty()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
              state.url = url
              state.canGoBack = view.canGoBack()
              state.canGoForward = view.canGoForward()
            }
          }

        webChromeClient =
          object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
              state.progress = newProgress
            }

            // Returning false refuses every new window, which is how pop-ups and pop-unders arrive.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false
          }

        setDownloadListener { url, userAgent, contentDisposition, _, _ ->
          if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(context, "This kind of download is not supported", Toast.LENGTH_LONG).show()
            return@setDownloadListener
          }
          val headers =
            buildMap {
              userAgent?.let { put("User-Agent", it) }
              CookieManager.getInstance().getCookie(url)?.let { put("Cookie", it) }
              put("Referer", state.url)
            }
          currentOnDownload(DownloadRequest(url, Downloader.fileNameFromContentDisposition(contentDisposition), headers))
        }

        val webView = this
        state.controls =
          object : BrowserControls {
            override fun load(url: String) = webView.loadUrl(url)

            override fun back() = webView.goBack()

            override fun forward() = webView.goForward()

            override fun reload() = webView.reload()

            override fun stop() = webView.stopLoading()
          }
        loadUrl(state.url)
      }
    },
    onRelease = { webView ->
      state.controls = null
      state.canGoBack = false
      state.canGoForward = false
      webView.stopLoading()
      webView.destroy()
    },
  )
}
