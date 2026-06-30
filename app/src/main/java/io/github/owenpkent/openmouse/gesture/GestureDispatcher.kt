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
        val stroke = pointStroke(x, y, startTime = 0L, duration = TAP_DURATION_MS)
        dispatch(GestureDescription.Builder().addStroke(stroke).build(), onFinished)
    }

    /** Inject two quick taps at ([x], [y]). */
    fun doubleTap(x: Float, y: Float, onFinished: () -> Unit = {}) {
        val first = pointStroke(x, y, startTime = 0L, duration = TAP_DURATION_MS)
        val second = pointStroke(x, y, startTime = DOUBLE_TAP_GAP_MS, duration = TAP_DURATION_MS)
        dispatch(GestureDescription.Builder().addStroke(first).addStroke(second).build(), onFinished)
    }

    /** Inject a long press (press-and-hold) at ([x], [y]). */
    fun longPress(x: Float, y: Float, onFinished: () -> Unit = {}) {
        val stroke = pointStroke(x, y, startTime = 0L, duration = LONG_PRESS_DURATION_MS)
        dispatch(GestureDescription.Builder().addStroke(stroke).build(), onFinished)
    }

    private fun pointStroke(
        x: Float,
        y: Float,
        startTime: Long,
        duration: Long,
    ): GestureDescription.StrokeDescription {
        val path = Path().apply { moveTo(x, y) }
        return GestureDescription.StrokeDescription(path, startTime, duration)
    }

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
    }
}
