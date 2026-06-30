package io.github.owenpkent.openmouse.click

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Android wrapper around [DwellMachine]. It drives the machine from a 16 ms timer
 * using `SystemClock.uptimeMillis()`, reports countdown progress to [onProgress],
 * and fires [onClick] when a dwell completes.
 *
 * All the decision logic lives in [DwellMachine] (which is unit tested); this
 * class only owns the timer and the clock.
 */
class DwellClicker(
    dwellTimeMs: Long = DwellMachine.DEFAULT_DWELL_MS,
    moveThresholdPx: Float = DwellMachine.DEFAULT_MOVE_THRESHOLD_PX,
    private val onProgress: (Float) -> Unit,
    private val onClick: (Float, Float) -> Unit,
) {
    private val machine = DwellMachine(dwellTimeMs, moveThresholdPx)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val poll = machine.poll(SystemClock.uptimeMillis())
            onProgress(poll.progress)
            if (poll.clicked) onClick(poll.x, poll.y)
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        machine.reset(SystemClock.uptimeMillis())
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        onProgress(0f)
    }

    /**
     * Lock at the current position so a resting mouse does not immediately
     * dwell-click again (e.g. right after a physical-button click). The next
     * move past the threshold unlocks it.
     */
    fun lockUntilMove() {
        machine.lockUntilMove()
        onProgress(0f)
    }

    /** Feed a new cursor position in screen coordinates. */
    fun onCursorMoved(x: Float, y: Float) {
        machine.onMove(x, y, SystemClock.uptimeMillis())
    }

    companion object {
        private const val TICK_INTERVAL_MS = 16L
    }
}
