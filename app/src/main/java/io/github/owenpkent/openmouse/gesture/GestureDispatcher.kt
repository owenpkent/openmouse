package io.github.owenpkent.openmouse.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * Turns cursor actions into real input by way of
 * [AccessibilityService.dispatchGesture] -- the only stock-Android API that can
 * inject taps and swipes into other apps without root. It works only because
 * the service declares canPerformGestures="true" in its config.
 *
 * Every method takes an [onFinished] callback that runs once the gesture
 * completes, is cancelled, or is refused by the platform. Callers rely on it to
 * undo the overlay passthrough set up around each gesture (see
 * MouseAccessibilityService.runGesture).
 */
class GestureDispatcher(private val service: AccessibilityService) {

    /** Inject a single tap at screen coordinates ([x], [y]). */
    fun tap(x: Float, y: Float, onFinished: () -> Unit = {}) {
        val stroke = pathStroke(Path().apply { moveTo(x, y) }, TAP_DURATION_MS)
        dispatch(GestureDescription.Builder().addStroke(stroke).build(), onFinished)
    }

    /** Inject two quick taps at ([x], [y]). */
    fun doubleTap(x: Float, y: Float, onFinished: () -> Unit = {}) {
        val first = pathStroke(Path().apply { moveTo(x, y) }, TAP_DURATION_MS, startTime = 0L)
        val second = pathStroke(Path().apply { moveTo(x, y) }, TAP_DURATION_MS, startTime = DOUBLE_TAP_GAP_MS)
        dispatch(GestureDescription.Builder().addStroke(first).addStroke(second).build(), onFinished)
    }

    /** Inject a long press (press-and-hold) at ([x], [y]). */
    fun longPress(x: Float, y: Float, onFinished: () -> Unit = {}) {
        val stroke = pathStroke(Path().apply { moveTo(x, y) }, LONG_PRESS_DURATION_MS)
        dispatch(GestureDescription.Builder().addStroke(stroke).build(), onFinished)
    }

    /** Slow drag from ([x1], [y1]) to ([x2], [y2]) -- carries content or a handle. */
    fun drag(x1: Float, y1: Float, x2: Float, y2: Float, onFinished: () -> Unit = {}) {
        line(x1, y1, x2, y2, DRAG_DURATION_MS, onFinished)
    }

    /** Fast swipe / fling from ([x1], [y1]) to ([x2], [y2]). */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, onFinished: () -> Unit = {}) {
        line(x1, y1, x2, y2, SWIPE_DURATION_MS, onFinished)
    }

    /**
     * Scroll the content at ([x], [y]). [revealAbove] true scrolls toward the
     * top of the content (finger drags down); false scrolls toward the bottom.
     * The stroke is clamped to the display so it stays on-screen near the edges.
     */
    fun scroll(x: Float, y: Float, revealAbove: Boolean, onFinished: () -> Unit = {}) {
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
        line(x, fromY, x, toY, SCROLL_DURATION_MS, onFinished)
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long, onFinished: () -> Unit) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatch(GestureDescription.Builder().addStroke(pathStroke(path, duration)).build(), onFinished)
    }

    private fun pathStroke(path: Path, duration: Long, startTime: Long = 0L) =
        GestureDescription.StrokeDescription(path, startTime, duration)

    private fun dispatch(gesture: GestureDescription, onFinished: () -> Unit) {
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onFinished()
            override fun onCancelled(gestureDescription: GestureDescription?) = onFinished()
        }
        val dispatched = service.dispatchGesture(gesture, callback, null)
        // dispatchGesture returns false if the gesture could not be queued at
        // all; the callback never fires in that case, so finish here instead.
        if (!dispatched) onFinished()
    }

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
