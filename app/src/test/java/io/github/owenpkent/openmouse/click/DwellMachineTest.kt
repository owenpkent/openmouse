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

    companion object {
        private const val EPS = 0.001f
    }
}
