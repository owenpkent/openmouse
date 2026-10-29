package io.github.owenpkent.openmouse.click

import kotlin.math.hypot

/**
 * Pure dwell-to-click state machine, with no Android dependencies so it can be
 * unit tested directly. The caller supplies a monotonic timestamp (milliseconds)
 * on every interaction; [DwellClicker] is the thin Android wrapper that feeds it
 * `SystemClock.uptimeMillis()` on a timer.
 *
 * Rules:
 * - It starts locked, so nothing clicks before the pointer has moved.
 * - Moving past [moveThresholdPx] re-anchors, restarts the countdown, and unlocks.
 * - When the countdown fills it reports a click once, then locks again until the
 *   next move past the threshold.
 *
 * The machine stays defined for any input rather than trusting its caller: a
 * dwell time below 1 ms is treated as 1 ms, a negative (or NaN) threshold as 0,
 * and a timestamp older than the rest anchor as zero elapsed time. The fuzz
 * target in `app/src/test/.../fuzz` drives it with arbitrary values to keep that
 * true.
 */
class DwellMachine(
    dwellTimeMs: Long = DEFAULT_DWELL_MS,
    moveThresholdPx: Float = DEFAULT_MOVE_THRESHOLD_PX,
) {
    private var dwellTimeMs = clampDwell(dwellTimeMs)
    private var moveThresholdPx = clampThreshold(moveThresholdPx)

    /** Update the timing live (e.g. from a settings change). */
    fun configure(dwellTimeMs: Long, moveThresholdPx: Float) {
        this.dwellTimeMs = clampDwell(dwellTimeMs)
        this.moveThresholdPx = clampThreshold(moveThresholdPx)
    }

    private var anchorX = 0f
    private var anchorY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var restStart = 0L
    private var locked = true

    /** A snapshot of the machine at a point in time. */
    data class Poll(val progress: Float, val clicked: Boolean, val x: Float, val y: Float)

    /** Re-arm the timer baseline (called when the engine (re)starts). */
    fun reset(nowMs: Long) {
        locked = true
        anchorX = currentX
        anchorY = currentY
        restStart = nowMs
    }

    /** Feed a new pointer position. */
    fun onMove(x: Float, y: Float, nowMs: Long) {
        currentX = x
        currentY = y
        // Strictly greater: a move of exactly the threshold still counts as resting,
        // so the boundary belongs to the countdown rather than cancelling it.
        val moved = hypot((x - anchorX).toDouble(), (y - anchorY).toDouble()) > moveThresholdPx
        if (moved) {
            anchorX = x
            anchorY = y
            restStart = nowMs
            locked = false
        }
    }

    /** Lock at the current position, as if a click had just happened. */
    fun lockUntilMove() {
        locked = true
        anchorX = currentX
        anchorY = currentY
    }

    /**
     * Evaluate the machine at [nowMs]. If the returned [Poll.clicked] is true the
     * machine has already locked itself, so the same rest will not click twice.
     * The firing poll reports zero progress so the countdown ring clears.
     */
    fun poll(nowMs: Long): Poll {
        if (locked) return Poll(0f, false, currentX, currentY)
        // A timestamp older than the anchor counts as no elapsed time, and a
        // difference too large for a Long saturates instead of wrapping negative
        // (the fuzz target drives both). The clamp on the divisor keeps progress
        // finite for any dwell time.
        val elapsed = elapsedSince(restStart, nowMs)
        val progress = (elapsed.toFloat() / dwellTimeMs).coerceIn(0f, 1f)
        if (progress >= 1f) {
            locked = true
            return Poll(0f, clicked = true, x = currentX, y = currentY)
        }
        return Poll(progress, clicked = false, x = currentX, y = currentY)
    }

    companion object {
        const val DEFAULT_DWELL_MS = 1000L
        const val DEFAULT_MOVE_THRESHOLD_PX = 14f

        private fun clampDwell(ms: Long): Long = ms.coerceAtLeast(1L)

        private fun elapsedSince(start: Long, now: Long): Long {
            if (now <= start) return 0L
            val d = now - start
            return if (d < 0L) Long.MAX_VALUE else d // overflow: saturate
        }

        // NaN fails every comparison, so test the inverse to map it to 0 as well.
        private fun clampThreshold(px: Float): Float = if (px >= 0f) px else 0f
    }
}
