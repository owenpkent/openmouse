package io.github.owenpkent.openmouse.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import io.github.owenpkent.openmouse.gesture.PassthroughGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Jazzer fuzz target for the legacy passthrough bookkeeping. The fuzzer picks
 * the gesture deadline and a sequence of begin / end / expire / reset events
 * with arbitrary (including backwards) timestamps. Invariants after every event:
 *
 * - the in-flight count is never negative;
 * - a deadline exists exactly while something is in flight;
 * - the deadline never moves while the count stays above zero, so repeats
 *   cannot postpone recovery from a lost completion;
 * - begin opens the gate exactly when the count was zero, end closes it
 *   exactly when the count is zero afterwards (an empty gate included);
 * - expire at or past the deadline always empties the gate.
 *
 * Without `JAZZER_FUZZ=1` this only replays the seed corpus as a JUnit test.
 */
class PassthroughGateFuzzTest {

    @FuzzTest(maxDuration = "10m")
    fun arbitraryEventSequenceKeepsInvariants(data: FuzzedDataProvider) {
        val gate = PassthroughGate(maxGestureMs = data.consumeLong())
        var deadlineWhileBusy: Long? = null

        while (data.remainingBytes() > 0) {
            val before = gate.inFlight
            when (data.consumeInt(0, 3)) {
                0 -> {
                    val opened = gate.begin(data.consumeLong())
                    assertEquals(before == 0 && gate.inFlight == 1, opened, "begin must open iff count was 0")
                }

                1 -> {
                    // end() reports "nothing in flight" rather than a transition,
                    // so a completion on an empty gate (after expiry or reset)
                    // also returns true; the service restoring an already
                    // touchable overlay is harmless.
                    val closed = gate.end()
                    assertEquals(gate.inFlight == 0, closed, "end must close iff count is 0")
                }

                2 -> {
                    val now = data.consumeLong()
                    val deadline = gate.deadlineMs
                    val expired = gate.expire(now)
                    val due = deadline != null && now >= deadline
                    assertEquals(due, expired, "expire must fire iff a deadline is due")
                }

                else -> gate.reset()
            }

            assertTrue(gate.inFlight >= 0, "in-flight count went negative")
            val deadline = gate.deadlineMs
            if (gate.inFlight == 0) {
                assertNull(deadline, "deadline without anything in flight")
                deadlineWhileBusy = null
            } else {
                assertNotNull(deadline, "in flight without a deadline")
                if (deadlineWhileBusy != null) {
                    assertEquals(deadlineWhileBusy, deadline, "deadline moved while gestures were in flight")
                }
                deadlineWhileBusy = deadline
            }
        }
    }
}
