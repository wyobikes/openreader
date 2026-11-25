package dev.local.androidreader.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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

private sealed interface BookState {
  data object Opening : BookState

  data class Ready(val book: EpubBook) : BookState

  data class Failed(val message: String) : BookState
}

/** Reads an EPUB one section at a time, as scrolling text. */
@OptIn(ExperimentalMaterial3Api::class)
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
      // How far down the section the reader is, 0 to 1; restored once, when the book opens.
      var scroll by remember { mutableFloatStateOf(if (section == saved.lastPage) saved.scroll else 0f) }

      fun save() =
        app.library.updateState(file) {
          it.copy(lastPage = section, pageCount = book.chapters.size, scroll = scroll, lastOpened = System.currentTimeMillis())
        }
      DisposableEffect(section) {
        save()
        onDispose { save() }
      }

      Scaffold(
        topBar = {
          TopAppBar(
            title = {
              Column {
                Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(
                  book.chapters[section].title,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            },
            navigationIcon = {
              IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library") }
            },
            actions = {
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
            },
          )
        },
        bottomBar = {
          Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              TextButton(
                enabled = section > 0,
                onClick = {
                  scroll = 0f
                  section--
                },
              ) {
                Text("Previous")
              }
              Text(
                "Section ${section + 1} of ${book.chapters.size}",
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
              )
              TextButton(
                enabled = section < book.chapters.size - 1,
                onClick = {
                  scroll = 0f
                  section++
                },
              ) {
                Text("Next")
              }
            }
          }
        },
      ) { padding ->
        BookPage(
          book = book,
          section = section,
          textZoom = textZoom,
          startScroll = scroll,
          onSection = {
            scroll = 0f
            section = it
          },
          onScroll = { scroll = it },
          modifier = Modifier.padding(padding).fillMaxSize(),
        )
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
                    scroll = 0f
                    section = index
                    showContents = false
                  },
                  colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
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
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BookPage(
  book: EpubBook,
  section: Int,
  textZoom: Int,
  startScroll: Float,
  onSection: (Int) -> Unit,
  onScroll: (Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  val background = MaterialTheme.colorScheme.background
  val dark = background.luminance() < 0.5f
  val css = remember(dark) { BookHtml.css(dark) }
  // What the view is showing, so that following a link inside the book does not trigger a reload.
  val shown = remember { mutableStateOf<String?>(null) }
  val pendingScroll = remember { mutableFloatStateOf(startScroll) }
  val currentOnSection = androidx.compose.runtime.rememberUpdatedState(onSection)
  val currentOnScroll = androidx.compose.runtime.rememberUpdatedState(onScroll)

  AndroidView(
    modifier = modifier,
    factory = { context ->
      WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = false
        settings.blockNetworkLoads = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        webViewClient =
          object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
              val missing = WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
              val path = BookHtml.path(request.url.toString()) ?: return missing
              val stream = book.open(path) ?: return missing
              val type = book.mediaType(path)
              if (!BookHtml.isChapterType(type)) return WebResourceResponse(type, null, stream)
              val styled = stream.use { BookHtml.inject(it.readBytes().toString(Charsets.UTF_8), css) }
              return WebResourceResponse(type, "utf-8", ByteArrayInputStream(styled.toByteArray()))
            }

            // Links to other parts of the book are followed; links out to the web are ignored.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
              BookHtml.path(request.url.toString()) == null

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
              val path = BookHtml.path(url) ?: return
              shown.value = path
              val index = book.chapterIndex(path)
              if (index >= 0) currentOnSection.value(index)
            }

            override fun onPageFinished(view: WebView, url: String) {
              val fraction = pendingScroll.floatValue
              pendingScroll.floatValue = 0f
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
          currentOnScroll.value(if (range > 0) (scrollY / range).coerceIn(0f, 1f) else 0f)
        }
      }
    },
    update = { view ->
      view.setBackgroundColor(background.toArgb())
      view.settings.textZoom = textZoom
      val target = book.chapters[section].path
      // A changed style means the same section has to be fetched again to pick it up.
      val styleKey = "$dark"
      if (shown.value != target || view.tag != styleKey) {
        view.tag = styleKey
        shown.value = target
        view.loadUrl(BookHtml.url(target))
      }
    },
    onRelease = { it.destroy() },
  )
}
