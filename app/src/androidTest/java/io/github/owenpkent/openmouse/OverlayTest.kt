package io.github.owenpkent.openmouse

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.github.owenpkent.openmouse.menu.GestureAction
import io.github.owenpkent.openmouse.settings.OpenMouseSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The overlay's safety contract, on every API level: it spans the display in
 * the one screen-space coordinate system, it never locks out the touchscreen,
 * it follows live settings, and it goes away with the service. No mouse needed.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class OverlayTest {

    private var recorder: Activity? = null

    @Before
    fun setUp() {
        Device.wakeAndUnlock()
        resetSettings()
        if (OpenMouseService.current != null) OpenMouseService.disable()
        OpenMouseService.enable()
    }

    @After
    fun tearDown() {
        recorder?.finish()
        if (OpenMouseService.current != null) OpenMouseService.disable()
        resetSettings()
    }

    @Test
    fun overlaySpansTheWholeDisplayIncludingTheCutout() {
        // Read in-process from the attached view: the same on every release,
        // unlike dumpsys, whose window description changes between them.
        val params = waitFor("the overlay window") { overlayParams() }
        assertEquals("overlay type", WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.width)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.height)
        assertTrue("overlay is focusable and would steal keys", params.hasFlag(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Without this every injected tap lands at a fixed offset from the
            // cross-hair on a display with a cutout.
            assertEquals(
                "cutout mode is not ALWAYS",
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
                params.layoutInDisplayCutoutMode,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertTrue("API 34+ overlay must never be touchable", params.hasFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE))
        }

        // Where the window manager actually put it, not only what was asked
        // for: the origin must be the screen origin and it must cover the
        // display, or drawing and dispatchGesture disagree.
        val size = Device.displaySize()
        val frame = waitFor("the overlay to be laid out") {
            Device.onMain {
                val view = OpenMouseService.current?.overlayView ?: return@onMain null
                if (view.width == 0) return@onMain null
                val at = IntArray(2).also { view.getLocationOnScreen(it) }
                listOf(at[0], at[1], at[0] + view.width, at[1] + view.height)
            }
        }
        assertEquals("overlay frame [l, t, r, b]", listOf(0, 0, size.width, size.height), frame)
    }

    @Test
    fun modernInputAsksTheSystemForMouseEvents() {
        // The mouse-driven tests call onMotionEvent directly, so they would not
        // notice if the service stopped asking the system for mouse events.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val info = Device.onMain { OpenMouseService.current!!.serviceInfo }
        assertTrue(
            "FLAG_SEND_MOTION_EVENTS is not set",
            info.flags and AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS != 0,
        )
        assertEquals(InputDevice.SOURCE_MOUSE, info.motionEventSources)
    }

    @Test
    fun fingerTapReachesTheAppUnderTheOverlay() {
        recorder = launchTouchRecorder()
        val size = Device.displaySize()
        val x = size.width / 4
        val y = size.height * 2 / 5

        // API 34+: the overlay is never touchable, so the first tap must land.
        // API 24-33: the overlay captures the mouse, and the idle watchdog drops
        // touchability once no mouse has been seen for a while, so a finger tap
        // must get through within that window. Either way, never a lockout.
        val lockedOut = "a finger tap to reach the app (the touchscreen is locked out)"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // One tap, with generous delivery time for a slow emulator.
            Device.shell("input tap $x $y")
            waitFor(lockedOut, 5_000) { touchDowns().firstOrNull() }
        } else {
            val deadline = SystemClock.uptimeMillis() + 15_000L
            while (touchDowns().isEmpty() && SystemClock.uptimeMillis() < deadline) {
                Device.shell("input tap $x $y")
                SystemClock.sleep(500)
            }
            waitFor(lockedOut, 2_000) { touchDowns().firstOrNull() }
        }
        val down = touchDowns().first()
        assertEquals(x.toFloat(), down.x, 1.5f)
        assertEquals(y.toFloat(), down.y, 1.5f)
    }

    @Test
    fun menuSideSettingMovesTheMenuLive() {
        val service = OpenMouseService.current!!
        val width = Device.displaySize().width
        val right = waitFor("the menu toggle") { service.centerOf(GestureAction.TOGGLE) }
        assertTrue("menu should start on the right", right.x > width / 2f)

        OpenMouseSettings(Device.context).menuOnRight = false
        Device.instrumentation.waitForIdleSync()

        waitUntil("the menu to dock left") {
            service.centerOf(GestureAction.TOGGLE)?.let { it.x < width / 2f } == true
        }
    }

    @Test
    fun disablingTheServiceRemovesTheOverlay() {
        val view = waitFor("the overlay to attach") {
            Device.onMain { OpenMouseService.current?.overlayView?.takeIf { it.isAttachedToWindow } }
        }
        OpenMouseService.disable()
        waitUntil("the overlay window to go away") { Device.onMain { !view.isAttachedToWindow } }
        assertFalse(OpenMouseService.isEnabledInSettings())
    }

    private fun WindowManager.LayoutParams.hasFlag(flag: Int) = flags and flag != 0
}
