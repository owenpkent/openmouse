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
 * The fuzzer's bytes are decoded into a sequence of machine events (moves with
 * and without rest keys, polls, locks, resets, pause toggles, in-place re-arms,
 * live settings changes) with adversarial coordinates, timestamps (including
 * ones that run backwards), and settings (zero, negative, NaN, infinite). The
 * JVM rules out memory corruption, so the value here is the invariants checked
 * after every poll:
 *
 * - progress is finite and within 0..1;
 * - a firing poll reports zero progress (the ring clears);
 * - a click lands at the last reported pointer position;
 * - nothing fires before the pointer has moved (or been re-armed in place)
 *   since the last lock, reset, settings change, pause toggle, or click;
 * - the same rest never fires twice without a move or an in-place re-arm;
 * - while paused, a rest over the app (null key) never fires;
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
            postActionThresholdPx = data.consumeFloat(),
        )
        var now = 0L
        machine.reset(now)

        var armedSinceLock = false // an onMove or rearmInPlace since the last lock-like event
        var lastX = 0f
        var lastY = 0f
        var lastKey: Any? = null
        var paused = false
        var clickedThisRest = false // a click happened with no onMove/rearm since
        var lastProgress = 0f
        var lastPollNow = Long.MIN_VALUE
        var haveLastPoll = false

        // Bound the number of ops so any single input terminates regardless of size.
        var steps = 0
        while (data.remainingBytes() > 0 && steps++ < 4096) {
            val op = data.consumeByte().toInt()

            // Mostly monotonic time with fuzzer-chosen jumps; bit 4 makes the clock
            // jump to an arbitrary value, including backwards, to drive the
            // "timestamp older than the anchor" guard.
            now = if (op and 0x10 != 0) data.consumeLong() else now + data.consumeLong(0L, 1L shl 40)

            when (op and 0x0F) {
                0, 1, 2, 3 -> {
                    lastX = data.consumeFloat()
                    lastY = data.consumeFloat()
                    // A small key space so the same target recurs often: null is
                    // "over the app", the rest stand for menu entries.
                    lastKey = when (data.consumeInt(0, 3)) {
                        0 -> null
                        1 -> "a"
                        2 -> "b"
                        else -> "c"
                    }
                    machine.onMove(lastX, lastY, now, lastKey)
                    armedSinceLock = true
                    clickedThisRest = false
                    haveLastPoll = false
                }

                4 -> {
                    machine.lockUntilMove()
                    armedSinceLock = false
                    haveLastPoll = false
                }

                5 -> {
                    machine.lockAfterAction()
                    armedSinceLock = false
                    haveLastPoll = false
                }

                6 -> {
                    machine.reset(now)
                    armedSinceLock = false
                    haveLastPoll = false
                }

                7 -> {
                    // Mirrors DwellClicker.configure: new values, then lock.
                    machine.configure(data.consumeLong(), data.consumeFloat(), data.consumeFloat())
                    machine.lockUntilMove()
                    armedSinceLock = false
                    haveLastPoll = false
                }

                8 -> {
                    val next = data.consumeBoolean()
                    machine.setPaused(next)
                    assertEquals(next, machine.isPaused)
                    if (next != paused) {
                        // A real toggle locks; a redundant call must not disturb anything.
                        paused = next
                        armedSinceLock = false
                        haveLastPoll = false
                    }
                }

                9 -> {
                    machine.rearmInPlace(now)
                    armedSinceLock = true
                    clickedThisRest = false
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
                        assertTrue(armedSinceLock, "clicked without any movement or re-arm since the last lock")
                        assertFalse(clickedThisRest, "the same rest fired twice")
                        assertFalse(paused && lastKey == null, "fired over the app while paused")
                        clickedThisRest = true
                        haveLastPoll = false
                    } else {
                        if (paused && lastKey == null) {
                            assertEquals(0f, poll.progress, "progress reported over the app while paused")
                        }
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
