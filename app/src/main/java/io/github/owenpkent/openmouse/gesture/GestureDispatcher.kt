package io.github.owenpkent.openmouse.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * A gesture ready to inject, with where each of its strokes starts (x, y pairs
 * in stroke order). The start points are what lets the legacy overlay match a
 * touch it swallowed to the stroke it came from (see SwallowedGestureTracker).
 */
class Injection(val gesture: GestureDescription, val strokeStarts: FloatArray)

/**
 * Turns cursor actions into real input by way of
 * [AccessibilityService.dispatchGesture] -- the only stock-Android API that can
 * inject taps and swipes into other apps without root. It works only because
 * the service declares canPerformGestures="true" in its config.
 *
 * Each action method only builds an [Injection]; [dispatch] sends it. The
 * service sends every one through MouseAccessibilityService.runGesture, which
 * sets up the overlay passthrough around it and may send it again if the
 * legacy overlay swallowed it.
 */
class GestureDispatcher(private val service: AccessibilityService) {

    /** A single tap at screen coordinates ([x], [y]). */
    fun tap(x: Float, y: Float): Injection =
        single(pathStroke(Path().apply { moveTo(x, y) }, TAP_DURATION_MS), x, y)

    /** Two quick taps at ([x], [y]). */
    fun doubleTap(x: Float, y: Float): Injection {
        val first = pathStroke(Path().apply { moveTo(x, y) }, TAP_DURATION_MS, startTime = 0L)
        val second = pathStroke(Path().apply { moveTo(x, y) }, TAP_DURATION_MS, startTime = DOUBLE_TAP_GAP_MS)
        return Injection(
            GestureDescription.Builder().addStroke(first).addStroke(second).build(),
            floatArrayOf(x, y, x, y),
        )
    }

    /** A long press (press-and-hold) at ([x], [y]). */
    fun longPress(x: Float, y: Float): Injection =
        single(pathStroke(Path().apply { moveTo(x, y) }, LONG_PRESS_DURATION_MS), x, y)

    /** Slow drag from ([x1], [y1]) to ([x2], [y2]) -- carries content or a handle. */
    fun drag(x1: Float, y1: Float, x2: Float, y2: Float): Injection =
        line(x1, y1, x2, y2, DRAG_DURATION_MS)

    /** Fast swipe / fling from ([x1], [y1]) to ([x2], [y2]). */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Injection =
        line(x1, y1, x2, y2, SWIPE_DURATION_MS)

    /**
     * Scroll the content at ([x], [y]). [revealAbove] true scrolls toward the
     * top of the content (finger drags down); false scrolls toward the bottom.
     * The stroke is clamped to the display so it stays on-screen near the edges.
     */
    fun scroll(x: Float, y: Float, revealAbove: Boolean): Injection {
        val dm = service.resources.displayMetrics
        val dist = SCROLL_DISTANCE_DP * dm.density
        val maxY = dm.heightPixels.toFloat()

        // Keep the full stroke length: if the stroke would run off an edge, shift
        // the whole thing inward rather than clamping one end (which would shorten
        // or null the scroll exactly where the user scrolls most, near the edges).
        var top = y - dist / 2f
        var bottom = y + dist / 2f
        if (top < 0f) { bottom -= top; top = 0f }
        if (bottom > maxY) { top -= bottom - maxY; bottom = maxY }
        top = top.coerceAtLeast(0f)
        bottom = bottom.coerceAtMost(maxY)

        // To reveal content above, the finger drags downward (top -> bottom).
        val fromY = if (revealAbove) top else bottom
        val toY = if (revealAbove) bottom else top
        return line(x, fromY, x, toY, SCROLL_DURATION_MS)
    }

    /**
     * Inject [injection]. [onFinished] runs once it completes, is cancelled, or
     * is refused by the platform; the caller relies on it to undo the overlay
     * passthrough set up around the gesture.
     */
    fun dispatch(injection: Injection, onFinished: () -> Unit) {
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onFinished()
            override fun onCancelled(gestureDescription: GestureDescription?) = onFinished()
        }
        val dispatched = service.dispatchGesture(injection.gesture, callback, null)
        // dispatchGesture returns false if the gesture could not be queued at
        // all; the callback never fires in that case, so finish here instead.
        if (!dispatched) onFinished()
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Injection {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return single(pathStroke(path, duration), x1, y1)
    }

    private fun single(stroke: GestureDescription.StrokeDescription, x: Float, y: Float) =
        Injection(GestureDescription.Builder().addStroke(stroke).build(), floatArrayOf(x, y))

    private fun pathStroke(path: Path, duration: Long, startTime: Long = 0L) =
        GestureDescription.StrokeDescription(path, startTime, duration)

    companion object {
        // A 1 ms stroke registers as a tap, well under the long-press timeout.
        // Raise this if some apps miss the tap on a particular device.
        private const val TAP_DURATION_MS = 1L

        // Gap between the two taps of a double tap.
        private const val DOUBLE_TAP_GAP_MS = 150L

        // Comfortably past the platform long-press timeout (~400-500 ms).
        private const val LONG_PRESS_DURATION_MS = 700L

        private const val DRAG_DURATION_MS = 600L
        private const val SWIPE_DURATION_MS = 120L
        private const val SCROLL_DURATION_MS = 250L

        // How far a single scroll travels, in dp.
        private const val SCROLL_DISTANCE_DP = 240f
    }
}
