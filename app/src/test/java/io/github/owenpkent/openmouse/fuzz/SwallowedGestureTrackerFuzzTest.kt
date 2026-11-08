package io.github.owenpkent.openmouse.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import io.github.owenpkent.openmouse.gesture.SwallowedGestureTracker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Jazzer fuzz target for swallowed-gesture detection. The fuzzer drives
 * dispatched / overlayTouched / finished / reset with arbitrary (including
 * backwards and extreme) timestamps and ids, and every answer is checked
 * against a plain reference model:
 *
 * - finished(id) is true exactly when that gesture is still remembered and some
 *   overlay DOWN stamped at or after its dispatch arrived while it was;
 * - an id is answered at most once, and unknown ids are never swallowed;
 * - outstanding matches the model and never exceeds the cap (oldest dropped);
 * - dispatched never hands out an id that is still outstanding.
 *
 * Without `JAZZER_FUZZ=1` this only replays the seed corpus as a JUnit test.
 */
class SwallowedGestureTrackerFuzzTest {

    private class Model(val dispatchedMs: Long, var swallowed: Boolean = false)

    @FuzzTest(maxDuration = "10m")
    fun arbitraryEventSequenceMatchesTheModel(data: FuzzedDataProvider) {
        val tracker = SwallowedGestureTracker()
        val model = LinkedHashMap<Int, Model>()
        val issued = mutableListOf<Int>()

        while (data.remainingBytes() > 0) {
            when (data.consumeInt(0, 3)) {
                0 -> {
                    val now = data.consumeLong()
                    val id = tracker.dispatched(now)
                    assertTrue(id !in model, "id $id handed out while still outstanding")
                    if (model.size >= SwallowedGestureTracker.MAX_OUTSTANDING) model.remove(model.keys.first())
                    model[id] = Model(now)
                    issued += id
                }

                1 -> {
                    val at = data.consumeLong()
                    tracker.overlayTouched(at)
                    for (m in model.values) if (at >= m.dispatchedMs) m.swallowed = true
                }

                2 -> {
                    // Mostly ids that were really issued, sometimes arbitrary ones.
                    val id = if (issued.isNotEmpty() && data.consumeBoolean()) {
                        issued[data.consumeInt(0, issued.size - 1)]
                    } else {
                        data.consumeInt()
                    }
                    val expected = model.remove(id)?.swallowed ?: false
                    assertEquals(expected, tracker.finished(id), "finished($id)")
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
