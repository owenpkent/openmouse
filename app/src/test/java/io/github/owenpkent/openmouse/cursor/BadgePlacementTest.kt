package io.github.owenpkent.openmouse.cursor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BadgePlacementTest {

    // A 100 x 30 badge, 50 px clear of the cross-hair center, in a 1000 x 800 view.
    private fun place(x: Float, y: Float, viewW: Float = 1000f, viewH: Float = 800f) =
        BadgePlacement.place(x, y, offset = 50f, width = 100f, height = 30f, viewWidth = viewW, viewHeight = viewH)

    @Test
    fun sitsUpAndToTheLeftOfTheCrossHair() {
        val b = place(500f, 400f)
        assertEquals(350f, b.left, EPS) // 500 - 50 - 100
        assertEquals(450f, b.right, EPS)
        assertEquals(320f, b.top, EPS) // 400 - 50 - 30
        assertEquals(350f, b.bottom, EPS)
    }

    @Test
    fun flipsToTheRightNearTheLeftEdge() {
        val b = place(80f, 400f)
        assertEquals(130f, b.left, EPS) // 80 + 50
        assertEquals(320f, b.top, EPS) // the vertical side is unchanged
    }

    @Test
    fun flipsBelowNearTheTopEdge() {
        val b = place(500f, 40f)
        assertEquals(350f, b.left, EPS)
        assertEquals(90f, b.top, EPS) // 40 + 50
    }

    @Test
    fun staysInsideTheViewAtTheFarCorner() {
        // Flipped right and below at the bottom-right corner would leave the
        // view, so the badge is clamped back in.
        val b = place(990f, 790f)
        assertTrue(b.right <= 1000f)
        assertTrue(b.bottom <= 800f)
        assertTrue(b.left >= 0f)
        assertTrue(b.top >= 0f)
    }

    @Test
    fun aViewSmallerThanTheBadgePinsItToTheOrigin() {
        val b = place(10f, 10f, viewW = 50f, viewH = 20f)
        assertEquals(0f, b.left, EPS)
        assertEquals(0f, b.top, EPS)
        assertEquals(100f, b.right, EPS) // the size is kept; only the position gives
        assertEquals(30f, b.bottom, EPS)
    }

    private companion object {
        const val EPS = 0.001f
    }
}
