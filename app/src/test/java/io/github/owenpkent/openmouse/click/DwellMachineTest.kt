package io.github.owenpkent.openmouse.click

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure dwell state machine. Time is supplied explicitly, so
 * these run on the JVM with no Android dependencies and no real clock.
 */
class DwellMachineTest {

    private fun machine() = DwellMachine(dwellTimeMs = 100L, moveThresholdPx = 10f)

    @Test
    fun startsLocked_doesNotClickWithoutMovement() {
        val m = machine()
        m.reset(0L)
        assertFalse(m.poll(0L).clicked)
        // Even long after, with no movement, it stays locked.
        assertFalse(m.poll(10_000L).clicked)
        assertEquals(0f, m.poll(10_000L).progress, EPS)
    }

    @Test
    fun clicksWhenDwellCompletes() {
        val m = machine()
        m.reset(0L)
        m.onMove(100f, 100f, 0L) // far from (0,0): unlocks and anchors here
        assertEquals(0.5f, m.poll(50L).progress, EPS)

        val click = m.poll(100L)
        assertTrue(click.clicked)
        assertEquals(100f, click.x, EPS)
        assertEquals(100f, click.y, EPS)
    }

    @Test
    fun locksAfterClickUntilNextMove() {
        val m = machine()
        m.reset(0L)
        m.onMove(100f, 100f, 0L)
        assertTrue(m.poll(100L).clicked)
        // The same rest must not produce a second click.
        assertFalse(m.poll(120L).clicked)
        assertFalse(m.poll(300L).clicked)
    }

    @Test
    fun smallMovesDoNotResetCountdown() {
        val m = machine()
        m.reset(0L)
        m.onMove(100f, 100f, 0L)
        m.onMove(105f, 103f, 50L) // ~5.8 px, under the 10 px threshold: no reset

        val click = m.poll(100L)
        assertTrue(click.clicked)
        assertEquals(105f, click.x, EPS) // position still tracks the small move
    }

    @Test
    fun largeMoveRestartsCountdown() {
        val m = machine()
        m.reset(0L)
        m.onMove(100f, 100f, 0L)
        m.onMove(200f, 200f, 50L) // far: re-anchor and restart the dwell at t=50
        assertFalse(m.poll(100L).clicked) // only 50 ms into the new dwell
        assertTrue(m.poll(150L).clicked)
    }

    @Test
    fun lockUntilMovePreventsClickUntilMoved() {
        val m = machine()
        m.reset(0L)
        m.onMove(100f, 100f, 0L)
        m.lockUntilMove()
        assertFalse(m.poll(200L).clicked)

        m.onMove(140f, 140f, 200L) // ~56 px from the lock anchor: unlocks
        assertTrue(m.poll(300L).clicked)
    }

    @Test
    fun configureThenLockPreventsInstantClick() {
        // Mirrors what DwellClicker.configure does: change timing, then lock so a
        // shorter dwell time cannot fire a click against already-elapsed rest.
        val m = machine()
        m.reset(0L)
        m.onMove(100f, 100f, 0L) // unlocked, resting since t=0

        m.configure(dwellTimeMs = 50L, moveThresholdPx = 10f)
        m.lockUntilMove()
        assertFalse(m.poll(1000L).clicked) // locked despite elapsed >> new dwell

        m.onMove(140f, 140f, 1000L) // move re-arms with the new 50 ms time
        assertTrue(m.poll(1050L).clicked)
    }

    @Test
    fun zeroDwellTimeDoesNotProduceNaN() {
        val m = machine()
        m.reset(0L)
        m.onMove(100f, 100f, 0L)
        m.configure(dwellTimeMs = 0L, moveThresholdPx = 10f)

        val p = m.poll(0L)
        assertFalse(p.progress.isNaN())
        assertEquals(0f, p.progress, EPS)
        assertTrue(m.poll(5L).clicked) // guarded divisor (>=1) still completes
    }

    @Test
    fun exactThresholdDoesNotUnlock() {
        val m = machine() // threshold 10
        m.reset(0L)
        m.onMove(10f, 0f, 0L) // distance exactly 10 from the (0,0) anchor: strict > fails
        assertFalse(m.poll(1000L).clicked)

        m.onMove(11f, 0f, 50L) // 11 > 10 unlocks
        assertTrue(m.poll(150L).clicked)
    }

    companion object {
        private const val EPS = 0.001f
    }
}
