package io.github.owenpkent.openmouse.click

import kotlin.math.hypot

/**
 * Pure dwell-to-click state machine, with no Android dependencies so it can be
 * unit tested directly. The caller supplies a monotonic timestamp (milliseconds)
 * on every interaction; [DwellClicker] is the thin Android wrapper that feeds it
 * `SystemClock.uptimeMillis()` on a timer.
 *
 * Dwell clicking's dominant failure is the "Midas touch" problem: the machine
 * cannot tell a pointer being rested from one being aimed, so it fires clicks the
 * user never intended. Every rule below is biased toward refusing to fire when
 * intent is unclear (the same bias as the PowerToys Dwell Click engine, whose
 * rules these mirror):
 *
 * - It starts **locked**, and re-locks after every click, after a physical click
 *   ([lockUntilMove]), on pause, after a live settings change, and on [reset].
 *   A lock is cleared only by deliberate pointer movement; no amount of elapsed
 *   time will.
 * - **Two tolerances.** [moveThresholdPx] is the jitter a running countdown
 *   survives; drift within it leaves the countdown untouched, which is what lets
 *   a hand with a tremor complete a dwell at all. [postActionThresholdPx] is how
 *   far the pointer must travel after a click before a new countdown may start,
 *   kept separate so it can be widened without making dwelling harder. Only the
 *   lock a click leaves behind uses it; every other lock uses the ordinary
 *   tolerance. It never reads narrower than the movement tolerance.
 * - **Rest keys.** [onMove] takes an optional key naming the target under the
 *   pointer (the service passes the menu cell, or null over the app). While the
 *   key is unchanged the target itself is the tolerance: jitter inside a menu
 *   cell never restarts or re-arms, however large, and only leaving the cell
 *   does. Entering a cell starts its countdown at once; leaving a cell for the
 *   app locks, and the ordinary distance from the rest point applies again.
 *   This is what stops a parked pointer from firing the same menu entry every
 *   dwell period, and a slip off a cell from tapping the app beside it.
 * - **Pause** ([setPaused]) suspends dwelling on the app but not on the menu
 *   (any rest with a non-null key), so a user who cannot click can still reach
 *   the resume entry. It locks in both directions.
 * - **Repeat in place** ([rearmInPlace]) restarts the countdown at the same
 *   anchor without requiring movement, for actions that are modes rather than
 *   one-shots (scrolling): one notch per dwell period for as long as the pointer
 *   keeps resting.
 *
 * The machine stays defined for any input rather than trusting its caller: a
 * dwell time below 1 ms reads as 1 ms, a negative (or NaN) threshold as 0, a
 * timestamp older than the rest anchor as zero elapsed time, and an elapsed
 * time too large for a Long saturates. The fuzz target in
 * `app/src/test/.../fuzz` drives it with arbitrary values to keep that true.
 */
class DwellMachine(
    dwellTimeMs: Long = DEFAULT_DWELL_MS,
    moveThresholdPx: Float = DEFAULT_MOVE_THRESHOLD_PX,
    postActionThresholdPx: Float = moveThresholdPx,
) {
    private var dwellTimeMs = clampDwell(dwellTimeMs)
    private var moveThresholdPx = clampThreshold(moveThresholdPx)
    private var postActionThresholdPx = clampThreshold(postActionThresholdPx)

    /**
     * Update the settings live. The caller should [lockUntilMove] afterwards (as
     * [DwellClicker] does) so a shorter dwell time cannot fire against rest that
     * was already banked under the old one.
     */
    fun configure(
        dwellTimeMs: Long,
        moveThresholdPx: Float,
        postActionThresholdPx: Float = moveThresholdPx,
    ) {
        this.dwellTimeMs = clampDwell(dwellTimeMs)
        this.moveThresholdPx = clampThreshold(moveThresholdPx)
        this.postActionThresholdPx = clampThreshold(postActionThresholdPx)
    }

    // The position (and target key) the countdown is measured from, and the
    // latest reported ones. They are separate so drift within the tolerance
    // still updates the click point without disturbing the countdown.
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorKey: Any? = null
    private var currentX = 0f
    private var currentY = 0f
    private var currentKey: Any? = null
    private var restStart = 0L

    private var locked = true
    private var lockedAfterAction = false
    private var paused = false

    /** A snapshot of the machine at a point in time. */
    data class Poll(val progress: Float, val clicked: Boolean, val x: Float, val y: Float)

    val isPaused: Boolean get() = paused

    /**
     * Lock and re-baseline the rest timer. Called when the engine (re)starts, so
     * a pointer left resting across a stop/start cycle cannot fire the instant
     * the machine comes back. Pause is a user choice, not transient state, and
     * survives it.
     */
    fun reset(nowMs: Long) {
        lockUntilMove()
        restStart = nowMs
    }

    /**
     * Feed a new pointer position, with [restKey] naming the target under it
     * (null for "nothing in particular", such as the app below the cursor).
     * Never fires anything itself; it only decides whether the pointer has moved
     * enough to re-arm a locked machine or to restart a running countdown.
     */
    fun onMove(x: Float, y: Float, nowMs: Long, restKey: Any? = null) {
        currentX = x
        currentY = y
        currentKey = restKey

        val keyChanged = restKey != anchorKey

        // Entering a keyed target (a menu cell) starts its countdown at once,
        // whatever the distance: the cell edge is the deliberate act.
        if (keyChanged && restKey != null) {
            startCountdown(x, y, restKey, nowMs)
            return
        }

        // Leaving a keyed target for nothing in particular (the app, or the gap
        // between cells) is not the same act. Any countdown the cell had is
        // abandoned, and from here the ordinary distance rule applies, measured
        // from where the pointer rested. Without this, a 3 px slip into a 6 dp
        // gap, or a menu that re-laid out under a still pointer, would start a
        // countdown that taps the app.
        if (keyChanged) {
            if (!locked) {
                locked = true
                lockedAfterAction = false
            }
            anchorKey = null
        }

        // Inside the same keyed target the target is the tolerance: no distance
        // check at all, so a tremor within a menu cell can neither cancel its
        // countdown nor re-fire an entry the pointer never left.
        if (restKey != null) return

        // A post-action lock uses the wider re-arm distance; every other lock
        // (startup, settings change, physical click, pause) and a running
        // countdown use the ordinary tolerance. Strictly greater: a move of
        // exactly the threshold still counts as resting.
        val threshold = if (locked && lockedAfterAction) effectivePostActionThresholdPx() else moveThresholdPx
        val moved = hypot((x - anchorX).toDouble(), (y - anchorY).toDouble()) > threshold
        if (moved) startCountdown(x, y, restKey, nowMs)
    }

    // The re-arm distance exists to be wider than the movement tolerance; a value
    // below it would make the lock a click leaves behind weaker than any other
    // lock, so it reads as the movement tolerance instead.
    private fun effectivePostActionThresholdPx(): Float = maxOf(postActionThresholdPx, moveThresholdPx)

    /**
     * Lock at the current position with the ordinary tolerance: a physical click,
     * a menu choice, a settings change. The next move past [moveThresholdPx]
     * (or into a different target) re-arms it.
     */
    fun lockUntilMove() {
        locked = true
        lockedAfterAction = false
        anchorX = currentX
        anchorY = currentY
        anchorKey = currentKey
    }

    /**
     * Lock at the current position with the wider post-action distance. [poll]
     * applies this itself when a dwell fires; the fuzz target also drives it
     * directly.
     */
    internal fun lockAfterAction() {
        locked = true
        lockedAfterAction = true
        anchorX = currentX
        anchorY = currentY
        anchorKey = currentKey
    }

    /**
     * Restart the countdown at the same anchor without requiring movement, for
     * repeating actions (scroll): the next dwell period fires again if the
     * pointer keeps resting. Movement re-anchors as usual.
     */
    fun rearmInPlace(nowMs: Long) {
        restStart = nowMs
        locked = false
        lockedAfterAction = false
    }

    /**
     * Suspend dwelling on the app without losing settings. Rests on a keyed
     * target (the menu) still fire, so the user can reach the resume entry.
     * Locks in both directions: entering pause abandons the running countdown,
     * and leaving it must not resume against time that elapsed while away.
     */
    fun setPaused(value: Boolean) {
        if (value == paused) return
        paused = value
        lockUntilMove()
    }

    /**
     * Evaluate the machine at [nowMs]. If the returned [Poll.clicked] is true the
     * machine has already locked itself (with the post-action distance), so the
     * same rest will not click twice. The firing poll reports zero progress so
     * the countdown ring clears.
     */
    fun poll(nowMs: Long): Poll {
        if (locked) return Poll(0f, false, currentX, currentY)
        // Paused: only a keyed target (a menu cell) may still complete a dwell.
        if (paused && anchorKey == null) return Poll(0f, false, currentX, currentY)
        // A timestamp older than the anchor counts as no elapsed time, and a
        // difference too large for a Long saturates instead of wrapping negative
        // (the fuzz target drives both). The clamp on the divisor keeps progress
        // finite for any dwell time.
        val elapsed = elapsedSince(restStart, nowMs)
        val progress = (elapsed.toFloat() / dwellTimeMs).coerceIn(0f, 1f)
        if (progress >= 1f) {
            lockAfterAction()
            return Poll(0f, clicked = true, x = currentX, y = currentY)
        }
        return Poll(progress, clicked = false, x = currentX, y = currentY)
    }

    private fun startCountdown(x: Float, y: Float, key: Any?, nowMs: Long) {
        anchorX = x
        anchorY = y
        anchorKey = key
        restStart = nowMs
        locked = false
        lockedAfterAction = false
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
