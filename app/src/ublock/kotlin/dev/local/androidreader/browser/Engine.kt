package dev.local.androidreader.browser

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.local.androidreader.download.Downloader
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse

const val ENGINE_LABEL = "Firefox engine · uBlock Origin"

private const val TAG = "AndroidReaderGecko"
private const val UBLOCK_ID = "uBlock0@raymondhill.net"
private const val UBLOCK_ASSETS = "resource://android/assets/ublock/"

// Only one Gecko runtime may exist per process, so it outlives any single screen.
private var sharedRuntime: GeckoRuntime? = null

private fun runtime(context: Context): GeckoRuntime =
  sharedRuntime
    ?: GeckoRuntime.create(context.applicationContext).also { runtime ->
      sharedRuntime = runtime
      // The extension is unpacked into the app's assets at build time.
      runtime.webExtensionController
        .ensureBuiltIn(UBLOCK_ASSETS, UBLOCK_ID)
        .accept(
          { extension -> Log.i(TAG, "uBlock Origin ready: ${extension?.metaData?.version}") },
          { error -> Log.e(TAG, "uBlock Origin failed to load", error) },
        )
    }

/** Firefox's engine (GeckoView) with uBlock Origin installed as a built-in extension. */
@Composable
fun BrowserEngineView(state: BrowserState, onDownload: (DownloadRequest) -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val currentOnDownload by rememberUpdatedState(onDownload)

  val session =
    remember {
      GeckoSession().apply {
        navigationDelegate =
          object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
              session: GeckoSession,
              url: String?,
              perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
              hasUserGesture: Boolean,
            ) {
              if (url != null && url != "about:blank") state.url = url
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
              state.canGoBack = canGoBack
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
              state.canGoForward = canGoForward
            }

            override fun onLoadRequest(
              session: GeckoSession,
              request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
              // Refuse links that would leave the browser (app stores, intents, dialers).
              val scheme = request.uri.substringBefore(':').lowercase()
              val allowed = scheme in setOf("http", "https", "about", "blob", "data", "moz-extension", "resource")
              return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }

            // Returning null refuses every new window, which is how pop-ups and pop-unders arrive.
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null
          }

        progressDelegate =
          object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
              state.loading = true
              state.progress = 0
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
              state.loading = false
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
              state.progress = progress
            }
          }

        contentDelegate =
          object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
              state.title = title.orEmpty()
            }

            // Called when the page serves a file instead of something to display.
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
              // The app's own downloader fetches the file again, so this copy is not needed.
              runCatching { response.body?.close() }
              val uri = response.uri
              if (!uri.startsWith("http://") && !uri.startsWith("https://")) return
              val disposition = response.headers.entries.firstOrNull { it.key.equals("Content-Disposition", ignoreCase = true) }?.value
              currentOnDownload(
                DownloadRequest(uri, Downloader.fileNameFromContentDisposition(disposition), mapOf("Referer" to state.url))
              )
            }
          }
      }
    }

  DisposableEffect(session) {
    // uBlock Origin does not report its counts to the app.
    state.blocked = null
    state.controls =
      object : BrowserControls {
        override fun load(url: String) = session.loadUri(url)

        override fun back() = session.goBack()

        override fun forward() = session.goForward()

        override fun reload() = session.reload()

        override fun stop() = session.stop()
      }
    onDispose {
      state.controls = null
      state.canGoBack = false
      state.canGoForward = false
      session.close()
    }
  }

  AndroidView(
    modifier = modifier,
    factory = { viewContext ->
      GeckoView(viewContext).apply {
        if (!session.isOpen) session.open(runtime(context))
        setSession(session)
        session.loadUri(state.url)
      }
    },
    onRelease = { it.releaseSession() },
  )
}
