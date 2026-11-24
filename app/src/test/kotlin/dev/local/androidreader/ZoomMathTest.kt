package dev.local.androidreader

import dev.local.androidreader.reader.ZoomMath
import dev.local.androidreader.reader.ZoomMath.Point
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomMathTest {
  private fun assertPoint(expected: Point, actual: Point) {
    assertEquals(expected.x, actual.x, 0.01f)
    assertEquals(expected.y, actual.y, 0.01f)
  }

  @Test
  fun fitsTallAndWidePagesInsideTheScreen() {
    // Tall page on a phone: limited by height.
    assertPoint(Point(800f, 2400f), ZoomMath.fitted(1000, 3000, 1080, 2400))
    // Wide spread: limited by width.
    assertPoint(Point(1080f, 540f), ZoomMath.fitted(2000, 1000, 1080, 2400))
    // Unknown image size falls back to the box.
    assertPoint(Point(1080f, 2400f), ZoomMath.fitted(0, 0, 1080, 2400))
  }

  @Test
  fun panIsLimitedToTheOverhang() {
    val fitted = Point(1080f, 1620f)
    // Not zoomed: nothing overhangs, so no movement at all.
    assertPoint(Point(0f, 0f), ZoomMath.clamp(Point(300f, -300f), 1f, fitted, 1080, 2400))
    // 2x: 2160 wide (540 overhang each side), 3240 tall (420 each side).
    assertPoint(Point(540f, -420f), ZoomMath.clamp(Point(9999f, -9999f), 2f, fitted, 1080, 2400))
    assertPoint(Point(-100f, 50f), ZoomMath.clamp(Point(-100f, 50f), 2f, fitted, 1080, 2400))
    // 1.2x: wider than the screen but still shorter, so only horizontal movement.
    assertPoint(Point(108f, 0f), ZoomMath.clamp(Point(500f, 500f), 1.2f, fitted, 1080, 2400))
  }

  @Test
  fun zoomKeepsThePointUnderTheFingersStill() {
    val focus = Point(200f, -300f)
    val offset = Point(40f, 10f)
    val scale = 1.5f
    val newScale = 3f
    // The content point under the focus before zooming...
    val contentX = (focus.x - offset.x) / scale
    val contentY = (focus.y - offset.y) / scale
    val moved = ZoomMath.zoomAbout(offset, scale, newScale, focus)
    // ...is still under the focus afterwards.
    assertEquals(focus.x, contentX * newScale + moved.x, 0.01f)
    assertEquals(focus.y, contentY * newScale + moved.y, 0.01f)
    // Zooming about the centre from rest needs no offset.
    assertPoint(Point(0f, 0f), ZoomMath.zoomAbout(Point(0f, 0f), 1f, 2.5f, Point(0f, 0f)))
    // Double-tapping the top-left corner brings that corner toward the centre.
    assertPoint(Point(600f, 900f), ZoomMath.zoomAbout(Point(0f, 0f), 1f, 2.5f, Point(-400f, -600f)))
  }
}
