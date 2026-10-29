package io.github.owenpkent.openmouse.click

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure dwell state machine. Time is supplied explicitly, so
 * these run on the JVM with no Android dependencies and no real clock.
 *
 * The groups mirror the PowerToys Dwell Click engine suite (EngineTests.cpp) so
 * the two implementations can be compared rule for rule. PowerToys cases that
 * cover features OpenMouse does not have (a separate post-action tolerance,
 * selectable actions with revert, press-and-release drag, pause, external button
 * holds, auto-repeating scroll, injection failure) have no counterpart here.
 */
class DwellMachineTest {

    // Test baseline: a 100 ms dwell so the timing arithmetic stays obvious, with a
    // 10 px tolerance. The shipping defaults are 1000 ms and 14 px.
    private fun machine() = DwellMachine(dwellTimeMs = 100L, moveThresholdPx = 10f)

    // The machine starts locked with its anchor at the origin, so every test
    // first moves the pointer far enough to arm it, leaving the countdown
    // started at [nowMs].
    private fun arm(m: DwellMachine, x: Float = 100f, y: Float = 100f, nowMs: Long = 0L) {
        m.reset(nowMs)
        m.onMove(x, y, nowMs)
    }

    @Nested
    inner class DwellCountdown {

        @Test
        fun startsLockedAndDoesNotFireWithoutMovement() {
            val m = machine()
            m.reset(0L)
            assertFalse(m.poll(0L).clicked)
            // However long the caller polls for, no movement means no click.
            val late = m.poll(1_000_000L)
            assertFalse(late.clicked)
            assertEquals(0f, late.progress, EPS)
        }

        @Test
        fun movementWithinToleranceDoesNotArm() {
            val m = machine() // 10 px tolerance, anchor at the origin
            m.reset(0L)
            m.onMove(10f, 0f, 0L) // exactly 10 px: still resting, so still locked
            assertFalse(m.poll(1000L).clicked)

            m.onMove(11f, 0f, 1000L) // 11 px clears the tolerance and arms the machine
            assertTrue(m.poll(1100L).clicked)
        }

        @Test
        fun firesWhenDwellCompletes() {
            val m = machine()
            arm(m)
            val half = m.poll(50L)
            assertFalse(half.clicked)
            assertEquals(0.5f, half.progress, EPS)

            val done = m.poll(100L) // exactly at the threshold
            assertTrue(done.clicked)
            assertEquals(100f, done.x, EPS)
            assertEquals(100f, done.y, EPS)
        }

        @Test
        fun firingPollReportsZeroProgressSoTheRingClears() {
            val m = machine()
            arm(m)
            val done = m.poll(100L)
            assertTrue(done.clicked)
            // The countdown is over, so the ring must clear rather than sit full.
            assertEquals(0f, done.progress, EPS)
        }

        @Test
        fun locksAfterFiringUntilThePointerMoves() {
            val m = machine()
            arm(m)
            assertTrue(m.poll(100L).clicked)
            // A parked pointer must not re-fire, however long it rests. This is
            // the core Midas touch guard.
            assertFalse(m.poll(120L).clicked)
            assertFalse(m.poll(300L).clicked)
            assertFalse(m.poll(100_000L).clicked)
            assertEquals(0f, m.poll(100_000L).progress, EPS)
        }

        @Test
        fun driftWithinToleranceDoesNotRestartTheCountdown() {
            val m = machine()
            arm(m)
            m.onMove(105f, 103f, 50L) // ~5.8 px of drift, under the 10 px tolerance

            val done = m.poll(100L)
            assertTrue(done.clicked) // countdown survived the tremor
            // The click lands where the pointer actually is, not at the stale anchor.
            assertEquals(105f, done.x, EPS)
            assertEquals(103f, done.y, EPS)
        }

        @Test
        fun movementBeyondToleranceRestartsTheCountdown() {
            val m = machine()
            arm(m)
            m.onMove(200f, 200f, 50L) // re-anchors and restarts from t=50
            assertFalse(m.poll(100L).clicked) // only 50 ms into the new countdown
            assertTrue(m.poll(150L).clicked)
        }

        @Test
        fun progressGrowsMonotonicallyWhileResting() {
            val m = machine()
            arm(m)
            var last = -1f
            for (t in 0L until 100L step 10L) {
                val p = m.poll(t).progress
                assertTrue(p >= last, "progress fell from $last to $p at t=$t")
                last = p
            }
        }
    }

    @Nested
    inner class DegenerateInputs {

        @Test
        fun zeroDwellTimeDoesNotDivideByZero() {
            val m = machine()
            arm(m)
            m.configure(dwellTimeMs = 0L, moveThresholdPx = 10f)

            val immediate = m.poll(0L)
            assertFalse(immediate.clicked)
            assertFalse(immediate.progress.isNaN())
            assertEquals(0f, immediate.progress, EPS)
            assertTrue(m.poll(1L).clicked) // divisor clamped to 1 ms
        }

        @Test
        fun negativeSettingsAreClampedRatherThanUndefined() {
            val m = DwellMachine(dwellTimeMs = -500L, moveThresholdPx = -5f)
            m.reset(0L)
            // A negative tolerance clamps to 0, so any movement at all arms the machine.
            m.onMove(1f, 0f, 0L)
            assertTrue(m.poll(1L).clicked)
        }

        @Test
        fun nanThresholdIsTreatedAsZero() {
            val m = DwellMachine(dwellTimeMs = 100L, moveThresholdPx = Float.NaN)
            m.reset(0L)
            m.onMove(1f, 0f, 0L)
            assertTrue(m.poll(100L).clicked)
        }

        @Test
        fun aTimestampOlderThanTheAnchorDoesNotFire() {
            val m = machine()
            arm(m, nowMs = 1000L)
            // Negative elapsed time must read as zero, never as an instant click.
            val r = m.poll(500L)
            assertFalse(r.clicked)
            assertEquals(0f, r.progress, EPS)
            // And the countdown still completes once time catches up.
            assertTrue(m.poll(1100L).clicked)
        }

        @Test
        fun aHugeTimeGapSaturatesInsteadOfWrapping() {
            // Found by the fuzz target: with the anchor near Long.MIN_VALUE the
            // subtraction overflowed negative and progress snapped back to 0.
            val m = machine()
            arm(m, nowMs = Long.MIN_VALUE + 10L)
            val r = m.poll(Long.MAX_VALUE)
            assertTrue(r.clicked)
            assertEquals(0f, r.progress, EPS)
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
    }

    @Nested
    inner class LocksAndSuppression {

        @Test
        fun aPhysicalClickSuppressesTheNextDwell() {
            val m = machine()
            arm(m)
            m.poll(50L) // countdown half spent

            // The user clicked for themselves; do not stack a dwell click on top.
            m.lockUntilMove()
            assertFalse(m.poll(100L).clicked)
            assertFalse(m.poll(5000L).clicked)

            m.onMove(140f, 140f, 5000L) // ~56 px from the lock anchor: unlocks
            assertTrue(m.poll(5100L).clicked)
        }

        @Test
        fun driftWithinToleranceDoesNotClearALock() {
            val m = machine()
            arm(m)
            m.lockUntilMove()
            m.onMove(105f, 100f, 50L) // 5 px: still parked at the lock point
            assertFalse(m.poll(1000L).clicked)
        }

        @Test
        fun lockUntilMoveProtectsAgainstLiveSettingsChanges() {
            // Mirrors what DwellClicker.configure does: change timing, then lock so
            // a shorter dwell time cannot fire against already-elapsed rest.
            val m = DwellMachine(dwellTimeMs = 1000L, moveThresholdPx = 10f)
            arm(m)
            m.poll(900L) // 900 ms of rest already banked

            m.configure(dwellTimeMs = 100L, moveThresholdPx = 10f)
            m.lockUntilMove()
            assertFalse(m.poll(901L).clicked)

            m.onMove(300f, 300f, 1000L) // move re-arms with the new 100 ms time
            assertFalse(m.poll(1050L).clicked)
            assertTrue(m.poll(1100L).clicked)
        }

        @Test
        fun resetLocksSoARestartCannotFireImmediately() {
            val m = machine()
            arm(m)
            m.poll(50L)

            // A stop/start cycle (dwell toggled off and on) must not fire the
            // instant the machine comes back.
            m.reset(50L)
            assertFalse(m.poll(1000L).clicked)
            assertEquals(0f, m.poll(1000L).progress, EPS)

            m.onMove(300f, 300f, 1000L)
            assertTrue(m.poll(1100L).clicked)
        }

        @Test
        fun resetAnchorsAtTheCurrentPointSoDriftStaysLocked() {
            val m = machine()
            arm(m)
            m.onMove(105f, 100f, 50L) // drift, so current != anchor
            m.reset(50L)
            m.onMove(110f, 100f, 60L) // 5 px from where reset saw the pointer
            assertFalse(m.poll(1000L).clicked)
        }
    }

    companion object {
        private const val EPS = 0.001f
    }
}
