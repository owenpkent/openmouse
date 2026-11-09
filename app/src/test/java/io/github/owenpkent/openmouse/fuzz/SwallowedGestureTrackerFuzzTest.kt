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
 * requested / dispatched / completed / overlayTouched / settle / shouldDispatch /
 * reset with arbitrary (including backwards, extreme and NaN) times,
 * coordinates, ids and attempts. Every answer is checked against a plain
 * reference model, and the safety rule is asserted on its own terms:
 *
 * - settle() says resend only if every stroke of that gesture was claimed by an
 *   overlay DOWN at its start point stamped no earlier than its dispatch and no
 *   later than it ended (completed, or else settled), it is the newest request,
 *   attempts remain, and the time is inside the window since the request's
 *   first attempt settled;
 * - a retry is only ever allowed to go out for the newest request, inside that
 *   window;
 * - a DOWN is only ever credited to a gesture in flight at its event time
 *   (dispatched no later, not ended earlier) with a stroke starting there;
 * - one DOWN claims at most one stroke; an id is answered once;
 * - outstanding matches the model and the memory never exceeds the cap;
 * - the first wait rises for good once any stroke has been swallowed, and a
 *   multi-stroke gesture always waits at least the multi-stroke minimum.
 *
 * Without `JAZZER_FUZZ=1` this only replays the seed corpus as a JUnit test.
 */
class SwallowedGestureTrackerFuzzTest {

    private class Entry(val request: Int, val dispatchedMs: Long, val starts: FloatArray) {
        val strokes get() = starts.size / 2
        var claimed = 0
        var settledAtMs: Long? = null
        var endedAtMs: Long? = null
    }

    @FuzzTest(maxDuration = "10m")
    fun arbitraryEventSequenceMatchesTheModel(data: FuzzedDataProvider) {
        val tolerance = SwallowedGestureTracker.DEFAULT_TOLERANCE_PX
        val tracker = SwallowedGestureTracker(tolerance)
        val model = LinkedHashMap<Int, Entry>()
        val issued = mutableListOf<Int>()
        var latest = 0
        var firstSettle: Long? = null
        var slowDevice = false

        fun near(a: Float, b: Float) = abs(a - b) <= tolerance
        fun inWindow(now: Long): Boolean {
            val since = firstSettle ?: return false
            return now >= since && now - since in 0..SwallowedGestureTracker.MAX_RETRY_LAG_MS
        }

        while (data.remainingBytes() > 0) {
            when (data.consumeInt(0, 6)) {
                0 -> {
                    latest = tracker.requested()
                    firstSettle = null
                }

                1 -> {
                    val request = if (data.consumeBoolean()) latest else data.consumeInt()
                    val now = data.consumeLong()
                    val starts = FloatArray(data.consumeInt(0, 7)) { data.consumeFloat() }
                    // prune, then cap, then insert
                    model.values.removeIf { e ->
                        val s = e.settledAtMs
                        s != null && now >= s && now - s > SwallowedGestureTracker.TOMBSTONE_MS
                    }
                    val id = tracker.dispatched(request, now, starts)
                    assertFalse(id in model, "id $id handed out while still remembered")
                    if (model.size >= SwallowedGestureTracker.MAX_REMEMBERED) model.remove(model.keys.first())
                    model[id] = Entry(request, now, starts.copyOf(starts.size - starts.size % 2))
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

                    fun inFlightAt(e: Entry): Boolean {
                        val ended = e.endedAtMs
                        return at >= e.dispatchedMs && (ended == null || at <= ended)
                    }

                    // Stated without the model's first-in-first-out order: a
                    // stroke starting there, of a gesture in flight at that time.
                    val couldBeAnyones = model.values.any { e ->
                        inFlightAt(e) && (0 until e.strokes).any { near(e.starts[it * 2], x) && near(e.starts[it * 2 + 1], y) }
                    }

                    var expected = false
                    for (e in model.values) {
                        if (!inFlightAt(e) || e.claimed >= e.strokes) continue
                        val i = e.claimed * 2
                        if (near(e.starts[i], x) && near(e.starts[i + 1], y)) {
                            e.claimed++
                            expected = true
                            slowDevice = true
                            break
                        }
                    }
                    val claimed = tracker.overlayTouched(at, x, y)
                    assertEquals(expected, claimed, "overlayTouched($at, $x, $y)")
                    if (claimed) {
                        assertTrue(couldBeAnyones, "credited a DOWN no gesture in flight at $at could have made")
                    }
                }

                3 -> {
                    val id = if (issued.isNotEmpty() && data.consumeBoolean()) {
                        issued[data.consumeInt(0, issued.size - 1)]
                    } else {
                        data.consumeInt()
                    }
                    val attempt = data.consumeInt(-1, SwallowedGestureTracker.MAX_ATTEMPTS + 1)
                    val now = data.consumeLong()
                    val e = model[id]
                    var expected = false
                    if (e != null && e.settledAtMs == null) {
                        e.settledAtMs = now
                        if (e.endedAtMs == null) e.endedAtMs = now
                        val newest = e.request == latest
                        if (newest && firstSettle == null) firstSettle = now
                        expected = e.strokes > 0 && e.claimed == e.strokes && newest &&
                            attempt >= 0 && attempt < SwallowedGestureTracker.MAX_ATTEMPTS - 1 &&
                            inWindow(now)
                    }
                    val retry = tracker.settle(id, attempt, now)
                    assertEquals(expected, retry, "settle($id, $attempt, $now)")
                    if (retry) {
                        // The safety rule, stated independently of the model's bookkeeping.
                        assertTrue(e!!.strokes > 0 && e.claimed == e.strokes, "resending a gesture not wholly swallowed")
                        assertEquals(latest, e.request, "resending something superseded")
                    }
                }

                5 -> {
                    val id = if (issued.isNotEmpty() && data.consumeBoolean()) {
                        issued[data.consumeInt(0, issued.size - 1)]
                    } else {
                        data.consumeInt()
                    }
                    val now = data.consumeLong()
                    tracker.completed(id, now)
                    model[id]?.let { if (it.endedAtMs == null) it.endedAtMs = now }
                }

                4 -> {
                    val request = if (data.consumeBoolean()) latest else data.consumeInt()
                    val attempt = data.consumeInt()
                    val now = data.consumeLong()
                    val allowed = tracker.shouldDispatch(request, attempt, now)
                    assertEquals(attempt <= 0 || (request == latest && inWindow(now)), allowed)
                    if (allowed && attempt > 0) assertEquals(latest, request, "a superseded retry went out")
                }

                else -> {
                    tracker.reset()
                    model.clear()
                }
            }

            assertEquals(model.values.count { it.settledAtMs == null }, tracker.outstanding, "outstanding count")
            val single = if (slowDevice) SwallowedGestureTracker.SLOW_DEVICE_FIRST_WAIT_MS else SwallowedGestureTracker.FIRST_WAIT_MS
            assertEquals(single, tracker.firstAttemptDelayMs(1), "single-stroke first wait")
            assertEquals(maxOf(single, SwallowedGestureTracker.MULTI_STROKE_FIRST_WAIT_MS), tracker.firstAttemptDelayMs(2))
            assertTrue(model.size <= SwallowedGestureTracker.MAX_REMEMBERED, "cap exceeded")
        }
    }
}
