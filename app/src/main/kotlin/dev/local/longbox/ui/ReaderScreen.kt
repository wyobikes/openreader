package dev.local.longbox.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.longbox.LongboxApp
import dev.local.longbox.reader.PageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private sealed interface ReaderState {
  data object Opening : ReaderState

  data class Ready(val pages: PageSource) : ReaderState

  data class Failed(val message: String) : ReaderState
}

@Composable
fun ReaderScreen(path: String, onBack: () -> Unit) {
  val app = LocalContext.current.applicationContext as LongboxApp
  val file = remember(path) { File(path) }
  val state by
    produceState<ReaderState>(ReaderState.Opening, file) {
      value =
        withContext(Dispatchers.IO) {
          runCatching { PageSource.open(file) }
            .fold(
              { if (it.pageCount == 0) ReaderState.Failed("This file has no readable pages") else ReaderState.Ready(it) },
              { ReaderState.Failed(it.message ?: "Could not open this file") },
            )
        }
    }
  DisposableEffect(state) {
    val opened = (state as? ReaderState.Ready)?.pages
    onDispose { opened?.close() }
  }

  Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
    when (val s = state) {
      ReaderState.Opening -> CircularProgressIndicator()
      is ReaderState.Failed ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
          Text(s.message, color = Color.White)
          TextButton(onClick = onBack) { Text("Back") }
        }
      is ReaderState.Ready -> {
        val saved = remember(file) { app.library.state(file) }
        Pages(
          title = file.nameWithoutExtension,
          pages = s.pages,
          startPage = saved.lastPage,
          startRightToLeft = saved.rightToLeft,
          onPosition = { page, rtl ->
            app.library.updateState(file) {
              it.copy(lastPage = page, pageCount = s.pages.pageCount, rightToLeft = rtl, lastOpened = System.currentTimeMillis())
            }
          },
          onBack = onBack,
        )
      }
    }
  }
}

@Composable
private fun Pages(
  title: String,
  pages: PageSource,
  startPage: Int,
  startRightToLeft: Boolean,
  onPosition: (page: Int, rightToLeft: Boolean) -> Unit,
  onBack: () -> Unit,
) {
  val pagerState = rememberPagerState(initialPage = startPage.coerceIn(0, pages.pageCount - 1)) { pages.pageCount }
  var rightToLeft by rememberSaveable { mutableStateOf(startRightToLeft) }
  var showControls by rememberSaveable { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  // Decode pages at about the window's pixel size: sharp when fitted, without wasting memory.
  val window = LocalWindowInfo.current.containerSize
  val maxSide = maxOf(window.width, window.height).coerceIn(1080, 2800)

  LaunchedEffect(pagerState.currentPage, rightToLeft) { onPosition(pagerState.currentPage, rightToLeft) }

  fun turn(delta: Int) {
    val target = (pagerState.currentPage + delta).coerceIn(0, pages.pageCount - 1)
    scope.launch { pagerState.animateScrollToPage(target) }
  }

  HorizontalPager(state = pagerState, reverseLayout = rightToLeft, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { index ->
    ZoomablePage(
      pages = pages,
      index = index,
      maxSide = maxSide,
      onTap = { fraction ->
        // Outer thirds turn the page; the middle shows or hides the controls.
        val forward = if (rightToLeft) fraction < 0.33f else fraction > 0.67f
        val backward = if (rightToLeft) fraction > 0.67f else fraction < 0.33f
        when {
          forward -> turn(1)
          backward -> turn(-1)
          else -> showControls = !showControls
        }
      },
    )
  }

  AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      Row(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.75f)).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onBack) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library", tint = Color.White)
        }
        Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextButton(onClick = { rightToLeft = !rightToLeft }) { Text(if (rightToLeft) "Right to left" else "Left to right") }
      }
      Box(Modifier.weight(1f))
      Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
          "Page ${pagerState.currentPage + 1} of ${pages.pageCount}",
          color = Color.White,
          style = MaterialTheme.typography.labelLarge,
        )
        if (pages.pageCount > 1) {
          Slider(
            value = pagerState.currentPage.toFloat(),
            onValueChange = { scope.launch { pagerState.scrollToPage(it.roundToInt()) } },
            valueRange = 0f..(pages.pageCount - 1).toFloat(),
          )
        }
      }
    }
  }
}

@Composable
private fun ZoomablePage(pages: PageSource, index: Int, maxSide: Int, onTap: (xFraction: Float) -> Unit) {
  val bitmap by
    produceState<Result<Bitmap>?>(null, pages, index) {
      value = withContext(Dispatchers.IO) { runCatching { pages.decode(index, maxSide) } }
    }
  var scale by remember { mutableFloatStateOf(1f) }
  var offset by remember { mutableStateOf(Offset.Zero) }

  Box(
    Modifier.fillMaxSize()
      .pointerInput(Unit) {
        detectTapGestures(
          onDoubleTap = {
            if (scale > 1f) {
              scale = 1f
              offset = Offset.Zero
            } else {
              scale = 2.5f
            }
          },
          onTap = { if (scale == 1f) onTap(it.x / size.width) },
        )
      }
      .pointerInput(Unit) {
        awaitEachGesture {
          awaitFirstDown(requireUnconsumed = false)
          do {
            val event = awaitPointerEvent()
            // One finger at normal size belongs to the pager; pinching or panning a zoomed page is ours.
            if (event.changes.size >= 2 || scale > 1f) {
              scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
              val limitX = size.width * (scale - 1f) / 2f
              val limitY = size.height * (scale - 1f) / 2f
              val moved = offset + event.calculatePan()
              offset = Offset(moved.x.coerceIn(-limitX, limitX), moved.y.coerceIn(-limitY, limitY))
              event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
          } while (event.changes.any { it.pressed })
        }
      },
    contentAlignment = Alignment.Center,
  ) {
    val result = bitmap
    when {
      result == null -> CircularProgressIndicator()
      result.isFailure -> Text("Page ${index + 1} could not be shown", color = Color.White)
      else ->
        Image(
          result.getOrThrow().asImageBitmap(),
          contentDescription = "Page ${index + 1}",
          contentScale = ContentScale.Fit,
          modifier =
            Modifier.fillMaxSize().graphicsLayer {
              scaleX = scale
              scaleY = scale
              translationX = offset.x
              translationY = offset.y
            },
        )
    }
  }
}
