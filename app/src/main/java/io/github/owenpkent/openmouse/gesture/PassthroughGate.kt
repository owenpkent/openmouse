package io.github.owenpkent.openmouse.gesture

/**
 * Pure bookkeeping for the legacy (API 24-33) touch passthrough, with no
 * Android dependencies so it can be unit tested. The service makes its overlay
 * click-through while injected gestures are in flight and restores it when the
 * last one completes; this class decides when each of those happens.
 *
 * Gestures are ref-counted so overlapping ones cannot restore the overlay
 * early. A dropped completion callback would leave the count stuck above zero
 * and the overlay stuck click-through, which the cursor cannot recover from on
 * its own (a click-through overlay receives no mouse events), so the oldest
 * outstanding gesture carries a **deadline**: [deadlineMs] is set when the
 * count leaves zero and is never pushed out by later gestures. When the
 * service's watchdog fires at that deadline, [expire] treats every outstanding
 * completion as lost and restores the overlay. Without the fixed deadline a
 * repeating action (scroll, one notch per dwell period) would reschedule the
 * watchdog on every repeat and keep the overlay click-through for as long as
 * the pointer rested.
 *
 * The deadline must exceed the longest single gesture, and gestures are only
 * ever dispatched one at a time per dwell period or physical click, so in
 * healthy operation the count returns to zero and clears the deadline before
 * it is reached; the watchdog fires only after a lost callback.
 */
class PassthroughGate(maxGestureMs: Long) {
    private val maxGestureMs = maxGestureMs.coerceAtLeast(1L)

    /** Gestures dispatched but not yet reported complete. */
    var inFlight: Int = 0
        private set

    /**
     * When the oldest outstanding gesture must have completed by, or null when
     * nothing is in flight. The service schedules its watchdog for this time.
     */
    var deadlineMs: Long? = null
        private set

    /**
     * A gesture is about to be dispatched at [nowMs]. Returns true when this is
     * the first outstanding gesture, so the caller must make the overlay
     * click-through and arm the watchdog for [deadlineMs]. Later gestures
     * leave the deadline where it is.
     */
    fun begin(nowMs: Long): Boolean {
        if (inFlight == Int.MAX_VALUE) return false // never wrap negative
        inFlight++
        if (inFlight > 1) return false
        deadlineMs = saturatingAdd(nowMs, maxGestureMs)
        return true
    }

    /**
     * A gesture reported completion. Returns true when none remain, so the
     * caller must restore the overlay and cancel the watchdog.
     */
    fun end(): Boolean {
        inFlight = (inFlight - 1).coerceAtLeast(0)
        if (inFlight > 0) return false
        deadlineMs = null
        return true
    }

    /**
     * The watchdog fired at [nowMs]. If the deadline has passed, every
     * outstanding completion is treated as lost: the count drops to zero and
     * the caller must restore the overlay. Returns true in that case, false if
     * nothing was in flight or the deadline is still ahead (a stale callback).
     */
    fun expire(nowMs: Long): Boolean {
        val deadline = deadlineMs ?: return false
        if (nowMs < deadline) return false
        reset()
        return true
    }

    /** Forget every outstanding gesture (the overlay is being torn down). */
    fun reset() {
        inFlight = 0
        deadlineMs = null
    }

    private fun saturatingAdd(a: Long, b: Long): Long {
        val sum = a + b
        return if (b > 0 && sum < a) Long.MAX_VALUE else sum
    }
}
