package dev.local.androidreader.reader

import kotlin.math.max

/** The geometry behind pinch and double-tap zoom, kept free of UI types so it can be tested. */
object ZoomMath {
  const val MAX_SCALE = 5f
  const val DOUBLE_TAP_SCALE = 2.5f

  data class Point(val x: Float, val y: Float)

  /** Size of an image shown as large as possible inside a box without cropping. */
  fun fitted(imageWidth: Int, imageHeight: Int, boxWidth: Int, boxHeight: Int): Point {
    if (imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0 || boxHeight <= 0) return Point(boxWidth.toFloat(), boxHeight.toFloat())
    val ratio = minOf(boxWidth / imageWidth.toFloat(), boxHeight / imageHeight.toFloat())
    return Point(imageWidth * ratio, imageHeight * ratio)
  }

  /**
   * Keeps the page from being dragged away from the screen: an axis can only move by the amount
   * the zoomed page overhangs the box on that axis, and not at all when it still fits.
   */
  fun clamp(offset: Point, scale: Float, fitted: Point, boxWidth: Int, boxHeight: Int): Point {
    val limitX = max(0f, (fitted.x * scale - boxWidth) / 2f)
    val limitY = max(0f, (fitted.y * scale - boxHeight) / 2f)
    return Point(offset.x.coerceIn(-limitX, limitX), offset.y.coerceIn(-limitY, limitY))
  }

  /**
   * The offset that keeps whatever is under [focus] in place while the scale changes.
   * Offsets and [focus] are measured from the centre of the box.
   */
  fun zoomAbout(offset: Point, scale: Float, newScale: Float, focus: Point): Point {
    val factor = newScale / scale
    return Point(focus.x - (focus.x - offset.x) * factor, focus.y - (focus.y - offset.y) * factor)
  }
}
