package io.github.owenpkent.openmouse.menu

import io.github.owenpkent.openmouse.click.DwellMachine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * The production dock geometry and the dwell machine driven together through
 * the service's own sequence (dwell on the handle, expand, re-feed the entry
 * under the still pointer, lock), with the shipping defaults at density 1
 * (8 px movement tolerance, 20 px re-arm distance, 1 s dwell).
 *
 * The collapsed handle (22 x 96 dp) is twice a cell tall, so the pointer can
 * open the dock from a height that, once the strip has re-laid out, falls in
 * the 4 dp gap between two cells. Layout-only tests cannot see what happens
 * next: a 1 px tremor into either neighbour must not select it, on either dock
 * side, while a deliberate move into a cell must.
 */
class DockDwellIntegrationTest {

    private class Dock(val dockRight: Boolean) {
        val geometry = MenuGeometry(
            itemCount = 13,
            itemW = 48f,
            itemH = 48f,
            gap = 4f,
            margin = 0f,
            minColumns = 2,
            handleW = 22f,
            handleH = 96f,
        )
        val machine = DwellMachine(dwellTimeMs = DWELL_MS, moveThresholdPx = 8f, postActionThresholdPx = 20f)
        var expanded = false
        var now = 0L

        init {
            geometry.layout(WIDTH, HEIGHT, expanded, dockRight)
            machine.reset(now)
        }

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
                geometry.layout(WIDTH, HEIGHT, expanded, dockRight)
            }
            machine.onMove(x, y, now, hit(x, y))
            machine.lockUntilMove()
        }

        /** Dwell on the collapsed handle at ([x], [y]) until it opens, exactly as the service would. */
        fun openFromHandle(x: Float, y: Float) {
            assertEquals(MenuGeometry.TOGGLE_INDEX, hit(x, y), "not on the handle")
            move(x, y)
            assertEquals(MenuGeometry.TOGGLE_INDEX, dwell(x, y), "the handle did not open")
            menuAction(MenuGeometry.TOGGLE_INDEX, x, y)
            assertTrue(expanded)
        }
    }

    // Opening heights that land in a gap once the strip has shifted (rows run
    // 144..192, 196..244, 248..296): 247 and 195 are each 1 px above a cell and
    // 3 px below the one before it. Each pair is the opening height and the
    // tremor's landing height, on both dock sides.
    @ParameterizedTest(name = "dockRight={0}: open at {1}, tremor to {2}")
    @CsvSource(
        "true, 247, 248",
        "false, 247, 248",
        "true, 247, 244",
        "false, 247, 244",
        "true, 195, 196",
        "false, 195, 196",
        "true, 195, 192",
        "false, 195, 192",
    )
    fun aTremorFromTheGapIntoANeighbouringCellDoesNotSelectIt(dockRight: Boolean, openY: Float, tremorY: Float) {
        val dock = Dock(dockRight)
        val x = dock.handleX()
        dock.openFromHandle(x, openY)
        assertNull(dock.hit(x, openY), "the opening point should now be a gap")
        val neighbour = dock.hit(x, tremorY)
        assertTrue(neighbour != null && neighbour >= 0, "expected a cell at the tremor's landing point")

        dock.move(x, tremorY)
        assertNull(dock.dwell(x, tremorY), "a ${tremorY - openY} px tremor selected cell $neighbour")
        dock.move(x, openY)
        dock.move(x, tremorY)
        assertNull(dock.dwell(x, tremorY), "shaking across the cell edge selected cell $neighbour")
    }

    @ParameterizedTest(name = "dockRight={0}")
    @ValueSource(booleans = [true, false])
    fun aTremorWithinTheToleranceNeverSelectsWhateverItCrosses(dockRight: Boolean) {
        val dock = Dock(dockRight)
        val x = dock.handleX()
        dock.openFromHandle(x, 247f)
        // 7 px either way stays within the 8 px tolerance of the lock anchor,
        // crossing the gap and both neighbouring cells repeatedly.
        for (dy in listOf(1f, -7f, 7f, 0f, 3f, -3f, 7f)) {
            dock.move(x, 247f + dy)
            assertNull(dock.dwell(x, 247f + dy), "a tremor of $dy px selected a cell")
        }
    }

    @ParameterizedTest(name = "dockRight={0}")
    @ValueSource(booleans = [true, false])
    fun aDeliberateMoveIntoACellAfterOpeningStillSelectsIt(dockRight: Boolean) {
        val dock = Dock(dockRight)
        val x = dock.handleX()
        dock.openFromHandle(x, 247f)

        // Into the centre of the cell below the gap: 25 px, past the tolerance.
        val target = dock.hit(x, 272f)
        assertTrue(target != null && target >= 0, "expected a cell at the move target")
        dock.move(x, 272f)
        assertEquals(target, dock.dwell(x, 272f), "a deliberate move into a cell did not select it")
    }

    @ParameterizedTest(name = "dockRight={0}")
    @ValueSource(booleans = [true, false])
    fun openingOverACellStillNeedsTheTolerance(dockRight: Boolean) {
        // The companion case: the opening point lands inside a cell, which the
        // resync feeds and locks on. A tremor into the next cell must not select
        // it either, since the pointer never travelled anywhere.
        val dock = Dock(dockRight)
        val x = dock.handleX()
        dock.openFromHandle(x, 243f)
        val under = dock.hit(x, 243f)
        assertTrue(under != null && under >= 0, "the opening point should now be a cell")
        assertFalse(dock.hit(x, 249f) == under, "expected a different cell 6 px below")

        dock.move(x, 249f)
        assertNull(dock.dwell(x, 249f), "a 6 px tremor into the next cell selected it")
        dock.move(x, 243f)
        assertNull(dock.dwell(x, 243f), "the cell the dock opened under was selected without the pointer leaving")
    }

    private fun Dock.handleX(): Float = if (dockRight) WIDTH - 11f else 11f

    private companion object {
        // 800 x 400 dp landscape: three columns, five rows, strip shifted up to
        // fit, so the handle's span (152..248) covers two cell rows and two gaps.
        const val WIDTH = 800
        const val HEIGHT = 400
        const val DWELL_MS = 1000L
        const val POLL_MS = 50L
    }
}
