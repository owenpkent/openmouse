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
 * first in first out, to the oldest stroke that starts at that point, could
 * have produced a DOWN stamped at that event time, and is not yet claimed
 * (injected strokes arrive in the order they were dispatched). A gesture's
 * touches are stamped no earlier than its dispatch and no later than it
 * ended (its completion callback, or failing that its settle), so the event
 * time, not the moment the report arrives, says which gestures it can belong
 * to. The rule the service relies on is **never repeat what reached the
 * app**:
 *
 * - a gesture counts as swallowed only if *every* one of its strokes was seen
 *   on the overlay. A double tap whose second stroke got through has partly
 *   reached the app, and sending it again would add taps, so it is not resent;
 * - a DOWN only matches a stroke at its exact start point, so a finger
 *   elsewhere on the screen claims nothing;
 * - settled gestures are kept for [TOMBSTONE_MS], still first in line, so a
 *   DOWN that arrives late is absorbed by its own gesture rather than credited
 *   to a newer one that reached the app. Where the event time fits more than
 *   one gesture, the older wins: losing a click is recoverable, a repeat is
 *   not;
 * - a gesture that has ended never claims a DOWN stamped after it ended, so a
 *   click that reached the app cannot take the DOWN of the next click at the
 *   same spot and leave that one unresent;
 * - only the newest action the user asked for is resent ([requested] is called
 *   for every gesture and every menu choice), and only within
 *   [MAX_RETRY_LAG_MS] of its first attempt completing: sooner than a person
 *   notices nothing happened and clicks again, which would otherwise reach the
 *   app on its own and be followed by the retry.
 *
 * Because a partly swallowed gesture is never repaired, the first attempt is
 * made less likely to be split: a gesture whose strokes are spread out in time
 * (a double tap) waits longer before going out, and once any swallow has been
 * seen the device is known to be slow and every first attempt waits longer
 * from then on ([firstAttemptDelayMs]).
 *
 * Times are `uptimeMillis`, the clock MotionEvent.eventTime uses. Defined for
 * any input: unknown or repeated ids are ignored, time running backwards is
 * never inside the retry window, and the number of gestures remembered is
 * capped so lost completion callbacks cannot grow it without bound (the service
 * also [reset]s it when its passthrough watchdog fires).
 */
class SwallowedGestureTracker(tolerancePx: Float = DEFAULT_TOLERANCE_PX) {

    private val tolerancePx = if (tolerancePx >= 0f) tolerancePx else 0f

    private class Entry(val request: Int, val dispatchedMs: Long, val starts: FloatArray) {
        val strokes: Int get() = starts.size / 2
        var swallowedStrokes = 0
        var settledAtMs: Long? = null

        // No touch of this gesture is stamped later than this: when its
        // completion callback ran, or its settle if that came first.
        var endedAtMs: Long? = null

        fun couldBeStampedAt(eventTimeMs: Long): Boolean {
            val ended = endedAtMs
            return eventTimeMs >= dispatchedMs && (ended == null || eventTimeMs <= ended)
        }
    }

    // Insertion-ordered: iteration is dispatch order, and the oldest entry is
    // the one dropped at the cap. Holds outstanding gestures and tombstones.
    private val entries = LinkedHashMap<Int, Entry>()
    private var nextId = 0
    private var latestRequest = 0

    // When the newest request's first attempt settled, or null before then.
    private var latestFirstSettleMs: Long? = null

    // Raised for good once a swallow is seen: this device applies the
    // click-through change slowly. Survives reset(), which forgets gestures,
    // not what was learned about the device.
    private var learnedFirstWaitMs = FIRST_WAIT_MS

    /** Gestures dispatched and not yet [settle]d. */
    val outstanding: Int get() = entries.values.count { it.settledAtMs == null }

    /**
     * The user asked for something new: a gesture (not a retry of one) or a
     * menu choice. Returns its request number, which every attempt at a
     * gesture carries. Anything older can no longer be resent.
     */
    fun requested(): Int {
        latestRequest++
        latestFirstSettleMs = null
        return latestRequest
    }

    /**
     * Whether attempt [attempt] of [request] should go out at [nowMs]: always
     * the first one; a retry only while it is the newest request and still
     * inside the retry window.
     */
    fun shouldDispatch(request: Int, attempt: Int, nowMs: Long): Boolean =
        attempt <= 0 || (request == latestRequest && inRetryWindow(nowMs))

    /**
     * Attempt of [request] handed to the platform at [nowMs], its strokes
     * starting at [strokeStarts] (x, y pairs in stroke order; a trailing odd
     * value is ignored). Returns the id to [settle] it with.
     */
    fun dispatched(request: Int, nowMs: Long, strokeStarts: FloatArray): Int {
        prune(nowMs)
        val id = nextId++
        entries.remove(id) // only after the counter wraps, billions of gestures away
        if (entries.size >= MAX_REMEMBERED) entries.remove(entries.keys.first())
        entries[id] = Entry(request, nowMs, strokeStarts.copyOf(strokeStarts.size - strokeStarts.size % 2))
        return id
    }

    /**
     * Gesture [id]'s completion (or cancellation) callback ran at [nowMs].
     * Every touch the platform injected for it was stamped by then, so from
     * here on it only claims DOWNs stamped no later. Call it before [settle];
     * a gesture settled without it is taken to have ended at its settle. An
     * unknown, settled or already completed id is ignored.
     */
    fun completed(id: Int, nowMs: Long) {
        val e = entries[id] ?: return
        if (e.endedAtMs == null) e.endedAtMs = nowMs
    }

    /**
     * A touchscreen DOWN (not the mouse) reached the overlay at ([x], [y]),
     * stamped [eventTimeMs] (MotionEvent.eventTime: when it was injected, not
     * when it arrived). Claims the oldest stroke it can be, settled or not,
     * among the gestures in flight at that time. Returns whether it matched
     * one.
     */
    fun overlayTouched(eventTimeMs: Long, x: Float, y: Float): Boolean {
        for (e in entries.values) {
            if (!e.couldBeStampedAt(eventTimeMs) || e.swallowedStrokes >= e.strokes) continue
            val i = e.swallowedStrokes * 2
            if (near(e.starts[i], x) && near(e.starts[i + 1], y)) {
                e.swallowedStrokes++
                learnedFirstWaitMs = SLOW_DEVICE_FIRST_WAIT_MS
                return true
            }
        }
        return false
    }

    /**
     * Gesture [id], sent as attempt [attempt], completed or was cancelled and
     * its touches have had time to arrive; it is now [nowMs]. Returns whether
     * to send it again: every stroke was swallowed, it is still the newest
     * request, it is inside the retry window, and attempts remain. An unknown
     * or already settled id returns false. The entry is kept as a tombstone.
     */
    fun settle(id: Int, attempt: Int, nowMs: Long): Boolean {
        val e = entries[id] ?: return false
        if (e.settledAtMs != null) return false
        e.settledAtMs = nowMs
        if (e.endedAtMs == null) e.endedAtMs = nowMs
        val newest = e.request == latestRequest
        if (newest && latestFirstSettleMs == null) latestFirstSettleMs = nowMs
        val wholeGestureSwallowed = e.strokes > 0 && e.swallowedStrokes == e.strokes
        return wholeGestureSwallowed &&
            newest &&
            attempt >= 0 && attempt < MAX_ATTEMPTS - 1 &&
            inRetryWindow(nowMs)
    }

    /**
     * How long to wait, after making the overlay click-through, before the
     * first attempt at a gesture of [strokes] strokes. Retries use
     * [delayBeforeAttemptMs].
     */
    fun firstAttemptDelayMs(strokes: Int): Long =
        if (strokes > 1) maxOf(learnedFirstWaitMs, MULTI_STROKE_FIRST_WAIT_MS) else learnedFirstWaitMs

    /** Forget everything outstanding and settled (watchdog expiry or teardown). */
    fun reset() = entries.clear()

    private fun inRetryWindow(nowMs: Long): Boolean {
        val since = latestFirstSettleMs ?: return false
        // Compare without subtracting first, so extreme times cannot overflow.
        return nowMs >= since && nowMs - since in 0..MAX_RETRY_LAG_MS
    }

    private fun prune(nowMs: Long) {
        val it = entries.values.iterator()
        while (it.hasNext()) {
            val settled = it.next().settledAtMs ?: continue
            if (nowMs >= settled && nowMs - settled > TOMBSTONE_MS) it.remove()
        }
    }

    private fun near(a: Float, b: Float): Boolean = abs(a - b) <= tolerancePx

    companion object {
        /** Gestures remembered at once, tombstones included. Healthy operation has a few. */
        const val MAX_REMEMBERED = 32

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

        /** How long a settled gesture still absorbs its own late DOWNs. */
        const val TOMBSTONE_MS = 1000L

        /**
         * A retry goes out at most this long after the action's first attempt
         * completed, i.e. after the moment the user expected it to work.
         */
        const val MAX_RETRY_LAG_MS = 400L

        /**
         * The first wait for a single-stroke gesture on a device not yet seen
         * to be slow: the historical frame and a half, so a fast device pays
         * no extra latency.
         */
        const val FIRST_WAIT_MS = 24L

        /** The first wait once a swallow has been seen on this device. */
        const val SLOW_DEVICE_FIRST_WAIT_MS = 100L

        /**
         * The first wait for a gesture with several strokes. If the change
         * landed between its strokes, the gesture would be split (part on the
         * overlay, part in the app) and could not be safely resent.
         */
        const val MULTI_STROKE_FIRST_WAIT_MS = 200L

        // Index 0 is the default first wait (see firstAttemptDelayMs); a retry
        // waits longer, and by then the click-through request has usually been
        // in place for a while.
        private val DELAYS_MS = longArrayOf(FIRST_WAIT_MS, 100L, 200L)

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
