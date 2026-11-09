package io.github.owenpkent.openmouse.click

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Android wrapper around [DwellMachine]. It drives the machine from a 16 ms timer
 * using `SystemClock.uptimeMillis()`, but only while a countdown is running
 * ([DwellMachine.isCountingDown]); an idle machine costs no wake-ups. It reports
 * countdown progress to [onProgress], and fires [onClick] when a dwell completes.
 *
 * All the decision logic lives in [DwellMachine] (which is unit tested); this
 * class only owns the timer and the clock.
 */
class DwellClicker(
    dwellTimeMs: Long = DwellMachine.DEFAULT_DWELL_MS,
    moveThresholdPx: Float = DwellMachine.DEFAULT_MOVE_THRESHOLD_PX,
    repeatIntervalMs: Long = dwellTimeMs,
    private val onProgress: (Float) -> Unit,
    private val onClick: (Float, Float) -> Unit,
) {
    private val machine = DwellMachine(dwellTimeMs, moveThresholdPx, repeatIntervalMs = repeatIntervalMs)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    // The ticker runs only while the machine is counting down. Most of the time
    // (pointer moving, locked after a click, paused, a button held) time alone
    // changes nothing, and a 16 ms wake-up for nothing costs battery.
    private var ticking = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) {
                ticking = false
                return
            }
            val poll = machine.poll(SystemClock.uptimeMillis())
            onProgress(poll.ringProgress) // the ring waits out the start of a countdown; the click does not
            if (poll.clicked) onClick(poll.x, poll.y)
            // Decide after the callback: a scroll re-arms in place from inside
            // onClick (ensureTicking sees the flag still set and posts nothing),
            // while a click locks and the ticker simply stops.
            if (running && machine.isCountingDown) {
                handler.postDelayed(this, TICK_INTERVAL_MS)
            } else {
                ticking = false
            }
        }
    }

    // Start the ticker if a countdown is running and it is not already going.
    // Called after every machine call that can arm it.
    private fun ensureTicking() {
        if (running && !ticking && machine.isCountingDown) {
            ticking = true
            handler.post(tick)
        }
    }

    fun start() {
        if (running) return
        running = true
        machine.reset(SystemClock.uptimeMillis())
        ensureTicking() // reset locks, so nothing ticks until the pointer moves
    }

    fun stop() {
        running = false
        ticking = false
        handler.removeCallbacks(tick)
        onProgress(0f)
    }

    /**
     * Lock at the current position so a resting mouse does not immediately
     * dwell-click again (e.g. right after a physical-button click or a menu
     * choice). The next move past the threshold unlocks it, whichever target
     * it lands on.
     */
    fun lockUntilMove() {
        machine.lockUntilMove()
        onProgress(0f)
    }

    /**
     * Restart the countdown where the pointer already rests, for repeating
     * actions (scroll). Called right after such an action fires, so another
     * one lands each repeat interval while the pointer stays put.
     */
    fun rearmInPlace() {
        machine.rearmInPlace(SystemClock.uptimeMillis())
        ensureTicking()
    }

    /** Pause or resume dwelling on the app. The menu keeps working while paused. */
    fun setPaused(paused: Boolean) {
        machine.setPaused(paused)
        onProgress(0f)
    }

    val isPaused: Boolean get() = machine.isPaused

    /**
     * Report whether any mouse button is down, on every pointer event. While one
     * is, dwelling is suppressed (the user's own drag or selection must never be
     * cut short); the release locks until the pointer moves. Cheap to call
     * repeatedly: only a change touches the machine or the ring.
     */
    fun setButtonHeld(held: Boolean) {
        if (machine.setExternalButtonHeld(held)) onProgress(0f)
    }

    /**
     * Feed a new cursor position in screen coordinates. [restKey] names the
     * target under the cursor (the menu entry, or null over the app); see
     * [DwellMachine.onMove].
     */
    fun onCursorMoved(x: Float, y: Float, restKey: Any? = null) {
        machine.onMove(x, y, SystemClock.uptimeMillis(), restKey)
        ensureTicking() // a move is the one thing that arms a locked machine
    }

    /** Update dwell timing live (from a settings change). */
    fun configure(dwellTimeMs: Long, moveThresholdPx: Float, postActionThresholdPx: Float, repeatIntervalMs: Long) {
        machine.configure(dwellTimeMs, moveThresholdPx, postActionThresholdPx, repeatIntervalMs)
        // Re-baseline so lowering the dwell time mid-rest can't fire an instant
        // click; the next move past the threshold re-arms it.
        machine.lockUntilMove()
        onProgress(0f)
    }

    companion object {
        private const val TICK_INTERVAL_MS = 16L
    }
}
