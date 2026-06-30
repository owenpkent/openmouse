package io.github.owenpkent.openmouse.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure menu layout / hit-testing. Uses round numbers so the
 * expected pixel positions are easy to follow.
 */
class MenuGeometryTest {

    private fun geometry() = MenuGeometry(
        itemCount = 10,
        itemW = 80f,
        itemH = 50f,
        gap = 10f,
        margin = 10f,
        columns = 2,
    )

    @Test
    fun panelWidthSpansColumnsAndGap() {
        assertEquals(170f, geometry().panelWidth, EPS) // 2*80 + 1*10
    }

    @Test
    fun expandedLaysOutTwoColumnGridDockedRight() {
        val g = geometry()
        g.layout(width = 1000, height = 1000, expanded = true)

        // 6 rows (toggle + 5 item rows): totalH = 6*50 + 5*10 = 350; top = 325.
        // Docked right: left = 1000 - 10 - 170 = 820, right = 990.
        assertBounds(g.toggle, 820f, 325f, 990f, 375f)

        // Grid starts at top + itemH + gap = 385.
        assertBounds(g.item(0), 820f, 385f, 900f, 435f) // row 0, col 0
        assertBounds(g.item(1), 910f, 385f, 990f, 435f) // row 0, col 1
        assertBounds(g.item(2), 820f, 445f, 900f, 495f) // row 1, col 0
        assertBounds(g.item(9), 910f, 625f, 990f, 675f) // row 4, col 1
    }

    @Test
    fun hitTestIdentifiesToggleItemsAndMisses() {
        val g = geometry()
        g.layout(1000, 1000, expanded = true)

        assertEquals(MenuGeometry.TOGGLE_INDEX, g.hitTest(830f, 350f, expanded = true))
        assertEquals(0, g.hitTest(830f, 400f, expanded = true))
        assertEquals(1, g.hitTest(950f, 400f, expanded = true))
        assertEquals(2, g.hitTest(830f, 460f, expanded = true))
        assertNull(g.hitTest(500f, 500f, expanded = true)) // empty center of screen
    }

    @Test
    fun collapsedExposesOnlyTheToggle() {
        val g = geometry()
        g.layout(1000, 1000, expanded = false)

        // Only the toggle row: totalH = 50; top = 475.
        assertBounds(g.toggle, 820f, 475f, 990f, 525f)
        assertEquals(MenuGeometry.TOGGLE_INDEX, g.hitTest(830f, 500f, expanded = false))
        // Items are not hit-tested while collapsed.
        assertNull(g.hitTest(830f, 400f, expanded = false))
    }

    private fun assertBounds(b: Bounds, left: Float, top: Float, right: Float, bottom: Float) {
        assertEquals(left, b.left, EPS)
        assertEquals(top, b.top, EPS)
        assertEquals(right, b.right, EPS)
        assertEquals(bottom, b.bottom, EPS)
    }

    companion object {
        private const val EPS = 0.001f
    }
}
