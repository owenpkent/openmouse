package io.github.owenpkent.openmouse.gesture

import kotlin.math.abs

/**
 * Pure detection of injected gestures that the legacy (API 24-33) overlay
 * swallowed, and the decision to send one again. No Android dependencies, so it
 * is unit tested and fuzzed.
 *
 * On those releases the overlay is touchable (it captures the mouse), so before
 * each injected gesture the service asks for it to be click-through and waits a
 * moment. The window manager applies that change asynchronously; on a slow
 * device it can still be touchable when the gesture arrives. The overlay then
 * receives the gesture itself, rejects it (it only takes mouse input), and the
 * action is silently lost.
 *
 * The overlay reports every touchscreen DOWN it rejects. Each one is matched,
 * first in first out, to the oldest outstanding stroke that starts at that
 * point (injected strokes arrive in the order they were dispatched). The rule
 * the service relies on is **never repeat what reached the app**:
 *
 * - a gesture counts as swallowed only if *every* one of its strokes was seen
 *   on the overlay. A double tap whose second stroke got through has partly
 *   reached the app, and sending it again would add taps, so it is not retried;
 * - a DOWN only matches a stroke dispatched at or before it, at the stroke's
 *   start point, so a finger elsewhere on the screen claims nothing;
 * - only the newest action the user asked for is retried ([requested]). Once a
 *   newer one exists, an older retry would cancel or reorder it, so it is
 *   dropped instead.
 *
 * Times are `uptimeMillis`, the clock MotionEvent.eventTime uses. Defined for
 * any input: unknown or repeated ids are ignored, and the number of gestures
 * remembered is capped so lost completion callbacks cannot grow it without
 * bound (the service also [reset]s it when its passthrough watchdog fires).
 */
class SwallowedGestureTracker(tolerancePx: Float = DEFAULT_TOLERANCE_PX) {

    private val tolerancePx = if (tolerancePx >= 0f) tolerancePx else 0f

    private class Pending(val request: Int, val dispatchedMs: Long, val starts: FloatArray) {
        val strokes: Int get() = starts.size / 2
        var swallowedStrokes = 0
    }

    // Insertion-ordered, so iteration is dispatch order and the oldest entry is
    // the one dropped at the cap.
    private val pending = LinkedHashMap<Int, Pending>()
    private var nextId = 0
    private var latestRequest = 0

    /** Gestures dispatched and not yet [settle]d. */
    val outstanding: Int get() = pending.size

    /**
     * The user asked for a new action (not a retry of one). Returns its request
     * number, which every attempt at that action carries.
     */
    fun requested(): Int {
        latestRequest++
        return latestRequest
    }

    /**
     * Whether attempt [attempt] of [request] should still go out: always the
     * first one; a retry only while no newer action has been asked for.
     */
    fun shouldDispatch(request: Int, attempt: Int): Boolean = attempt <= 0 || request == latestRequest

    /**
     * Attempt of [request] handed to the platform at [nowMs], its strokes
     * starting at [strokeStarts] (x, y pairs in stroke order; a trailing odd
     * value is ignored). Returns the id to [settle] it with.
     */
    fun dispatched(request: Int, nowMs: Long, strokeStarts: FloatArray): Int {
        val id = nextId++
        pending.remove(id) // only after the counter wraps, billions of gestures away
        if (pending.size >= MAX_OUTSTANDING) pending.remove(pending.keys.first())
        pending[id] = Pending(request, nowMs, strokeStarts.copyOf(strokeStarts.size - strokeStarts.size % 2))
        return id
    }

    /**
     * A touchscreen DOWN (not the mouse) reached the overlay at ([x], [y]),
     * stamped [eventTimeMs]. Claims the oldest outstanding stroke it can be.
     * Returns whether it matched one.
     */
    fun overlayTouched(eventTimeMs: Long, x: Float, y: Float): Boolean {
        for (p in pending.values) {
            if (eventTimeMs < p.dispatchedMs || p.swallowedStrokes >= p.strokes) continue
            val i = p.swallowedStrokes * 2
            if (near(p.starts[i], x) && near(p.starts[i + 1], y)) {
                p.swallowedStrokes++
                return true
            }
        }
        return false
    }

    /**
     * Gesture [id], sent as attempt [attempt], completed or was cancelled and
     * its touches have had time to arrive. Forgets it and returns whether to
     * send it again: every stroke was swallowed, it is still the newest action,
     * and attempts remain. An unknown id returns false.
     */
    fun settle(id: Int, attempt: Int): Boolean {
        val p = pending.remove(id) ?: return false
        val wholeGestureSwallowed = p.strokes > 0 && p.swallowedStrokes == p.strokes
        return wholeGestureSwallowed &&
            p.request == latestRequest &&
            attempt >= 0 && attempt < MAX_ATTEMPTS - 1
    }

    /** Forget every outstanding gesture (watchdog expiry or teardown). */
    fun reset() = pending.clear()

    private fun near(a: Float, b: Float): Boolean = abs(a - b) <= tolerancePx

    companion object {
        /** Gestures remembered at once. Healthy operation has one or two. */
        const val MAX_OUTSTANDING = 32

        /**
         * Injected strokes land where they were aimed; allow for rounding to
         * the input pipeline's precision and nothing more, so a finger nearby
         * cannot pass for one.
         */
        const val DEFAULT_TOLERANCE_PX = 2f

        /**
         * How long the service waits after a gesture finishes before settling
         * it. The overlay's input channel and the completion callback are not
         * ordered with each other, so a swallowed DOWN can arrive just after.
         */
        const val SETTLE_MS = 48L

        // The first wait is the historical one, a frame and a half, so a fast
        // device pays no extra latency. Each retry gives the window manager
        // longer to apply the click-through change.
        private val DELAYS_MS = longArrayOf(24L, 100L, 250L, 500L)

        /** Attempts made at most, the first included. */
        val MAX_ATTEMPTS: Int get() = DELAYS_MS.size

        /**
         * How long to wait, after making the overlay click-through, before
         * dispatching attempt [attempt] (0 is the first try). Null once the
         * attempts are used up (or for a negative attempt): give up.
         */
        fun delayBeforeAttemptMs(attempt: Int): Long? =
            if (attempt in DELAYS_MS.indices) DELAYS_MS[attempt] else null
    }
}
