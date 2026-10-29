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
     * dwell-click again (e.g. right after a physical-button click or a menu
     * choice). The next move past the threshold, or into a different target,
     * unlocks it.
     */
    fun lockUntilMove() {
        machine.lockUntilMove()
        onProgress(0f)
    }

    /**
     * Restart the countdown where the pointer already rests, for repeating
     * actions (scroll). Called right after such an action fires, so another
     * one lands each dwell period while the pointer stays put.
     */
    fun rearmInPlace() {
        machine.rearmInPlace(SystemClock.uptimeMillis())
    }

    /** Pause or resume dwelling on the app. The menu keeps working while paused. */
    fun setPaused(paused: Boolean) {
        machine.setPaused(paused)
        onProgress(0f)
    }

    val isPaused: Boolean get() = machine.isPaused

    /**
     * Feed a new cursor position in screen coordinates. [restKey] names the
     * target under the cursor (the menu entry, or null over the app); see
     * [DwellMachine.onMove].
     */
    fun onCursorMoved(x: Float, y: Float, restKey: Any? = null) {
        machine.onMove(x, y, SystemClock.uptimeMillis(), restKey)
    }

    /** Update dwell timing live (from a settings change). */
    fun configure(dwellTimeMs: Long, moveThresholdPx: Float, postActionThresholdPx: Float) {
        machine.configure(dwellTimeMs, moveThresholdPx, postActionThresholdPx)
        // Re-baseline so lowering the dwell time mid-rest can't fire an instant
        // click; the next move past the threshold re-arms it.
        machine.lockUntilMove()
        onProgress(0f)
    }

    companion object {
        private const val TICK_INTERVAL_MS = 16L
    }
}
