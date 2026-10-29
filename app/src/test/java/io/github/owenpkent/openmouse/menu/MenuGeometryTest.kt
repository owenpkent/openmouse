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
        columns = 2,
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
            // Expanded strip is 50 + 10 + (5*50 + 4*10) = 350 tall; screen is only 300.
            g.layout(1000, 300, expanded = true)

            // Toggle is shifted up but stays fully on screen (top clamped to >= 0).
            assertEquals(0f, g.toggle.top, EPS)
            assertBounds(g.toggle, 820f, 0f, 990f, 50f)
        }

        @Test
        fun itemsNeverOverlapEachOtherOrTheToggle() {
            val g = geometry()
            g.layout(1000, 1000, expanded = true)
            val all = (0 until 10).map { g.item(it) } + g.toggle
            for (i in all.indices) for (j in all.indices) {
                if (i == j) continue
                val a = all[i]
                val b = all[j]
                val overlap = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
                assertTrue(!overlap, "cells $i and $j overlap: $a vs $b")
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
            val g = MenuGeometry(itemCount = 3, itemW = 80f, itemH = 50f, gap = 10f, margin = 10f, columns = 0)
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
