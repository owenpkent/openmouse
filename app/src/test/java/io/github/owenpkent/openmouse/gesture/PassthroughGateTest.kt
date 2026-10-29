package io.github.owenpkent.openmouse.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for the legacy passthrough bookkeeping. The watchdog in the
 * service is a Handler callback scheduled for [PassthroughGate.deadlineMs];
 * here that is simulated by calling [PassthroughGate.expire] at that time.
 */
class PassthroughGateTest {

    private fun gate() = PassthroughGate(maxGestureMs = 1500L)

    @Nested
    inner class HealthyGestures {

        @Test
        fun firstGestureOpensThePassthroughAndSetsTheDeadline() {
            val g = gate()
            assertTrue(g.begin(0L))
            assertEquals(1, g.inFlight)
            assertEquals(1500L, g.deadlineMs)
        }

        @Test
        fun lastCompletionClosesThePassthroughAndClearsTheDeadline() {
            val g = gate()
            g.begin(0L)
            assertTrue(g.end())
            assertEquals(0, g.inFlight)
            assertNull(g.deadlineMs)
        }

        @Test
        fun overlappingGesturesCannotCloseThePassthroughEarly() {
            val g = gate()
            assertTrue(g.begin(0L))
            assertFalse(g.begin(100L))
            assertFalse(g.end())
            assertTrue(g.end())
        }

        @Test
        fun repeatedScrollsWithNoLossNeverReachTheDeadline() {
            val g = gate()
            for (t in 0L until 10_000L step 400L) {
                assertTrue(g.begin(t))
                assertFalse(g.expire(t + 250L)) // a stale callback mid-gesture is ignored
                assertTrue(g.end())
                assertNull(g.deadlineMs)
            }
        }
    }

    @Nested
    inner class LostCompletions {

        @Test
        fun aSingleLostCompletionRecoversAtTheDeadline() {
            val g = gate()
            g.begin(0L)
            assertFalse(g.expire(1499L))
            assertTrue(g.expire(1500L))
            assertEquals(0, g.inFlight)
            assertNull(g.deadlineMs)
        }

        @Test
        fun repeatsAfterALostCompletionDoNotPostponeTheDeadline() {
            // One lost callback, then healthy scroll repeats every dwell period.
            // Each repeat used to re-arm the watchdog, so the overlay stayed
            // click-through for as long as the pointer rested.
            val g = gate()
            assertTrue(g.begin(0L)) // this completion is lost

            assertFalse(g.begin(1000L), "a repeat must not re-open the gate")
            assertEquals(1500L, g.deadlineMs, "a repeat must not move the deadline")
            assertFalse(g.end()) // the repeat completes; the lost one still counts
            assertEquals(1, g.inFlight)

            assertTrue(g.expire(1500L), "the watchdog must restore at the first deadline")
            assertEquals(0, g.inFlight)

            // From here every repeat is a fresh, healthy cycle.
            for (t in 2000L until 10_000L step 1000L) {
                assertTrue(g.begin(t))
                assertEquals(t + 1500L, g.deadlineMs)
                assertTrue(g.end())
            }
            assertEquals(0, g.inFlight)
        }

        @Test
        fun aLateCompletionAfterExpiryIsHarmless() {
            val g = gate()
            g.begin(0L)
            g.expire(1500L)
            assertTrue(g.end()) // restores again; the count never goes negative
            assertEquals(0, g.inFlight)
        }

        @Test
        fun aCompletionOnAnEmptyGateReportsClosed() {
            // end() answers "is anything still in flight?", not "did this call
            // empty the gate?": a fresh gate, or one just reset, is closed too.
            val g = gate()
            assertTrue(g.end())
            assertEquals(0, g.inFlight)
            assertNull(g.deadlineMs)

            g.begin(0L)
            g.reset()
            assertTrue(g.end())
            assertEquals(0, g.inFlight)
        }

        @Test
        fun expireWithNothingInFlightIsANoOp() {
            val g = gate()
            assertFalse(g.expire(5000L))
            assertEquals(0, g.inFlight)
        }
    }

    @Nested
    inner class DegenerateInputs {

        @Test
        fun zeroOrNegativeMaxGestureReadsAsOneMillisecond() {
            val g = PassthroughGate(maxGestureMs = -5L)
            g.begin(10L)
            assertEquals(11L, g.deadlineMs)
        }

        @Test
        fun aDeadlineThatWouldOverflowSaturates() {
            val g = PassthroughGate(maxGestureMs = Long.MAX_VALUE)
            g.begin(Long.MAX_VALUE - 1L)
            assertEquals(Long.MAX_VALUE, g.deadlineMs)
            assertTrue(g.expire(Long.MAX_VALUE))
        }

        @Test
        fun resetForgetsEverything() {
            val g = gate()
            g.begin(0L)
            g.begin(1L)
            g.reset()
            assertEquals(0, g.inFlight)
            assertNull(g.deadlineMs)
            assertTrue(g.begin(2L))
        }
    }
}
