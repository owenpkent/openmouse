package io.github.owenpkent.openmouse.menu

import io.github.owenpkent.openmouse.click.DwellMachine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The production dock geometry and the dwell machine driven together through
 * the service's own sequence (dwell on the toggle, expand, re-feed the entry
 * under the still pointer, lock), with the shipping defaults at density 1
 * (8 px movement tolerance, 20 px re-arm distance, 1 s dwell).
 *
 * The toggle keeps its place across collapse and expand, so the pointer that
 * opened the dock is still on the toggle afterwards. Layout-only tests cannot
 * see what happens next: resting there must not close the dock again, a tremor
 * across the 4 dp gap into the first cell must not select it, and a deliberate
 * move into a cell must. Each case runs on a portrait phone (one column) and in
 * landscape (three columns), on both dock sides.
 */
class DockDwellIntegrationTest {

    private class Dock(val dockRight: Boolean, val width: Int, val height: Int) {
        val geometry = MenuGeometry(
            itemCount = 13,
            itemW = 48f,
            itemH = 48f,
            gap = 4f,
            margin = 0f,
            minColumns = 1,
        )
        val machine = DwellMachine(dwellTimeMs = DWELL_MS, moveThresholdPx = 8f, postActionThresholdPx = 20f)
        var expanded = false
        var now = 0L

        init {
            geometry.layout(width, height, expanded, dockRight)
            machine.reset(now)
        }

        /** The middle of the collapsed toggle cell, flush with the dock edge. */
        val x: Float get() = if (dockRight) width - 24f else 24f

        fun hit(x: Float, y: Float): Int? = geometry.hitTest(x, y, expanded)

        /** The service's onCursorMoved: position plus the entry under it as the rest key. */
        fun move(x: Float, y: Float) {
            machine.onMove(x, y, now, hit(x, y))
        }

        /** Rest for one dwell period; what the machine fired, or null. */
        fun dwell(x: Float, y: Float): Int? {
            var fired: Int? = null
            var t = 0L
            while (t <= DWELL_MS) {
                t += POLL_MS
                now += POLL_MS
                if (machine.poll(now).clicked) {
                    fired = hit(x, y)
                    break
                }
            }
            return fired
        }

        /** The service's handlePrimaryAction for a menu hit: act, then resyncRestKey. */
        fun menuAction(index: Int, x: Float, y: Float) {
            if (index == MenuGeometry.TOGGLE_INDEX) {
                expanded = !expanded
                geometry.layout(width, height, expanded, dockRight)
            }
            machine.onMove(x, y, now, hit(x, y))
            machine.lockUntilMove()
        }

        /** Dwell on the toggle at ([x], [y]) until it flips, exactly as the service would. */
        fun dwellOnToggle(x: Float, y: Float) {
            assertEquals(MenuGeometry.TOGGLE_INDEX, hit(x, y), "not on the toggle")
            move(x, y)
            assertEquals(MenuGeometry.TOGGLE_INDEX, dwell(x, y), "the toggle did not fire")
            menuAction(MenuGeometry.TOGGLE_INDEX, x, y)
        }
    }

    @ParameterizedTest(name = "dockRight={0}, {1} x {2}")
    @CsvSource(textBlock = SCREENS)
    fun openingAndClosingLeaveTheToggleUnderTheStillPointer(dockRight: Boolean, width: Int, height: Int) {
        val dock = Dock(dockRight, width, height)
        val y = dock.geometry.toggle.top + 24f
        dock.dwellOnToggle(dock.x, y)
        assertTrue(dock.expanded)
        assertEquals(MenuGeometry.TOGGLE_INDEX, dock.hit(dock.x, y), "opening moved the toggle")
        assertNull(dock.dwell(dock.x, y), "resting on the toggle closed the dock it just opened")

        // Leave for the app side and come back: now the toggle closes it, in place.
        val away = if (dockRight) dock.x - 200f else dock.x + 200f
        dock.move(away, y)
        dock.move(dock.x, y)
        dock.dwellOnToggle(dock.x, y)
        assertFalse(dock.expanded)
        assertEquals(MenuGeometry.TOGGLE_INDEX, dock.hit(dock.x, y), "closing moved the toggle")
        assertNull(dock.dwell(dock.x, y), "resting on the toggle reopened the dock it just closed")
    }

    @ParameterizedTest(name = "dockRight={0}, {1} x {2}")
    @CsvSource(textBlock = SCREENS)
    fun aTremorFromTheToggleIntoTheFirstCellDoesNotSelectIt(dockRight: Boolean, width: Int, height: Int) {
        val dock = Dock(dockRight, width, height)
        val openY = dock.geometry.toggle.bottom - 1f
        dock.dwellOnToggle(dock.x, openY)
        val tremorY = openY + 6f // across the 4 px gap, 1 px into the cell, inside the 8 px tolerance
        val cell = dock.hit(dock.x, tremorY)
        assertTrue(cell != null && cell >= 0, "expected a cell at the tremor's landing point")

        dock.move(dock.x, tremorY)
        assertNull(dock.dwell(dock.x, tremorY), "a 6 px tremor selected cell $cell")
        for (dy in listOf(0f, 6f, 3f, 7f, -7f, 6f)) {
            dock.move(dock.x, openY + dy)
            assertNull(dock.dwell(dock.x, openY + dy), "a tremor of $dy px selected something")
        }
        assertTrue(dock.expanded, "a tremor closed the dock")
    }

    @ParameterizedTest(name = "dockRight={0}, {1} x {2}")
    @CsvSource(textBlock = SCREENS)
    fun aDeliberateMoveIntoACellAfterOpeningStillSelectsIt(dockRight: Boolean, width: Int, height: Int) {
        val dock = Dock(dockRight, width, height)
        val openY = dock.geometry.toggle.top + 24f
        dock.dwellOnToggle(dock.x, openY)

        // Into the centre of the cell below the toggle: 52 px, past every tolerance.
        val targetY = openY + 52f
        val target = dock.hit(dock.x, targetY)
        assertTrue(target != null && target >= 0, "expected a cell at the move target")
        dock.move(dock.x, targetY)
        assertEquals(target, dock.dwell(dock.x, targetY), "a deliberate move into a cell did not select it")
    }

    private companion object {
        // A 412 x 915 dp phone in portrait (one column of 13 under the toggle,
        // raised to fit) and an 800 x 400 dp landscape screen (three columns).
        const val SCREENS = "true, 412, 915\nfalse, 412, 915\ntrue, 800, 400\nfalse, 800, 400"
        const val DWELL_MS = 1000L
        const val POLL_MS = 50L
    }
}
