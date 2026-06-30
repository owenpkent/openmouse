package io.github.owenpkent.openmouse.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
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
 * It adds a full-screen [CursorView] overlay that draws the cross-hair and the
 * [GestureMenu]. A [DwellClicker] watches the pointer and, when it rests, fires
 * the primary action -- the same action a physical left-click triggers. Both
 * routes funnel through [handlePrimaryAction].
 *
 * Input capture has two paths:
 * - **API 34+ ([modernInput]):** the overlay is permanently non-touchable, so it
 *   never blocks finger touch and never swallows our own injected gestures. Mouse
 *   motion/buttons/scroll arrive through [onMotionEvent]. This is the correct,
 *   safe design.
 * - **API 24-33 (legacy):** the only way to capture the mouse is a touchable
 *   overlay, which has to be made click-through for each injected gesture (see
 *   [runGesture]). A finger source-gate ([CursorView.onTouchEvent]) stops stray
 *   touches from moving the cursor, a gesture watchdog prevents a dropped
 *   callback from freezing the cursor, and an idle watchdog drops touchability
 *   when no mouse is present so the touchscreen can never be locked out.
 */
class MouseAccessibilityService : AccessibilityService() {

    private val modernInput = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

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

    // Legacy passthrough state (unused on the modern path).
    private var gesturesInFlight = 0
    private var lastPointerMs = 0L
    private var captureSuspended = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (modernInput) {
            // Observe mouse motion events instead of capturing them with a
            // touchable window.
            serviceInfo = serviceInfo?.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS
                motionEventSources = InputDevice.SOURCE_MOUSE
            }
        }
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
        // Legacy capture path: CursorView reports mouse events to these.
        view.onMove = { x, y -> onPointerMoved(x, y, draw = false) }
        view.onPrimaryButton = { x, y -> handlePrimaryAction(x, y) }
        view.onScroll = { x, y, v -> handleScroll(x, y, v) }
        cursorView = view

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (modernInput) {
            // Non-touchable: touch passes through, injected gestures pass through.
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Span the whole display, including any cutout, so the overlay's
            // origin is the screen origin and view coordinates equal screen
            // coordinates for both drawing and dispatchGesture.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        layoutParams = params

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            // Adding the overlay failed; tear down so a later connect can retry.
            cursorView = null
            prefs.unregisterListener(settingsListener)
            settings = null
            return
        }

        applySettings()

        if (!modernInput) {
            lastPointerMs = SystemClock.uptimeMillis()
            handler.postDelayed(idleWatchdog, WATCHDOG_INTERVAL_MS)
        }
    }

    /** API 34+ mouse stream. */
    override fun onMotionEvent(event: MotionEvent) {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return
        val x = event.rawX
        val y = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_MOVE,
            -> onPointerMoved(x, y, draw = true)

            MotionEvent.ACTION_BUTTON_PRESS -> {
                onPointerMoved(x, y, draw = true)
                if ((event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0) {
                    handlePrimaryAction(x, y)
                }
            }

            MotionEvent.ACTION_SCROLL -> {
                onPointerMoved(x, y, draw = true)
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) handleScroll(x, y, v)
            }
        }
    }

    private fun onPointerMoved(x: Float, y: Float, draw: Boolean) {
        lastPointerMs = SystemClock.uptimeMillis()
        if (draw) cursorView?.setCursorPosition(x, y)
        dwellClicker?.onCursorMoved(x, y)
    }

    /**
     * Push the current settings onto the live components. Runs on connect and on
     * any preference change (via [settingsListener], on the main thread).
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
    }

    private fun onMenuAction(menu: GestureMenu, action: GestureAction) {
        when (action) {
            GestureAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            GestureAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            GestureAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            GestureAction.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            GestureAction.QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            GestureAction.TOGGLE -> menu.toggleExpanded()
            else -> menu.currentMode = action // a selectable tap/drag/scroll mode
        }
        // Any menu choice cancels a half-started two-point gesture.
        clearPending()
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

    private fun handleScroll(x: Float, y: Float, vscroll: Float) {
        val dispatcher = gestureDispatcher ?: return
        // Wheel-up (vscroll > 0) reveals content above.
        runGesture { done -> dispatcher.scroll(x, y, revealAbove = vscroll > 0f, done) }
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
     * Inject a gesture. On the modern path the overlay is already non-touchable,
     * so we dispatch directly. On the legacy path we make the overlay
     * click-through for the duration of the gesture and restore it afterward,
     * ref-counted so overlapping gestures cannot restore it early, with a
     * watchdog so a dropped callback cannot leave it stuck non-touchable.
     */
    private fun runGesture(dispatch: (onFinished: () -> Unit) -> Unit) {
        if (modernInput) {
            dispatch { }
            return
        }
        beginPassthrough()
        // Give WindowManager a frame to register the window as non-touchable
        // before the gesture is injected, otherwise the overlay can swallow it.
        handler.postDelayed({
            dispatch { endPassthrough() }
        }, TAP_PASSTHROUGH_DELAY_MS)
    }

    private fun beginPassthrough() {
        gesturesInFlight++
        setOverlayTouchable(false)
        handler.removeCallbacks(passthroughWatchdog)
        handler.postDelayed(passthroughWatchdog, MAX_GESTURE_MS)
    }

    private fun endPassthrough() {
        gesturesInFlight = (gesturesInFlight - 1).coerceAtLeast(0)
        if (gesturesInFlight == 0) {
            handler.removeCallbacks(passthroughWatchdog)
            setOverlayTouchable(true)
        }
    }

    // Force the overlay touchable again if a gesture callback was dropped, so the
    // cursor can never get stuck unable to capture the mouse.
    private val passthroughWatchdog = Runnable {
        gesturesInFlight = 0
        setOverlayTouchable(true)
    }

    // Legacy lockout guard: if no mouse has been seen for a while, drop overlay
    // touchability so the bare touchscreen works; sample periodically to detect a
    // returning mouse and re-arm.
    private val idleWatchdog = object : Runnable {
        override fun run() {
            if (gesturesInFlight == 0) {
                val idle = SystemClock.uptimeMillis() - lastPointerMs > IDLE_SUSPEND_MS
                if (idle && !captureSuspended) {
                    captureSuspended = true
                    setOverlayTouchable(false)
                } else if (captureSuspended) {
                    val sampleStart = SystemClock.uptimeMillis()
                    setOverlayTouchable(true)
                    handler.postDelayed({
                        if (lastPointerMs >= sampleStart) {
                            captureSuspended = false // mouse came back; stay active
                        } else if (captureSuspended) {
                            setOverlayTouchable(false) // still gone; suspend again
                        }
                    }, SAMPLE_MS)
                }
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
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
        gesturesInFlight = 0
        captureSuspended = false
        windowManager = null
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
        private const val MAX_GESTURE_MS = 1500L
        private const val WATCHDOG_INTERVAL_MS = 2500L
        private const val IDLE_SUSPEND_MS = 6000L
        private const val SAMPLE_MS = 300L
    }
}
