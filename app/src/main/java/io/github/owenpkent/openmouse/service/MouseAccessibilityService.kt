package io.github.owenpkent.openmouse.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
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
import io.github.owenpkent.openmouse.settings.OpenMouseSettings

/**
 * The OpenMouse engine.
 *
 * On connect it adds a full-screen [CursorView] overlay that captures mouse
 * motion and draws the cross-hair plus the [GestureMenu]. A [DwellClicker]
 * watches positions and, when the pointer rests, fires the primary action --
 * the same action a physical left-click triggers. Both routes funnel through
 * [handlePrimaryAction], which selects a menu entry or performs the current
 * gesture via [GestureDispatcher].
 *
 * Gesture modes:
 * - TAP / DOUBLE_TAP / LONG_PRESS act at one point.
 * - DRAG / SWIPE are two-point: the first action sets the start, the second the
 *   end (see [pendingX]/[pendingY]).
 * - SCROLL_UP / SCROLL_DOWN scroll at the cursor and stay selected so the user
 *   can repeat them; the one-shot modes revert to TAP after a single use.
 *
 * Overlay touchability is the one subtlety. The overlay must be touchable to
 * capture the mouse, but a touchable overlay also swallows the synthetic
 * gestures we inject. So [runGesture] makes the overlay click-through for the
 * duration of the gesture, then restores it.
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

    private var settings: OpenMouseSettings? = null
    private val settingsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applySettings() }

    // Start point of an in-progress two-point gesture (drag/swipe), or null.
    private var pendingX: Float? = null
    private var pendingY: Float? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        addCursorOverlay()
    }

    private fun addCursorOverlay() {
        if (cursorView != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val prefs = OpenMouseSettings(this)
        settings = prefs
        prefs.registerListener(settingsListener)

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
        applySettings()
    }

    /**
     * Push the current settings onto the live components. Safe to call any time;
     * it runs on connect and again whenever a preference changes (via
     * [settingsListener], delivered on the main thread in-process).
     */
    private fun applySettings() {
        val prefs = settings ?: return
        val density = resources.displayMetrics.density

        dwellClicker?.configure(prefs.dwellTimeMs, prefs.moveThresholdDp * density)
        cursorView?.setCursorScale(prefs.cursorScale)
        cursorView?.setCursorColor(prefs.cursorColor)
        gestureMenu?.setDockRight(prefs.menuOnRight)

        // Dwell click can be turned off entirely (physical-button clicks still work).
        if (prefs.dwellEnabled) dwellClicker?.start() else dwellClicker?.stop()

        cursorView?.invalidate()
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
            performMode(menu?.currentMode ?: GestureAction.TAP, x, y)
        }
        // Don't let a resting mouse immediately fire again; require a move first.
        dwellClicker?.lockUntilMove()
        cursorView?.invalidate()
    }

    private fun onMenuAction(menu: GestureMenu, action: GestureAction) {
        when (action) {
            GestureAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            GestureAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            GestureAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            GestureAction.TOGGLE -> menu.toggleExpanded()
            else -> {
                // Any selectable mode. Switching modes cancels a pending drag.
                menu.currentMode = action
                clearPending()
            }
        }
    }

    private fun performMode(mode: GestureAction, x: Float, y: Float) {
        val dispatcher = gestureDispatcher ?: return
        when (mode) {
            GestureAction.TAP -> runGesture { done -> dispatcher.tap(x, y, done) }

            GestureAction.DOUBLE_TAP -> {
                runGesture { done -> dispatcher.doubleTap(x, y, done) }
                resetToTap()
            }

            GestureAction.LONG_PRESS -> {
                runGesture { done -> dispatcher.longPress(x, y, done) }
                resetToTap()
            }

            GestureAction.DRAG, GestureAction.SWIPE -> performTwoPoint(mode, x, y)

            GestureAction.SCROLL_UP ->
                runGesture { done -> dispatcher.scroll(x, y, revealAbove = true, done) }

            GestureAction.SCROLL_DOWN ->
                runGesture { done -> dispatcher.scroll(x, y, revealAbove = false, done) }

            else -> Unit // navigation actions never reach here
        }
    }

    /**
     * Two-point gestures: the first call records the start (and shows a marker),
     * the second performs the gesture and reverts to a plain tap.
     */
    private fun performTwoPoint(mode: GestureAction, x: Float, y: Float) {
        val startX = pendingX
        val startY = pendingY
        if (startX == null || startY == null) {
            pendingX = x
            pendingY = y
            cursorView?.setPendingPoint(x, y)
            return
        }
        val dispatcher = gestureDispatcher
        if (dispatcher != null) {
            if (mode == GestureAction.DRAG) {
                runGesture { done -> dispatcher.drag(startX, startY, x, y, done) }
            } else {
                runGesture { done -> dispatcher.swipe(startX, startY, x, y, done) }
            }
        }
        clearPending()
        resetToTap()
    }

    private fun resetToTap() {
        gestureMenu?.currentMode = GestureAction.TAP
    }

    private fun clearPending() {
        pendingX = null
        pendingY = null
        cursorView?.clearPendingPoint()
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
        settings?.unregisterListener(settingsListener)
        settings = null
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
        pendingX = null
        pendingY = null
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
