package io.github.owenpkent.openmouse.click

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.hypot

/**
 * Dwell-to-click engine.
 *
 * When the cursor holds still (stays within [moveThresholdPx] of where it came
 * to rest) for [dwellTimeMs], the engine fires [onClick]. While the countdown
 * runs it reports a 0..1 progress value to [onProgress] so the cursor can draw
 * a shrinking countdown.
 *
 * After a click the engine locks: it will not click again until the cursor
 * first moves away by more than [moveThresholdPx]. That stops a resting mouse
 * from firing the same click over and over.
 */
class DwellClicker(
    private val dwellTimeMs: Long = DEFAULT_DWELL_MS,
    private val moveThresholdPx: Float = DEFAULT_MOVE_THRESHOLD_PX,
    private val onProgress: (Float) -> Unit,
    private val onClick: (Float, Float) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())

    private var anchorX = 0f
    private var anchorY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var restStartUptime = 0L
    private var running = false

    // Start locked so we never click before the mouse has actually moved. The
    // first real movement unlocks the engine (see onCursorMoved).
    private var locked = true

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            evaluate()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        locked = true
        restStartUptime = SystemClock.uptimeMillis()
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        onProgress(0f)
    }

    /** Feed a new cursor position in screen coordinates. */
    fun onCursorMoved(x: Float, y: Float) {
        currentX = x
        currentY = y
        val movedFar = hypot((x - anchorX).toDouble(), (y - anchorY).toDouble()) > moveThresholdPx
        if (movedFar) {
            // The cursor relocated: re-anchor here, restart the countdown, and
            // unlock so this new resting point can produce a click.
            anchorX = x
            anchorY = y
            restStartUptime = SystemClock.uptimeMillis()
            locked = false
        }
    }

    private fun evaluate() {
        if (locked) {
            onProgress(0f)
            return
        }
        val elapsed = SystemClock.uptimeMillis() - restStartUptime
        val progress = (elapsed.toFloat() / dwellTimeMs).coerceIn(0f, 1f)
        onProgress(progress)
        if (progress >= 1f) {
            locked = true
            onProgress(0f)
            onClick(currentX, currentY)
        }
    }

    companion object {
        const val DEFAULT_DWELL_MS = 1000L
        const val DEFAULT_MOVE_THRESHOLD_PX = 14f
        private const val TICK_INTERVAL_MS = 16L
    }
}
