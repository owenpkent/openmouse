package io.github.owenpkent.openmouse

import android.app.Activity
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Size
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import io.github.owenpkent.openmouse.menu.GestureAction
import io.github.owenpkent.openmouse.service.MouseAccessibilityService
import io.github.owenpkent.openmouse.settings.OpenMouseSettings
import io.github.owenpkent.openmouse.testing.TouchLog
import io.github.owenpkent.openmouse.testing.TouchRecorderActivity
import org.junit.Assert.fail

/**
 * Shared plumbing for the instrumented UI tests: shell access, turning the
 * service on and off, resetting settings, and polling.
 */
object Device {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context: Context get() = instrumentation.targetContext

    /**
     * Always ask for this flag. A plain getUiAutomation() connects as an
     * accessibility client that suppresses every other accessibility service,
     * which would silently unbind OpenMouse mid-test.
     */
    private val uiAutomation: UiAutomation
        get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    /** Run a shell command as the shell user and return its stdout. */
    fun shell(command: String): String {
        val pfd = uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    fun screenshot() = uiAutomation.takeScreenshot()

    fun wakeAndUnlock() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        // A cold emulator boot often leaves a "System UI isn't responding" dialog
        // up, which steals focus from the window under test.
        shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
    }

    /** Full display size in pixels, including system bars: the overlay's coordinate space. */
    fun displaySize(): Size {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Size(b.width(), b.height())
        } else {
            val p = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            Size(p.x, p.y)
        }
    }

    fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}

/** Poll [condition] until it returns non-null, or fail after [timeoutMs]. */
fun <T : Any> waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> T?): T {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (true) {
        condition()?.let { return it }
        if (SystemClock.uptimeMillis() > deadline) fail("Timed out after ${timeoutMs}ms waiting for $what")
        SystemClock.sleep(50)
    }
}

fun waitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
    waitFor(what, timeoutMs) { if (condition()) Unit else null }
}

object OpenMouseService {
    private const val PACKAGE = "io.github.owenpkent.openmouse"
    private const val ID = "$PACKAGE/$PACKAGE.service.MouseAccessibilityService"

    val current: MouseAccessibilityService? get() = MouseAccessibilityService.connected?.get()

    /**
     * Enable the accessibility service over the shell and wait for its overlay.
     * Any other enabled service (TalkBack on a developer's phone) is kept.
     */
    fun enable(): MouseAccessibilityService {
        // Android 13+ blocks sideloaded accessibility services until this appop
        // is granted; harmless (an error message) on older releases.
        Device.shell("appops set $PACKAGE ACCESS_RESTRICTED_SETTINGS allow")
        val services = (otherServices() + ID).joinToString(":")
        Device.shell("settings put secure enabled_accessibility_services $services")
        Device.shell("settings put secure accessibility_enabled 1")
        return waitFor("the accessibility service to connect", 15_000) { current }
    }

    /** Disable OpenMouse only, leaving any other enabled service alone. */
    fun disable() {
        val others = otherServices()
        if (others.isEmpty()) {
            Device.shell("settings delete secure enabled_accessibility_services")
        } else {
            Device.shell("settings put secure enabled_accessibility_services ${others.joinToString(":")}")
        }
        waitUntil("the accessibility service to disconnect", 15_000) { current == null }
    }

    private fun otherServices(): List<String> =
        Device.shell("settings get secure enabled_accessibility_services").trim()
            .split(':')
            .filter { it.isNotBlank() && it != "null" && !it.equals(ID, ignoreCase = true) }

    fun isEnabledInSettings(): Boolean =
        Device.shell("settings get secure enabled_accessibility_services").contains(ID)
}

/**
 * Reset every setting to its default, then apply [block]. Call it before
 * enabling the service (which applies settings on connect): clear() notifies
 * preference listeners only from API 30 on.
 */
fun resetSettings(block: OpenMouseSettings.() -> Unit = {}) {
    Device.context.getSharedPreferences(OpenMouseSettings.PREFS_NAME, Context.MODE_PRIVATE)
        .edit().clear().commit()
    OpenMouseSettings(Device.context).block()
    // Preference listeners run on the main thread; let applySettings() land.
    Device.instrumentation.waitForIdleSync()
}

/** Launch the debug-only [TouchRecorderActivity] in front and wait until it is resumed. */
fun launchTouchRecorder(): Activity {
    val intent = Intent(Device.context, TouchRecorderActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    val activity = Device.instrumentation.startActivitySync(intent)
    Device.instrumentation.waitForIdleSync()
    waitUntil("the touch recorder to take focus") { activity.hasWindowFocus() }
    TouchLog.clear()
    return activity
}

/**
 * A synthetic mouse, fed in where real mouse input arrives on this release:
 * - API 34+: [MouseAccessibilityService.onMotionEvent]. The platform filters
 *   shell-injected input out of that callback, hence calling it in-process.
 * - API 24-33: the touchable CursorView overlay, the way the window system
 *   delivers it (hover and wheel as generic motion, a held button as touch).
 *   Everything after that, the passthrough around each injected gesture
 *   included, is the real legacy path.
 *
 * All coordinates are screen pixels. The overlay sits at the screen origin, and
 * for these obtained events rawX == x, so both paths see the same numbers.
 */
class Mouse(private val service: MouseAccessibilityService) {

    private val modernInput = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    var x = 0f
        private set
    var y = 0f
        private set

    fun moveTo(x: Float, y: Float) {
        this.x = x
        this.y = y
        send(MotionEvent.ACTION_HOVER_MOVE, x, y)
    }

    fun moveTo(p: PointF) = moveTo(p.x, p.y)

    /**
     * Approach [p] from below in a few hops, each well past every movement
     * tolerance, then rest there. However the dwell machine was locked (after a
     * click, a menu choice, or leaving a menu entry), it is armed at [p] after
     * this, and the countdown runs from the last hop.
     */
    fun approachAndRest(p: PointF) {
        moveTo(p.x, p.y + 360f)
        moveTo(p.x, p.y + 180f)
        moveTo(p)
    }

    /** Press (and release) the physical primary button where the pointer is. */
    fun clickPrimary() {
        if (modernInput) {
            val press = event(MotionEvent.ACTION_BUTTON_PRESS, x, y, MotionEvent.BUTTON_PRIMARY)
            setActionButton(press, MotionEvent.BUTTON_PRIMARY)
            dispatch(press)
            val release = event(MotionEvent.ACTION_BUTTON_RELEASE, x, y, 0)
            setActionButton(release, MotionEvent.BUTTON_PRIMARY)
            dispatch(release)
        } else {
            // A touchable window receives a held mouse button as a touch stream.
            dispatch(event(MotionEvent.ACTION_DOWN, x, y, MotionEvent.BUTTON_PRIMARY))
            dispatch(event(MotionEvent.ACTION_UP, x, y, 0))
        }
    }

    /** Turn the wheel once at the pointer; [notches] > 0 is wheel-up. */
    fun scroll(notches: Float) = send(MotionEvent.ACTION_SCROLL, x, y, vscroll = notches)

    private fun send(action: Int, x: Float, y: Float, vscroll: Float = 0f) =
        dispatch(event(action, x, y, 0, vscroll))

    private fun dispatch(event: MotionEvent) {
        Device.onMain {
            if (modernInput) {
                service.onMotionEvent(event)
            } else {
                val view = service.overlayView ?: throw AssertionError("the cursor overlay is gone")
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP ->
                        view.dispatchTouchEvent(event)
                    else -> view.dispatchGenericMotionEvent(event)
                }
            }
        }
        event.recycle()
    }

    private fun event(action: Int, x: Float, y: Float, buttonState: Int, vscroll: Float = 0f): MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            if (vscroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
        }
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(
            now, now, action, 1, arrayOf(props), arrayOf(coords),
            0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
    }

    // MotionEvent has no public way to set the button that changed; the setter
    // is a @TestApi, which instrumentation is allowed to call.
    private fun setActionButton(event: MotionEvent, button: Int) {
        try {
            MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
                .invoke(event, button)
        } catch (e: ReflectiveOperationException) {
            // Fail, not skip: a skip would let a broken physical-click path pass CI.
            throw AssertionError("MotionEvent.setActionButton is not reachable here", e)
        }
    }
}

/**
 * Where the menu draws [action], found by asking the service's own hit test, so
 * the tests follow any layout change (screen size, dock side, column count).
 * Null if the entry is not on screen (for example the menu is collapsed).
 */
fun MouseAccessibilityService.centerOf(action: GestureAction): PointF? {
    val size = Device.displaySize()
    return Device.onMain {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -1f
        var maxY = -1f
        // Entries are 84 x 52 dp, so a coarse grid still hits each many times;
        // a fine one holds the main thread (and the dwell ticker on it) for a
        // noticeable while on every poll.
        val step = 12
        for (py in 0 until size.height step step) {
            for (px in 0 until size.width step step) {
                if (menuEntryAt(px.toFloat(), py.toFloat()) == action) {
                    minX = minOf(minX, px.toFloat())
                    minY = minOf(minY, py.toFloat())
                    maxX = maxOf(maxX, px.toFloat())
                    maxY = maxOf(maxY, py.toFloat())
                }
            }
        }
        if (maxX < 0f) null else PointF((minX + maxX) / 2f, (minY + maxY) / 2f)
    }
}

/** The dumpsys block for OpenMouse's overlay window, or null if none is attached. */
fun overlayWindowDump(): String? {
    val dump = Device.shell("dumpsys window windows")
    val blocks = dump.split(Regex("(?m)^\\s*Window #\\d+ "))
    blocks.filter { "ACCESSIBILITY_OVERLAY" in it }
        .forEach { block -> block.lines().take(12).forEach { android.util.Log.w("OMDIAG", "dump: $it") } }
    return blocks.firstOrNull {
        it.contains("io.github.owenpkent.openmouse}") && it.contains("ACCESSIBILITY_OVERLAY")
    }
}

/** Touch DOWNs the recorder has seen. */
fun touchDowns() = TouchLog.snapshot().filter { it.action == MotionEvent.ACTION_DOWN }
