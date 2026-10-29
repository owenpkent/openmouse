package io.github.owenpkent.openmouse.menu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure menu layout / hit-testing. Uses round numbers so the
 * expected pixel positions are easy to follow. The toggle is anchored at its
 * collapsed center and the grid grows below it (clamped on screen).
 */
class MenuGeometryTest {

    private fun geometry() = MenuGeometry(
        itemCount = 10,
        itemW = 80f,
        itemH = 50f,
        gap = 10f,
        margin = 10f,
        minColumns = 2,
    )

    /** The production menu at density 1: 13 entries (Pause last), 84 x 52 dp cells. */
    private fun productionGeometry() = MenuGeometry(
        itemCount = 13,
        itemW = 84f,
        itemH = 52f,
        gap = 6f,
        margin = 8f,
        minColumns = 2,
    )

    @Nested
    inner class Layout {

        @Test
        fun panelWidthSpansColumnsAndGap() {
            assertEquals(170f, geometry().panelWidth, EPS) // 2*80 + 1*10
        }

        @Test
        fun collapsedCentersTheToggle() {
            val g = geometry()
            g.layout(1000, 1000, expanded = false)

            // Toggle centered vertically: top = (1000 - 50) / 2 = 475.
            assertBounds(g.toggle, 820f, 475f, 990f, 525f)
            assertEquals(MenuGeometry.TOGGLE_INDEX, g.hitTest(830f, 500f, expanded = false))
            // Items are not hit-tested while collapsed.
            assertNull(g.hitTest(830f, 600f, expanded = false))
        }

        @Test
        fun expandedAnchorsToggleAndGrowsGridBelow() {
            val g = geometry()
            g.layout(width = 1000, height = 1000, expanded = true)

            // Toggle stays at its collapsed center (475); the grid grows below it.
            assertBounds(g.toggle, 820f, 475f, 990f, 525f)

            // Grid starts at toggle.bottom + gap = 525 + 10 = 535.
            assertBounds(g.item(0), 820f, 535f, 900f, 585f) // row 0, col 0
            assertBounds(g.item(1), 910f, 535f, 990f, 585f) // row 0, col 1
            assertBounds(g.item(2), 820f, 595f, 900f, 645f) // row 1, col 0
            assertBounds(g.item(9), 910f, 775f, 990f, 825f) // row 4, col 1
        }

        @Test
        fun leftDockMirrorsToTheLeftEdge() {
            val g = geometry()
            g.layout(1000, 1000, expanded = true, dockRight = false)

            // Docked left: left = margin = 10, right = 10 + 170 = 180.
            assertBounds(g.toggle, 10f, 475f, 180f, 525f)
            assertBounds(g.item(0), 10f, 535f, 90f, 585f) // row 0, col 0
            assertBounds(g.item(1), 100f, 535f, 180f, 585f) // row 0, col 1
            assertEquals(0, g.hitTest(50f, 560f, expanded = true))
        }

        @Test
        fun clampsStripOnScreenWhenTooTall() {
            val g = geometry()
            // Expanded strip is 50 + 10 + (5*50 + 4*10) = 350 tall; screen is only
            // 300, and at 200 wide a third column (260) cannot fit either.
            g.layout(200, 300, expanded = true)
            assertEquals(2, g.columns)

            // Toggle is shifted up but stays fully on screen (top clamped to >= 0).
            assertEquals(0f, g.toggle.top, EPS)
            assertBounds(g.toggle, 20f, 0f, 190f, 50f)
        }

        @Test
        fun itemsNeverOverlapEachOtherOrTheToggle() {
            val g = geometry()
            g.layout(1000, 1000, expanded = true)
            assertNoOverlap(g, 10)
        }
    }

    @Nested
    inner class ShortScreens {

        @Test
        fun tallScreenKeepsTheMinimumColumns() {
            val g = productionGeometry()
            g.layout(400, 800, expanded = true)
            assertEquals(2, g.columns)
            assertEquals(174f, g.panelWidth, EPS) // 2*84 + 6
            // 7 rows: toggle 52 + 6 + 7*52 + 6*6 = 458, fits in 800 without a shift.
            assertEquals(458f, g.item(12).bottom - g.toggle.top, EPS)
        }

        @Test
        fun landscapeWidensTheGridSoTheLastEntryStaysOnScreen() {
            // 800 x 400 dp landscape: two columns would put Pause at y 406..458,
            // entirely below the screen. Three columns make 5 rows (342 tall).
            val g = productionGeometry()
            g.layout(800, 400, expanded = true)
            assertEquals(3, g.columns)
            assertEquals(264f, g.panelWidth, EPS) // 3*84 + 2*6
            assertFullyOnScreen(g, 800, 400, itemCount = 13)
            assertNoOverlap(g, 13)
            // The widened strip is still docked to the right edge.
            assertEquals(792f, g.toggle.right, EPS)
            assertEquals(MenuGeometry.TOGGLE_INDEX, g.hitTest(700f, g.toggle.top + 1f, expanded = true))
        }

        @Test
        fun pauseKeepsAFullHitTargetAfterPortraitToLandscape() {
            // A paused user rotates: the Resume entry (index 12) must keep its
            // whole 84 x 52 target reachable on the shorter layout.
            val g = productionGeometry()
            g.layout(400, 800, expanded = true)
            assertFullyOnScreen(g, 400, 800, itemCount = 13)
            g.layout(800, 400, expanded = true)
            val pause = g.item(12)
            assertEquals(84f, pause.right - pause.left, EPS)
            assertEquals(52f, pause.bottom - pause.top, EPS)
            assertTrue(pause.bottom <= 400f, "Pause runs off the bottom: $pause")
            assertEquals(12, g.hitTest(pause.left + 1f, pause.top + 1f, expanded = true))
            assertEquals(12, g.hitTest(pause.right - 1f, pause.bottom - 1f, expanded = true))
        }

        @Test
        fun veryShortScreenKeepsWideningUntilItFits() {
            // 360 dp tall: 3 columns (342) fit. 300 dp tall: 4 columns (284).
            val g = productionGeometry()
            g.layout(800, 360, expanded = true)
            assertEquals(3, g.columns)
            assertFullyOnScreen(g, 800, 360, itemCount = 13)
            g.layout(800, 300, expanded = true)
            assertEquals(4, g.columns)
            assertFullyOnScreen(g, 800, 300, itemCount = 13)
        }

        @Test
        fun wideningStopsAtTheScreenWidth() {
            // 300 x 300: 3 columns are 264 wide and fit in 300 - 16; 4 would be
            // 354. The grid cannot fit the height, so it clamps to the top instead.
            val g = productionGeometry()
            g.layout(300, 300, expanded = true)
            assertEquals(3, g.columns)
            assertEquals(0f, g.toggle.top, EPS)
        }

        @Test
        fun collapsedUsesTheSameWidthAsExpanded() {
            // The column count comes from the expanded strip even while
            // collapsed, so the toggle keeps one width and one horizontal place
            // when the grid opens (it still shifts up to fit, as always).
            val g = productionGeometry()
            g.layout(800, 400, expanded = false)
            val collapsed = g.toggle
            assertEquals(3, g.columns)
            assertEquals(174f, collapsed.top, EPS) // centered: (400 - 52) / 2
            g.layout(800, 400, expanded = true)
            assertEquals(collapsed.left, g.toggle.left, EPS)
            assertEquals(collapsed.right, g.toggle.right, EPS)
        }

        @Test
        fun leftDockWidensTowardTheCenter() {
            val g = productionGeometry()
            g.layout(800, 400, expanded = true, dockRight = false)
            assertEquals(3, g.columns)
            assertEquals(8f, g.toggle.left, EPS)
            assertEquals(272f, g.toggle.right, EPS)
            assertFullyOnScreen(g, 800, 400, itemCount = 13)
        }

        private fun assertFullyOnScreen(g: MenuGeometry, width: Int, height: Int, itemCount: Int) {
            val all = (0 until itemCount).map { g.item(it) } + g.toggle
            for ((i, b) in all.withIndex()) {
                assertTrue(b.left >= 0f && b.right <= width, "cell $i off screen horizontally: $b")
                assertTrue(b.top >= 0f && b.bottom <= height, "cell $i off screen vertically: $b")
            }
        }
    }

    @Nested
    inner class HitTesting {

        @Test
        fun hitTestIdentifiesToggleItemsGapsAndMisses() {
            val g = geometry()
            g.layout(1000, 1000, expanded = true)

            assertEquals(MenuGeometry.TOGGLE_INDEX, g.hitTest(830f, 500f, expanded = true))
            assertEquals(0, g.hitTest(830f, 560f, expanded = true))
            assertEquals(1, g.hitTest(950f, 560f, expanded = true))
            assertEquals(2, g.hitTest(830f, 620f, expanded = true))
            assertNull(g.hitTest(500f, 500f, expanded = true)) // empty center of screen
            assertNull(g.hitTest(830f, 590f, expanded = true)) // vertical gap between rows 0 and 1
            assertNull(g.hitTest(905f, 560f, expanded = true)) // horizontal gap between columns
        }

        @Test
        fun collapsedHitTestIgnoresStaleCells() {
            val g = geometry()
            g.layout(1000, 1000, expanded = true)
            // Hit-testing with expanded=false must not report an item even though
            // the last layout computed cells.
            assertNull(g.hitTest(830f, 560f, expanded = false))
        }

        @Test
        fun nanCoordinatesHitNothing() {
            val g = geometry()
            g.layout(1000, 1000, expanded = true)
            assertNull(g.hitTest(Float.NaN, 500f, expanded = true))
            assertNull(g.hitTest(830f, Float.NaN, expanded = true))
        }
    }

    @Nested
    inner class DegenerateInputs {

        @Test
        fun negativeItemCountBehavesAsEmpty() {
            val g = MenuGeometry(itemCount = -3, itemW = 80f, itemH = 50f, gap = 10f, margin = 10f)
            g.layout(1000, 1000, expanded = true)
            assertBounds(g.toggle, 820f, 475f, 990f, 525f)
            assertNull(g.hitTest(830f, 560f, expanded = true))
        }

        @Test
        fun zeroOrNegativeColumnsBecomeOne() {
            val g = MenuGeometry(itemCount = 3, itemW = 80f, itemH = 50f, gap = 10f, margin = 10f, minColumns = 0)
            assertEquals(80f, g.panelWidth, EPS)
            g.layout(1000, 1000, expanded = true)
            // One column: items stack straight down.
            assertEquals(g.item(0).left, g.item(2).left, EPS)
            assertEquals(g.item(0).bottom + 10f, g.item(1).top, EPS)
        }

        @Test
        fun tinyScreenStillPinsTheToggleToTheTop() {
            val g = geometry()
            g.layout(50, 20, expanded = true)
            assertEquals(0f, g.toggle.top, EPS)
            assertEquals(50f, g.toggle.bottom, EPS)
        }

        @Test
        fun zeroSizedScreenDoesNotThrow() {
            val g = geometry()
            g.layout(0, 0, expanded = true)
            g.layout(-100, -100, expanded = true, dockRight = false)
            assertNull(g.hitTest(0f, 0f, expanded = false))
        }
    }

    private fun assertNoOverlap(g: MenuGeometry, itemCount: Int) {
        val all = (0 until itemCount).map { g.item(it) } + g.toggle
        for (i in all.indices) for (j in all.indices) {
            if (i == j) continue
            val a = all[i]
            val b = all[j]
            val overlap = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
            assertTrue(!overlap, "cells $i and $j overlap: $a vs $b")
        }
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
