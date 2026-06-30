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
import io.github.owenpkent.openmouse.menu.GestureAction
import io.github.owenpkent.openmouse.menu.GestureMenu

/**
 * The OpenMouse engine.
 *
 * On connect it adds a full-screen [CursorView] overlay that captures mouse
 * motion and draws the cross-hair plus the [GestureMenu]. A [DwellClicker]
 * watches positions and, when the pointer rests, fires the primary action --
 * the same action a physical left-click triggers. Both routes funnel through
 * [handlePrimaryAction], which either selects a menu entry or performs the
 * current gesture via [GestureDispatcher].
 *
 * Overlay touchability is the one subtlety. The overlay must be touchable to
 * capture the mouse, but a touchable overlay also swallows the synthetic taps we
 * inject. So [runGesture] makes the overlay click-through for the duration of
 * the gesture, then restores it.
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
    private var gestureMenu: GestureMenu? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        addCursorOverlay()
    }

    private fun addCursorOverlay() {
        if (cursorView != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        gestureDispatcher = GestureDispatcher(this)
        val menu = GestureMenu(this)
        gestureMenu = menu

        val clicker = DwellClicker(
            onProgress = { progress -> cursorView?.setCountdown(progress) },
            onClick = { x, y -> handlePrimaryAction(x, y) },
        )
        dwellClicker = clicker

        val view = CursorView(this)
        view.gestureMenu = menu
        view.onMove = { x, y -> clicker.onCursorMoved(x, y) }
        view.onPrimaryButton = { x, y -> handlePrimaryAction(x, y) }
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
     * The single entry point for "the user clicked here", whether by dwell or by
     * pressing the physical button. If the cursor is over a menu entry, handle
     * the menu; otherwise perform the current gesture at the target.
     */
    private fun handlePrimaryAction(x: Float, y: Float) {
        val menu = gestureMenu
        val hit = menu?.hitTest(x, y)
        if (menu != null && hit != null) {
            onMenuAction(menu, hit)
        } else {
            val mode = menu?.currentMode ?: GestureAction.TAP
            performGesture(mode, x, y)
            // Non-tap modes are one-shot: revert to plain tap after use.
            if (mode != GestureAction.TAP) menu?.currentMode = GestureAction.TAP
        }
        // Whether we acted on the menu or the screen, don't let a resting mouse
        // immediately fire again; require a move first.
        dwellClicker?.lockUntilMove()
        cursorView?.invalidate()
    }

    private fun onMenuAction(menu: GestureMenu, action: GestureAction) {
        when (action) {
            GestureAction.TAP,
            GestureAction.DOUBLE_TAP,
            GestureAction.LONG_PRESS,
            -> menu.currentMode = action

            GestureAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            GestureAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            GestureAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            GestureAction.TOGGLE -> menu.toggleExpanded()
        }
    }

    private fun performGesture(mode: GestureAction, x: Float, y: Float) {
        val dispatcher = gestureDispatcher ?: return
        when (mode) {
            GestureAction.TAP -> runGesture { done -> dispatcher.tap(x, y, done) }
            GestureAction.DOUBLE_TAP -> runGesture { done -> dispatcher.doubleTap(x, y, done) }
            GestureAction.LONG_PRESS -> runGesture { done -> dispatcher.longPress(x, y, done) }
            else -> Unit // navigation actions never reach here
        }
    }

    /**
     * Inject a gesture, briefly making the overlay click-through so the injected
     * events land on the app below instead of on our own overlay. [dispatch]
     * must invoke its callback when the gesture finishes so touchability is
     * restored.
     */
    private fun runGesture(dispatch: (onFinished: () -> Unit) -> Unit) {
        setOverlayTouchable(false)
        // Give WindowManager a frame to register the window as non-touchable
        // before the gesture is injected, otherwise the overlay can swallow it.
        handler.postDelayed({
            dispatch { setOverlayTouchable(true) }
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
        gestureMenu = null
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
