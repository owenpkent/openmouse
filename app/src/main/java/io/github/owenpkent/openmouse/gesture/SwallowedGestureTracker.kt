package io.github.owenpkent.openmouse.gesture

/**
 * Pure detection of injected gestures that the legacy (API 24-33) overlay
 * swallowed, with no Android dependencies so it can be unit tested.
 *
 * On those releases the overlay is touchable (it captures the mouse), so before
 * each injected gesture the service asks for it to be click-through and waits a
 * moment. The window manager applies that change asynchronously; on a slow
 * device it can still be touchable when the gesture arrives. The overlay then
 * receives the gesture itself, rejects it (it only takes mouse input), and the
 * tap is silently lost.
 *
 * Detection rests on one fact: during a passthrough the overlay only goes from
 * touchable to click-through, never back, until the passthrough ends. So if a
 * touchscreen DOWN reaches the overlay at or after the moment a gesture was
 * dispatched (and before that gesture finished), the overlay was still
 * touchable when the gesture was injected: the gesture was swallowed, not
 * delivered. That holds whether the DOWN was the gesture itself or a finger
 * that happened to land in the same window, so a gesture that did reach the
 * app is never reported as swallowed (bar a finger landing in the same
 * instant the window manager flips the flag). The service then runs the
 * gesture again, waiting longer each time ([delayBeforeAttemptMs]).
 *
 * Times are `uptimeMillis`, the clock MotionEvent.eventTime uses. Defined for
 * any input: unknown or repeated ids are ignored, and the number of gestures
 * remembered is capped so lost completion callbacks cannot grow it without
 * bound (the service also [reset]s it when its passthrough watchdog fires).
 */
class SwallowedGestureTracker {

    private class Pending(val dispatchedMs: Long) {
        var swallowed = false
    }

    // Insertion-ordered, so the oldest entry is the one dropped at the cap.
    private val pending = LinkedHashMap<Int, Pending>()
    private var nextId = 0

    /** Gestures dispatched and not yet [finished]. */
    val outstanding: Int get() = pending.size

    /** A gesture was handed to the platform at [nowMs]. Returns its id for [finished]. */
    fun dispatched(nowMs: Long): Int {
        val id = nextId++
        pending.remove(id) // only after the counter wraps, which is billions of gestures away
        if (pending.size >= MAX_OUTSTANDING) pending.remove(pending.keys.first())
        pending[id] = Pending(nowMs)
        return id
    }

    /**
     * A touchscreen DOWN (not the mouse) reached the overlay, stamped
     * [eventTimeMs]. Every outstanding gesture dispatched at or before that
     * time was swallowed.
     */
    fun overlayTouched(eventTimeMs: Long) {
        for (p in pending.values) {
            if (eventTimeMs >= p.dispatchedMs) p.swallowed = true
        }
    }

    /**
     * Gesture [id] completed or was cancelled. Returns true if the overlay
     * swallowed it, so it never reached the app. An unknown id (already
     * finished, dropped at the cap, or forgotten by [reset]) returns false.
     */
    fun finished(id: Int): Boolean = pending.remove(id)?.swallowed ?: false

    /** Forget every outstanding gesture (watchdog expiry or teardown). */
    fun reset() = pending.clear()

    companion object {
        /** Gestures remembered at once. Healthy operation has one or two. */
        const val MAX_OUTSTANDING = 32

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
