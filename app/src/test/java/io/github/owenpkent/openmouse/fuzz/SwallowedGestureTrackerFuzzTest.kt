package io.github.owenpkent.openmouse.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import io.github.owenpkent.openmouse.gesture.SwallowedGestureTracker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.math.abs

/**
 * Jazzer fuzz target for swallowed-gesture detection. The fuzzer drives
 * requested / dispatched / overlayTouched / settle / reset with arbitrary
 * (including backwards, extreme and NaN) times, coordinates, ids and attempts,
 * and every answer is checked against a plain reference model. On top of the
 * model, the safety rule is asserted directly:
 *
 * - settle() says retry only if every stroke of that gesture was claimed by an
 *   overlay DOWN at its start point, stamped no earlier than its dispatch, the
 *   gesture is still the newest request, and attempts remain;
 * - one DOWN claims at most one stroke;
 * - an id is answered once; unknown ids never retry;
 * - outstanding matches the model and never exceeds the cap.
 *
 * Without `JAZZER_FUZZ=1` this only replays the seed corpus as a JUnit test.
 */
class SwallowedGestureTrackerFuzzTest {

    private class Model(val request: Int, val dispatchedMs: Long, val starts: FloatArray) {
        val strokes get() = starts.size / 2
        var claimed = 0
    }

    @FuzzTest(maxDuration = "10m")
    fun arbitraryEventSequenceMatchesTheModel(data: FuzzedDataProvider) {
        val tolerance = SwallowedGestureTracker.DEFAULT_TOLERANCE_PX
        val tracker = SwallowedGestureTracker(tolerance)
        val model = LinkedHashMap<Int, Model>()
        val issued = mutableListOf<Int>()
        var latest = 0

        fun near(a: Float, b: Float) = abs(a - b) <= tolerance

        while (data.remainingBytes() > 0) {
            when (data.consumeInt(0, 5)) {
                0 -> {
                    latest = tracker.requested()
                }

                1 -> {
                    val request = if (data.consumeBoolean()) latest else data.consumeInt()
                    val now = data.consumeLong()
                    val starts = FloatArray(data.consumeInt(0, 7)) { data.consumeFloat() }
                    val id = tracker.dispatched(request, now, starts)
                    assertFalse(id in model, "id $id handed out while still outstanding")
                    if (model.size >= SwallowedGestureTracker.MAX_OUTSTANDING) model.remove(model.keys.first())
                    model[id] = Model(request, now, starts.copyOf(starts.size - starts.size % 2))
                    issued += id
                }

                2 -> {
                    // Often aimed at a real stroke start, sometimes anywhere.
                    val target = model.values.toList().takeIf { it.isNotEmpty() && data.consumeBoolean() }
                        ?.let { it[data.consumeInt(0, it.size - 1)] }
                    val x: Float
                    val y: Float
                    if (target != null && target.strokes > 0) {
                        val s = data.consumeInt(0, target.strokes - 1) * 2
                        x = target.starts[s]
                        y = target.starts[s + 1]
                    } else {
                        x = data.consumeFloat()
                        y = data.consumeFloat()
                    }
                    val at = data.consumeLong()

                    var expected = false
                    for (m in model.values) {
                        if (at < m.dispatchedMs || m.claimed >= m.strokes) continue
                        val i = m.claimed * 2
                        if (near(m.starts[i], x) && near(m.starts[i + 1], y)) {
                            m.claimed++
                            expected = true
                            break
                        }
                    }
                    assertEquals(expected, tracker.overlayTouched(at, x, y), "overlayTouched($at, $x, $y)")
                }

                3 -> {
                    val id = if (issued.isNotEmpty() && data.consumeBoolean()) {
                        issued[data.consumeInt(0, issued.size - 1)]
                    } else {
                        data.consumeInt()
                    }
                    val attempt = data.consumeInt()
                    val m = model.remove(id)
                    val expected = m != null &&
                        m.strokes > 0 && m.claimed == m.strokes &&
                        m.request == latest &&
                        attempt >= 0 && attempt < SwallowedGestureTracker.MAX_ATTEMPTS - 1
                    assertEquals(expected, tracker.settle(id, attempt), "settle($id, $attempt)")
                }

                4 -> {
                    val request = data.consumeInt()
                    val attempt = data.consumeInt()
                    assertEquals(attempt <= 0 || request == latest, tracker.shouldDispatch(request, attempt))
                }

                else -> {
                    tracker.reset()
                    model.clear()
                }
            }

            assertEquals(model.size, tracker.outstanding, "outstanding count")
            assertTrue(tracker.outstanding <= SwallowedGestureTracker.MAX_OUTSTANDING, "cap exceeded")
        }
    }
}
