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
 */
class DwellMachine(
    private var dwellTimeMs: Long = DEFAULT_DWELL_MS,
    private var moveThresholdPx: Float = DEFAULT_MOVE_THRESHOLD_PX,
) {
    /** Update the timing live (e.g. from a settings change). */
    fun configure(dwellTimeMs: Long, moveThresholdPx: Float) {
        this.dwellTimeMs = dwellTimeMs
        this.moveThresholdPx = moveThresholdPx
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
        restStart = nowMs
    }

    /** Feed a new pointer position. */
    fun onMove(x: Float, y: Float, nowMs: Long) {
        currentX = x
        currentY = y
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
     */
    fun poll(nowMs: Long): Poll {
        if (locked) return Poll(0f, false, currentX, currentY)
        val elapsed = nowMs - restStart
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
    }
}
