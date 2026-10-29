package io.github.owenpkent.openmouse.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import io.github.owenpkent.openmouse.click.DwellMachine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Jazzer fuzz target for the dwell state machine, the OpenMouse counterpart of
 * the PowerToys Dwell Click libFuzzer target (DwellClick.FuzzingTest.cpp).
 *
 * The fuzzer's bytes are decoded into a sequence of machine events (moves,
 * polls, locks, resets, live settings changes) with adversarial coordinates,
 * timestamps (including ones that run backwards), and settings (zero, negative,
 * NaN, infinite). The JVM rules out memory corruption, so the value here is the
 * invariants checked after every poll:
 *
 * - progress is finite and within 0..1;
 * - a firing poll reports zero progress (the ring clears);
 * - a click lands at the last reported pointer position;
 * - nothing fires before the pointer has moved since the last lock, reset,
 *   settings change, or click (the Midas touch guard);
 * - the same rest never fires twice;
 * - progress never decreases while the pointer rests and time moves forward.
 *
 * Without `JAZZER_FUZZ=1` in the environment this only replays the seed corpus
 * (and an empty input) as an ordinary JUnit test. The nightly workflow runs it
 * in fuzzing mode.
 */
class DwellMachineFuzzTest {

    @FuzzTest(maxDuration = "10m")
    fun arbitraryEventSequenceKeepsInvariants(data: FuzzedDataProvider) {
        val machine = DwellMachine(
            dwellTimeMs = data.consumeLong(),
            moveThresholdPx = data.consumeFloat(),
        )
        var now = 0L
        machine.reset(now)

        var movedSinceLock = false // any onMove since the last lock-like event
        var lastX = 0f
        var lastY = 0f
        var clickedThisRest = false // a click happened with no onMove since
        var lastProgress = 0f
        var lastPollNow = Long.MIN_VALUE
        var haveLastPoll = false

        // Bound the number of ops so any single input terminates regardless of size.
        var steps = 0
        while (data.remainingBytes() > 0 && steps++ < 4096) {
            val op = data.consumeByte().toInt()

            // Mostly monotonic time with fuzzer-chosen jumps; bit 3 makes the clock
            // jump to an arbitrary value, including backwards, to drive the
            // "timestamp older than the anchor" guard.
            now = if (op and 0x08 != 0) data.consumeLong() else now + data.consumeLong(0L, 1L shl 40)

            when (op and 0x07) {
                0, 1 -> {
                    lastX = data.consumeFloat()
                    lastY = data.consumeFloat()
                    machine.onMove(lastX, lastY, now)
                    movedSinceLock = true
                    clickedThisRest = false
                    haveLastPoll = false
                }

                2 -> {
                    machine.lockUntilMove()
                    movedSinceLock = false
                    haveLastPoll = false
                }

                3 -> {
                    machine.reset(now)
                    movedSinceLock = false
                    haveLastPoll = false
                }

                4 -> {
                    // Mirrors DwellClicker.configure: new values, then lock.
                    machine.configure(data.consumeLong(), data.consumeFloat())
                    machine.lockUntilMove()
                    movedSinceLock = false
                    haveLastPoll = false
                }

                else -> {
                    val poll = machine.poll(now)

                    assertTrue(poll.progress.isFinite(), "progress not finite: $poll")
                    assertTrue(poll.progress in 0f..1f, "progress out of range: $poll")
                    assertEquals(0, java.lang.Float.compare(lastX, poll.x), "poll x drifted from last move")
                    assertEquals(0, java.lang.Float.compare(lastY, poll.y), "poll y drifted from last move")

                    if (poll.clicked) {
                        assertEquals(0f, poll.progress, "firing poll must report zero progress")
                        assertTrue(movedSinceLock, "clicked without any movement since the last lock")
                        assertFalse(clickedThisRest, "the same rest fired twice")
                        clickedThisRest = true
                        haveLastPoll = false
                    } else {
                        if (clickedThisRest) {
                            assertEquals(0f, poll.progress, "progress reported after a click with no movement")
                        }
                        if (haveLastPoll && now >= lastPollNow) {
                            assertTrue(
                                poll.progress >= lastProgress,
                                "progress fell from $lastProgress to ${poll.progress} with time moving forward",
                            )
                        }
                        lastProgress = poll.progress
                        lastPollNow = now
                        haveLastPoll = true
                    }
                }
            }
        }
    }
}
