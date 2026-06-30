package io.github.owenpkent.openmouse.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * Turns cursor actions into real input by way of
 * [AccessibilityService.dispatchGesture] -- the only stock-Android API that can
 * inject taps and swipes into other apps without root. It works only because
 * the service declares canPerformGestures="true" in its config.
 */
class GestureDispatcher(private val service: AccessibilityService) {

    /**
     * Inject a single tap at screen coordinates ([x], [y]).
     *
     * [onFinished] runs once the gesture completes, is cancelled, or is refused
     * by the platform. Callers rely on it to undo any temporary state set up
     * around the tap (see MouseAccessibilityService.performTap).
     */
    fun tap(x: Float, y: Float, onFinished: () -> Unit = {}) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

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
    }
}
