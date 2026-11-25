package dev.local.androidreader.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.local.androidreader.ReaderApp
import dev.local.androidreader.epub.BookHtml
import dev.local.androidreader.epub.EpubBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.roundToInt

private sealed interface BookState {
  data object Opening : BookState

  data class Ready(val book: EpubBook) : BookState

  data class Failed(val message: String) : BookState
}

/**
 * Reads an EPUB one section at a time, as scrolling text, in full screen.
 *
 * Follows the conventions shared by established readers: tap the middle of the page to show or
 * hide the controls, tap the left or right side to move back or forward a screenful.
 */
@Composable
fun BookReaderScreen(path: String, onBack: () -> Unit) {
  val app = LocalContext.current.applicationContext as ReaderApp
  val file = remember(path) { File(path) }
  val state by
    produceState<BookState>(BookState.Opening, file) {
      value =
        withContext(Dispatchers.IO) {
          runCatching { EpubBook.open(file) }.fold({ BookState.Ready(it) }, { BookState.Failed(it.message ?: "Could not open this book") })
        }
    }
  DisposableEffect(state) {
    val opened = (state as? BookState.Ready)?.book
    onDispose { opened?.close() }
  }

  val background = MaterialTheme.colorScheme.background
  val dark = background.luminance() < 0.5f
  var showControls by rememberSaveable { mutableStateOf(false) }
  // Full screen while reading; the system bars return together with the reader's own controls.
  ReaderWindow(fullScreen = !showControls && state is BookState.Ready, lightBackground = !dark)

  when (val s = state) {
    BookState.Opening -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    is BookState.Failed ->
      Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(s.message)
        TextButton(onClick = onBack) { Text("Back") }
      }
    is BookState.Ready -> {
      val book = s.book
      val saved = remember(file) { app.library.state(file) }
      var section by rememberSaveable { mutableIntStateOf(saved.lastPage.coerceIn(0, book.chapters.size - 1)) }
      var textZoom by rememberSaveable { mutableIntStateOf(app.library.bookTextZoom) }
      var showContents by remember { mutableStateOf(false) }
      // How far down the section the reader is, 0 to 1.
      var scroll by remember { mutableFloatStateOf(if (section == saved.lastPage) saved.scroll else 0f) }
      // Where to land when a section loads: the saved place on opening, the end when paging backwards.
      val landing = remember { mutableFloatStateOf(scroll) }

      fun save() =
        app.library.updateState(file) {
          it.copy(lastPage = section, pageCount = book.chapters.size, scroll = scroll, lastOpened = System.currentTimeMillis())
        }
      DisposableEffect(section) {
        save()
        onDispose { save() }
      }

      fun goTo(target: Int, atEnd: Boolean = false) {
        if (target !in book.chapters.indices) return
        landing.floatValue = if (atEnd) 1f else 0f
        scroll = landing.floatValue
        section = target
      }

      Box(Modifier.fillMaxSize()) {
        // The page always fills the whole screen, so showing or hiding the bars never moves the text.
        BookPage(
          book = book,
          section = section,
          textZoom = textZoom,
          landing = landing,
          onSection = { section = it },
          onScroll = { scroll = it },
          onCentreTap = { showControls = !showControls },
          onPastEnd = { goTo(section + 1) },
          onPastStart = { goTo(section - 1, atEnd = true) },
          modifier = Modifier.fillMaxSize(),
        )

        // A faint position marker stays visible while reading.
        if (!showControls) {
          Text(
            "${section + 1} / ${book.chapters.size} · ${(scroll * 100).roundToInt()}%",
            modifier =
              Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
          )
        }

        AnimatedVisibility(showControls, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
          // Keep the controls clear of the status bar, navigation bar and camera cutout.
          val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
          Column(Modifier.fillMaxSize()) {
            Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)) {
              Row(
                Modifier.fillMaxWidth().windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library") }
                Column(Modifier.weight(1f)) {
                  Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                  Text(
                    book.chapters[section].title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                TextButton(
                  enabled = textZoom > 60,
                  onClick = {
                    textZoom = (textZoom - 10).coerceAtLeast(60)
                    app.library.setBookTextZoom(textZoom)
                  },
                ) {
                  Text("A−")
                }
                TextButton(
                  enabled = textZoom < 250,
                  onClick = {
                    textZoom = (textZoom + 10).coerceAtMost(250)
                    app.library.setBookTextZoom(textZoom)
                  },
                ) {
                  Text("A+")
                }
                IconButton(onClick = { showContents = true }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Contents") }
              }
            }
            Box(Modifier.weight(1f))
            Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)) {
              Row(
                Modifier.fillMaxWidth()
                  .windowInsetsPadding(insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                  .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                TextButton(enabled = section > 0, onClick = { goTo(section - 1) }) { Text("Previous") }
                Text(
                  "Section ${section + 1} of ${book.chapters.size} · ${(scroll * 100).roundToInt()}%",
                  modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                )
                TextButton(enabled = section < book.chapters.size - 1, onClick = { goTo(section + 1) }) { Text("Next") }
              }
            }
          }
        }
      }

      if (showContents) {
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (section - 2).coerceAtLeast(0))
        AlertDialog(
          onDismissRequest = { showContents = false },
          title = { Text("Contents") },
          text = {
            LazyColumn(state = listState) {
              itemsIndexed(book.chapters) { index, chapter ->
                ListItem(
                  onClick = {
                    goTo(index)
                    showContents = false
                  },
                  colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) {
                  Text(
                    chapter.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (index == section) FontWeight.Bold else FontWeight.Normal,
                  )
                }
              }
            }
          },
          confirmButton = { TextButton(onClick = { showContents = false }) { Text("Close") } },
        )
      }
    }
  }
}

/**
 * The web view showing one section. The book's files are served from inside the archive under a
 * made-up address; scripts and all network access are switched off, so a book cannot run code
 * or contact anything.
 */
// Scripts stay off (the annotation is for the explicit `false`). The touch listener only watches
// for taps and never consumes them, so the view's own click and scroll handling is untouched.
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun BookPage(
  book: EpubBook,
  section: Int,
  textZoom: Int,
  landing: androidx.compose.runtime.MutableFloatState,
  onSection: (Int) -> Unit,
  onScroll: (Float) -> Unit,
  onCentreTap: () -> Unit,
  onPastEnd: () -> Unit,
  onPastStart: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val background = MaterialTheme.colorScheme.background
  val dark = background.luminance() < 0.5f
  // Space for the camera cutout and the gesture bar, in the page's own (CSS) pixels.
  val density = LocalDensity.current
  val topInset = (WindowInsets.displayCutout.getTop(density) / density.density).roundToInt()
  val bottomInset = (WindowInsets.navigationBars.getBottom(density) / density.density).roundToInt()
  val styleKey = "$dark/$topInset/$bottomInset"
  val css = remember(styleKey) { BookHtml.css(dark, topInset, bottomInset) }
  val currentCss by rememberUpdatedState(css)
  // What the view is showing, so that following a link inside the book does not trigger a reload.
  val shown = remember { mutableStateOf<String?>(null) }
  val currentOnSection by rememberUpdatedState(onSection)
  val currentOnScroll by rememberUpdatedState(onScroll)
  val currentOnCentreTap by rememberUpdatedState(onCentreTap)
  val currentOnPastEnd by rememberUpdatedState(onPastEnd)
  val currentOnPastStart by rememberUpdatedState(onPastStart)

  AndroidView(
    modifier = modifier,
    factory = { context ->
      WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = false
        settings.blockNetworkLoads = true
        settings.denyLocalAccess()

        webViewClient =
          object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
              val missing = WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
              val path = BookHtml.path(request.url.toString()) ?: return missing
              val type = book.mediaType(path)
              if (BookHtml.isChapterType(type)) {
                // Text is styled in memory; a section too large for that is shown as it is.
                val text = book.readSection(path)
                if (text != null) {
                  val styled = BookHtml.inject(text.toString(Charsets.UTF_8), currentCss)
                  return WebResourceResponse(type, "utf-8", ByteArrayInputStream(styled.toByteArray()))
                }
              }
              val stream = book.open(path) ?: return missing
              return WebResourceResponse(type, null, stream)
            }

            // Links to other parts of the book are followed; links out to the web are ignored.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
              BookHtml.path(request.url.toString()) == null

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
              val path = BookHtml.path(url) ?: return
              shown.value = path
              val index = book.chapterIndex(path)
              if (index >= 0) currentOnSection(index)
            }

            override fun onPageFinished(view: WebView, url: String) {
              val fraction = landing.floatValue
              landing.floatValue = 0f
              if (fraction <= 0f) return
              // Wait until the text is laid out, or there is no height to scroll within yet.
              view.postVisualStateCallback(
                0,
                object : WebView.VisualStateCallback() {
                  override fun onComplete(requestId: Long) {
                    val range = view.contentHeight * view.resources.displayMetrics.density - view.height
                    if (range > 0) view.scrollTo(0, (range * fraction).toInt())
                  }
                },
              )
            }
          }

        setOnScrollChangeListener { view, _, scrollY, _, _ ->
          val web = view as WebView
          val range = web.contentHeight * web.resources.displayMetrics.density - web.height
          currentOnScroll(if (range > 0) (scrollY / range).coerceIn(0f, 1f) else 0f)
        }

        // Tap zones: sides page through the text, the middle shows the controls.
        val webView = this
        val taps =
          GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
              override fun onSingleTapUp(event: MotionEvent): Boolean {
                // A tap on a link belongs to the link.
                val hit = webView.hitTestResult.type
                if (hit == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) return false
                val x = event.x / webView.width.coerceAtLeast(1)
                when {
                  // pageDown/pageUp report false when there is nothing further to scroll to.
                  x > 0.7f -> if (!webView.pageDown(false)) currentOnPastEnd()
                  x < 0.3f -> if (!webView.pageUp(false)) currentOnPastStart()
                  else -> currentOnCentreTap()
                }
                return false
              }
            },
          )
        setOnTouchListener { _, event ->
          taps.onTouchEvent(event)
          false
        }
      }
    },
    update = { view ->
      view.setBackgroundColor(background.toArgb())
      view.settings.textZoom = textZoom
      val target = book.chapters[section].path
      // A changed style means the same section has to be fetched again to pick it up.
      if (shown.value != target || view.tag != styleKey) {
        // Reloading the same section for a new style: come back to the same place in it.
        if (shown.value == target && view.tag != null) {
          val range = view.contentHeight * view.resources.displayMetrics.density - view.height
          if (range > 0) landing.floatValue = (view.scrollY / range).coerceIn(0f, 1f)
        }
        view.tag = styleKey
        shown.value = target
        view.loadUrl(BookHtml.url(target))
      }
    },
    onRelease = { it.destroy() },
  )
}
