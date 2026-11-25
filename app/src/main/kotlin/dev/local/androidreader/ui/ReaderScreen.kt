package dev.local.androidreader.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.local.androidreader.ReaderApp
import dev.local.androidreader.reader.PageSource
import dev.local.androidreader.reader.ZoomMath
import dev.local.androidreader.source.KomgaSource
import dev.local.androidreader.source.ServerCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

private sealed interface ReaderState {
  data object Opening : ReaderState

  data class Ready(val pages: PageSource) : ReaderState

  data class Failed(val message: String) : ReaderState
}

/** What the reader is showing. */
sealed interface ReaderTarget {
  val title: String

  /** Where the reading position is kept. */
  val stateKey: String

  /** A file in the library. */
  data class Local(val path: String) : ReaderTarget {
    override val title: String
      get() = File(path).nameWithoutExtension

    override val stateKey: String
      get() = File(path).name
  }

  /** A book on a server, fetched a page at a time. */
  data class Online(val sourceId: String, val bookId: String, override val title: String) : ReaderTarget {
    override val stateKey: String
      get() = "online|$sourceId|$bookId"
  }
}

private suspend fun open(target: ReaderTarget, app: ReaderApp): PageSource =
  when (target) {
    is ReaderTarget.Local -> withContext(Dispatchers.IO) { PageSource.open(File(target.path)) }
    is ReaderTarget.Online -> {
      val source =
        app.sources().firstOrNull { it.id == target.sourceId } as? KomgaSource
          ?: throw IOException("The server this book came from is no longer set up")
      source.openBook(target.bookId, File(app.cacheDir, "online-pages"))
    }
  }

@Composable
fun ReaderScreen(target: ReaderTarget, onBack: () -> Unit) {
  val app = LocalContext.current.applicationContext as ReaderApp
  val state by
    produceState<ReaderState>(ReaderState.Opening, target) {
      value =
        try {
          val pages = open(target, app)
          if (pages.pageCount == 0) ReaderState.Failed("This comic has no readable pages") else ReaderState.Ready(pages)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          ReaderState.Failed(
            if (target is ReaderTarget.Online) ServerCheck.describe(e, "a Komga server") else e.message ?: "Could not open this file"
          )
        }
    }
  DisposableEffect(state) {
    val opened = (state as? ReaderState.Ready)?.pages
    onDispose { opened?.close() }
  }

  var showControls by rememberSaveable { mutableStateOf(false) }
  // Full screen while reading; the system bars come back together with the reader's own controls.
  ReaderWindow(fullScreen = !showControls && state is ReaderState.Ready)

  Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
    when (val s = state) {
      ReaderState.Opening -> CircularProgressIndicator()
      is ReaderState.Failed ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
          Text(s.message, color = Color.White)
          TextButton(onClick = onBack) { Text("Back") }
        }
      is ReaderState.Ready -> {
        val saved = remember(target) { app.library.state(target.stateKey) }
        Pages(
          title = target.title,
          pages = s.pages,
          startPage = saved.lastPage,
          startRightToLeft = saved.rightToLeft,
          showControls = showControls,
          onToggleControls = { showControls = !showControls },
          onPosition = { page, rtl ->
            app.library.updateState(target.stateKey) {
              it.copy(lastPage = page, pageCount = s.pages.pageCount, rightToLeft = rtl, lastOpened = System.currentTimeMillis())
            }
          },
          onBack = onBack,
        )
      }
    }
  }
}

private tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

/**
 * Puts the window into reading mode and restores it on leaving.
 *
 * The page always draws edge to edge, including under the camera cutout, so showing or hiding
 * the system bars never changes the size of the page: the bars simply appear over it.
 */
// The cutout mode written back on leaving is the value read from the same field on entering.
@SuppressLint("WrongConstant")
@Composable
private fun ReaderWindow(fullScreen: Boolean) {
  val view = LocalView.current
  val window = remember(view) { view.context.findActivity()?.window } ?: return

  DisposableEffect(window) {
    val controller = WindowCompat.getInsetsController(window, view)
    val lightStatus = controller.isAppearanceLightStatusBars
    val lightNavigation = controller.isAppearanceLightNavigationBars
    val cutoutMode = if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode else 0

    // The reader is black, so the bars need light icons whatever the app theme is.
    controller.isAppearanceLightStatusBars = false
    controller.isAppearanceLightNavigationBars = false
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (Build.VERSION.SDK_INT >= 28) {
      // Without this the system letterboxes the window when the status bar hides, and the page jumps.
      window.attributes =
        window.attributes.apply {
          layoutInDisplayCutoutMode =
            if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    onDispose {
      controller.show(WindowInsetsCompat.Type.systemBars())
      controller.isAppearanceLightStatusBars = lightStatus
      controller.isAppearanceLightNavigationBars = lightNavigation
      window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      if (Build.VERSION.SDK_INT >= 28) {
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = cutoutMode }
      }
    }
  }

  DisposableEffect(window, fullScreen) {
    val controller = WindowCompat.getInsetsController(window, view)
    // A swipe from the edge shows the bars briefly, then they hide again by themselves.
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (fullScreen) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    onDispose {}
  }
}

@Composable
private fun Pages(
  title: String,
  pages: PageSource,
  startPage: Int,
  startRightToLeft: Boolean,
  showControls: Boolean,
  onToggleControls: () -> Unit,
  onPosition: (page: Int, rightToLeft: Boolean) -> Unit,
  onBack: () -> Unit,
) {
  val pagerState = rememberPagerState(initialPage = startPage.coerceIn(0, pages.pageCount - 1)) { pages.pageCount }
  var rightToLeft by rememberSaveable { mutableStateOf(startRightToLeft) }
  // While a page is zoomed, one-finger drags pan the page instead of turning it.
  var zoomed by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  // Decode pages at about the window's pixel size: sharp when fitted, without wasting memory.
  val window = LocalWindowInfo.current.containerSize
  val maxSide = maxOf(window.width, window.height).coerceIn(1080, 2800)
  // Recently shown pages stay decoded, so flipping back and forth is instant.
  val cache = remember(pages, maxSide) { LruCache<Int, Bitmap>(5) }

  LaunchedEffect(pagerState.currentPage, rightToLeft) { onPosition(pagerState.currentPage, rightToLeft) }

  fun turn(delta: Int) {
    val target = (pagerState.currentPage + delta).coerceIn(0, pages.pageCount - 1)
    if (target != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(target, animationSpec = tween(180)) }
  }

  HorizontalPager(
    state = pagerState,
    reverseLayout = rightToLeft,
    userScrollEnabled = !zoomed,
    modifier = Modifier.fillMaxSize(),
    beyondViewportPageCount = 1,
    key = { it },
  ) { index ->
    ZoomablePage(
      pages = pages,
      index = index,
      maxSide = maxSide,
      cache = cache,
      isCurrent = pagerState.settledPage == index,
      onZoomChanged = { if (pagerState.currentPage == index) zoomed = it },
      onEdgeTap = { leftEdge -> turn(if (leftEdge != rightToLeft) -1 else 1) },
      onCentreTap = onToggleControls,
    )
  }

  AnimatedVisibility(showControls, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
    val scrim = Color.Black.copy(alpha = 0.8f)
    // Keep the controls clear of the status bar, navigation bar and camera cutout.
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    Column(Modifier.fillMaxSize()) {
      Row(
        Modifier.fillMaxWidth().background(scrim).windowInsetsPadding(insets.onlyTopAndSides()).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onBack) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library", tint = Color.White)
        }
        Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextButton(onClick = { rightToLeft = !rightToLeft }) { Text(if (rightToLeft) "Right to left" else "Left to right") }
      }
      Box(Modifier.weight(1f))
      Column(
        Modifier.fillMaxWidth().background(scrim).windowInsetsPadding(insets.onlyBottomAndSides()).padding(horizontal = 16.dp, vertical = 8.dp)
      ) {
        // The slider shows where the drag will land and only jumps there on release,
        // so dragging across a long comic does not decode every page on the way.
        var dragging by remember { mutableStateOf<Float?>(null) }
        val shown = dragging?.roundToInt() ?: pagerState.currentPage
        Text("Page ${shown + 1} of ${pages.pageCount}", color = Color.White, style = MaterialTheme.typography.labelLarge)
        if (pages.pageCount > 1) {
          // The slider runs the same way the pages do.
          CompositionLocalProvider(LocalLayoutDirection provides if (rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            val sliderState =
              // Arguments: starting value, number of discrete steps (0 = continuous), range.
              remember(pages.pageCount) { SliderState(pagerState.currentPage.toFloat(), 0, 0f..(pages.pageCount - 1).toFloat()) }
            // The thumb follows the pages, except while it is being dragged.
            LaunchedEffect(pagerState.currentPage, dragging) {
              if (dragging == null) sliderState.value = pagerState.currentPage.toFloat()
            }
            Slider(
              state = sliderState,
              onValueChange = {
                dragging = it
                sliderState.value = it
              },
              onValueChangeFinished = {
                dragging?.let { target -> scope.launch { pagerState.scrollToPage(target.roundToInt()) } }
                dragging = null
              },
            )
          }
        }
      }
    }
  }
}

private fun WindowInsets.onlyTopAndSides(): WindowInsets = only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

private fun WindowInsets.onlyBottomAndSides(): WindowInsets = only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

/** Zoom level and pan position of one page. */
@Stable
private class ZoomState {
  var scale by mutableFloatStateOf(1f)
  var offset by mutableStateOf(Offset.Zero)
  var image by mutableStateOf(IntSize.Zero)

  val isZoomed: Boolean
    get() = scale > 1.01f

  private fun clamp(target: Offset, targetScale: Float, box: IntSize): Offset {
    val fitted = ZoomMath.fitted(image.width, image.height, box.width, box.height)
    val p = ZoomMath.clamp(ZoomMath.Point(target.x, target.y), targetScale, fitted, box.width, box.height)
    return Offset(p.x, p.y)
  }

  /** Applies one step of a pinch or drag. [centroid] is in the box's own coordinates. */
  fun transform(centroid: Offset, pan: Offset, zoom: Float, box: IntSize) {
    val newScale = (scale * zoom).coerceIn(1f, ZoomMath.MAX_SCALE)
    val focus = centroid - Offset(box.width / 2f, box.height / 2f)
    val moved = ZoomMath.zoomAbout(ZoomMath.Point(offset.x, offset.y), scale, newScale, ZoomMath.Point(focus.x, focus.y))
    scale = newScale
    offset = if (newScale == 1f) Offset.Zero else clamp(Offset(moved.x, moved.y) + pan, newScale, box)
  }

  suspend fun animateTo(targetScale: Float, targetOffset: Offset) {
    val startScale = scale
    val startOffset = offset
    animate(0f, 1f, animationSpec = tween(200)) { t, _ ->
      scale = startScale + (targetScale - startScale) * t
      offset = startOffset + (targetOffset - startOffset) * t
    }
  }

  /** Double tap: zoom in on the tapped spot, or back out to the whole page. */
  suspend fun toggle(tap: Offset, box: IntSize) {
    if (isZoomed) return animateTo(1f, Offset.Zero)
    val focus = tap - Offset(box.width / 2f, box.height / 2f)
    val moved = ZoomMath.zoomAbout(ZoomMath.Point(0f, 0f), 1f, ZoomMath.DOUBLE_TAP_SCALE, ZoomMath.Point(focus.x, focus.y))
    animateTo(ZoomMath.DOUBLE_TAP_SCALE, clamp(Offset(moved.x, moved.y), ZoomMath.DOUBLE_TAP_SCALE, box))
  }

  fun reset() {
    scale = 1f
    offset = Offset.Zero
  }
}

@Composable
private fun ZoomablePage(
  pages: PageSource,
  index: Int,
  maxSide: Int,
  cache: LruCache<Int, Bitmap>,
  isCurrent: Boolean,
  onZoomChanged: (Boolean) -> Unit,
  onEdgeTap: (leftEdge: Boolean) -> Unit,
  onCentreTap: () -> Unit,
) {
  val bitmap by
    produceState(cache.get(index)?.let { Result.success(it) }, pages, index) {
      if (value == null) {
        value = withContext(Dispatchers.IO) { runCatching { pages.decode(index, maxSide).also { cache.put(index, it) } } }
      }
    }
  val zoom = remember { ZoomState() }
  val scope = rememberCoroutineScope()

  LaunchedEffect(bitmap) { bitmap?.getOrNull()?.let { zoom.image = IntSize(it.width, it.height) } }
  LaunchedEffect(zoom.isZoomed) { onZoomChanged(zoom.isZoomed) }
  // A page that has been swiped away comes back at normal size.
  LaunchedEffect(isCurrent) { if (!isCurrent) zoom.reset() }

  Box(
    Modifier.fillMaxSize().pointerInput(Unit) {
      val slop = viewConfiguration.touchSlop
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var moved = false
        var pinched = false
        var travelled = Offset.Zero
        var upTime = down.uptimeMillis
        var upPosition = down.position

        while (true) {
          val event = awaitPointerEvent()
          if (event.changes.count { it.pressed } >= 2) pinched = true
          // One finger at normal size belongs to the pager; pinching, or dragging a zoomed page, is ours.
          if (pinched || zoom.isZoomed) {
            val pan = event.calculatePan()
            val zoomChange = event.calculateZoom()
            if (zoomChange != 1f || pan != Offset.Zero) zoom.transform(event.calculateCentroid(useCurrent = true), pan, zoomChange, size)
            event.changes.forEach { if (it.positionChanged()) it.consume() }
          }
          travelled += event.changes.first().let { it.position - it.previousPosition }
          if (travelled.getDistance() > slop) moved = true
          if (event.changes.none { it.pressed }) {
            upTime = event.changes.first().uptimeMillis
            upPosition = event.changes.first().position
            break
          }
        }

        if (pinched) {
          // Let go slightly zoomed: settle back to the whole page rather than leave it almost fitted.
          if (zoom.scale < 1.1f) scope.launch { zoom.animateTo(1f, Offset.Zero) }
          return@awaitEachGesture
        }
        if (moved || upTime - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis) return@awaitEachGesture

        // A tap. On the outer parts of an unzoomed page it turns the page at once.
        val x = upPosition.x / size.width
        if (!zoom.isZoomed && (x < 0.3f || x > 0.7f)) {
          onEdgeTap(x < 0.3f)
          return@awaitEachGesture
        }
        // Elsewhere, wait briefly to tell a single tap (controls) from a double tap (zoom).
        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown(requireUnconsumed = false) }
        if (second == null) {
          onCentreTap()
        } else {
          second.consume()
          scope.launch { zoom.toggle(second.position, size) }
          waitForUpOrCancellation()
        }
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
              scaleX = zoom.scale
              scaleY = zoom.scale
              translationX = zoom.offset.x
              translationY = zoom.offset.y
            },
        )
    }
  }
}
