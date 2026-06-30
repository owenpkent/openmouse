package io.github.owenpkent.openmouse.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import io.github.owenpkent.openmouse.click.DwellClicker
import io.github.owenpkent.openmouse.cursor.CursorView
import io.github.owenpkent.openmouse.gesture.GestureDispatcher

/**
 * The OpenMouse engine.
 *
 * On connect it adds a full-screen [CursorView] overlay that captures mouse
 * motion and draws the cross-hair. A [DwellClicker] watches those positions and,
 * when the pointer rests, asks [GestureDispatcher] to inject a tap.
 *
 * Overlay touchability is the one subtlety. The overlay must be touchable to
 * capture the mouse, but a touchable overlay also swallows the synthetic taps we
 * inject. So [performTap] makes the overlay click-through for the duration of
 * the tap, then restores it.
 *
 * Known MVP limitation: because the overlay is touchable while active, finger
 * touch is also captured. On Android 14+ the cleaner path is a non-touchable
 * overlay plus AccessibilityService.onMotionEvent; that is left as a follow-up.
 */
class MouseAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var cursorView: CursorView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var dwellClicker: DwellClicker? = null
    private var gestureDispatcher: GestureDispatcher? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        addCursorOverlay()
    }

    private fun addCursorOverlay() {
        if (cursorView != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val dispatcher = GestureDispatcher(this)
        gestureDispatcher = dispatcher

        val clicker = DwellClicker(
            onProgress = { progress -> cursorView?.setCountdown(progress) },
            onClick = { x, y -> performTap(x, y) },
        )
        dwellClicker = clicker

        val view = CursorView(this)
        view.onMove = { x, y -> clicker.onCursorMoved(x, y) }
        cursorView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        layoutParams = params

        wm.addView(view, params)
        clicker.start()
    }

    /**
     * Inject a tap, briefly making the overlay click-through so the injected
     * event lands on the app below instead of on our own overlay.
     */
    private fun performTap(x: Float, y: Float) {
        setOverlayTouchable(false)
        // Give WindowManager a frame to register the window as non-touchable
        // before the tap is injected, otherwise the overlay can still swallow it.
        handler.postDelayed({
            val dispatcher = gestureDispatcher
            if (dispatcher == null) {
                setOverlayTouchable(true)
            } else {
                dispatcher.tap(x, y) { setOverlayTouchable(true) }
            }
        }, TAP_PASSTHROUGH_DELAY_MS)
    }

    private fun setOverlayTouchable(touchable: Boolean) {
        val params = layoutParams ?: return
        val view = cursorView ?: return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            windowManager?.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            // View is no longer attached (service tearing down); nothing to do.
        }
    }

    private fun removeCursorOverlay() {
        handler.removeCallbacksAndMessages(null)
        dwellClicker?.stop()
        cursorView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: IllegalArgumentException) {
                // Already removed.
            }
        }
        cursorView = null
        layoutParams = null
        dwellClicker = null
        gestureDispatcher = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used: OpenMouse drives input, it does not react to UI events.
    }

    override fun onInterrupt() {
        // No long-running feedback to interrupt.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        removeCursorOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeCursorOverlay()
        super.onDestroy()
    }

    companion object {
        private const val TAP_PASSTHROUGH_DELAY_MS = 24L
    }
}
