package io.github.owenpkent.openmouse.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import io.github.owenpkent.openmouse.menu.MenuGeometry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.math.abs

/**
 * Jazzer fuzz target for the menu layout and hit-testing, the counterpart of
 * the toolbar-model half of the PowerToys Dwell Click fuzz target.
 *
 * Construction values, screen sizes, dock side, expand state, and probe points
 * are all fuzzer-chosen (negative, zero, NaN, infinite included). Invariants:
 *
 * - layout and hit-testing never throw, whatever the sizes;
 * - every item index is addressable after an expanded layout;
 * - a hit result is consistent with the bounds: the toggle index means the
 *   toggle contains the point, an item index means that item contains it and
 *   the menu is expanded, and null means neither the toggle nor any visible
 *   item contains it;
 * - the toggle spans exactly the panel width;
 * - the column count stays between the preferred count and the entry count.
 *
 * Without `JAZZER_FUZZ=1` this only replays the seed corpus as a JUnit test.
 */
class MenuGeometryFuzzTest {

    @FuzzTest(maxDuration = "10m")
    fun layoutAndHitTestStayConsistent(data: FuzzedDataProvider) {
        val itemCount = data.consumeInt(-8, 256)
        val columns = data.consumeInt(-2, 12)
        val g = MenuGeometry(
            itemCount = itemCount,
            itemW = data.consumeFloat(),
            itemH = data.consumeFloat(),
            gap = data.consumeFloat(),
            margin = data.consumeFloat(),
            minColumns = columns,
        )
        val visibleItems = itemCount.coerceAtLeast(0)
        val minColumns = columns.coerceAtLeast(1)

        val layouts = data.consumeInt(1, 32)
        repeat(layouts) {
            val expanded = data.consumeBoolean()
            val dockRight = data.consumeBoolean()
            g.layout(data.consumeInt(), data.consumeInt(), expanded, dockRight)

            // The grid widens on a short screen but never below the preferred
            // count, and never past one entry per column.
            assertTrue(g.columns >= minColumns, "fewer than the minimum columns: ${g.columns}")
            assertTrue(g.columns <= maxOf(minColumns, visibleItems), "more columns than entries: ${g.columns}")

            val toggle = g.toggle
            val toggleWidth = toggle.right - toggle.left
            if (toggleWidth.isFinite() && g.panelWidth.isFinite()) {
                // Relative tolerance: left + panelWidth - left loses low bits for huge values.
                val tolerance = 1e-3f * (1f + abs(g.panelWidth)) + 1e-3f * abs(toggle.left)
                assertEquals(g.panelWidth, toggleWidth, tolerance, "toggle width != panel width")
            }

            if (expanded) {
                for (i in 0 until visibleItems) g.item(i) // must be addressable, never throw
            }

            repeat(data.consumeInt(1, 8)) {
                val x = data.consumeFloat()
                val y = data.consumeFloat()
                val hit = g.hitTest(x, y, expanded)
                when {
                    hit == null -> {
                        assertFalse(toggle.contains(x, y), "null hit but the toggle contains ($x,$y)")
                        if (expanded) {
                            for (i in 0 until visibleItems) {
                                assertFalse(g.item(i).contains(x, y), "null hit but item $i contains ($x,$y)")
                            }
                        }
                    }

                    hit == MenuGeometry.TOGGLE_INDEX ->
                        assertTrue(toggle.contains(x, y), "toggle reported but does not contain ($x,$y)")

                    else -> {
                        assertTrue(expanded, "item $hit reported while collapsed")
                        assertTrue(hit in 0 until visibleItems, "item index $hit out of range")
                        assertTrue(g.item(hit).contains(x, y), "item $hit reported but does not contain ($x,$y)")
                    }
                }
            }
        }
    }
}
