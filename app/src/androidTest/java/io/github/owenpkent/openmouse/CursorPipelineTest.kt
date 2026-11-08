package io.github.owenpkent.openmouse

import android.app.Activity
import android.graphics.Color
import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.github.owenpkent.openmouse.menu.GestureAction
import io.github.owenpkent.openmouse.service.MouseAccessibilityService
import io.github.owenpkent.openmouse.settings.OpenMouseSettings
import io.github.owenpkent.openmouse.testing.TouchLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * End-to-end tests of the cursor pipeline on a real device or emulator: a
 * synthetic [Mouse] feeds the service where real mouse input arrives on this
 * release (`onMotionEvent` on API 34+, the touchable CursorView before that),
 * and the real dwell timer, menu hit test, `dispatchGesture` injection and
 * overlay all run. The touches are read back from a real app window
 * ([TouchLog]), so these check that what the cross-hair points at is exactly
 * what gets touched. On API 24-33 every injected gesture also proves the
 * overlay went click-through for it: otherwise the overlay swallows it.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class CursorPipelineTest {

    private lateinit var service: MouseAccessibilityService
    private lateinit var mouse: Mouse
    private lateinit var recorder: Activity

    // An app point well clear of the menu, which docks on the right edge.
    private lateinit var app: PointF

    @Before
    fun setUp() {
        Device.wakeAndUnlock()
        resetSettings { dwellTimeMs = DWELL_MS.toLong() }
        // A fresh connect per test, so menu state (expanded, mode, paused) and the
        // dwell lock never leak from one test into the next.
        if (OpenMouseService.current != null) OpenMouseService.disable()
        service = OpenMouseService.enable()
        mouse = Mouse(service)
        recorder = launchTouchRecorder()

        val size = Device.displaySize()
        app = PointF(size.width * 0.25f, size.height * 0.4f)
    }

    @After
    fun tearDown() {
        if (::recorder.isInitialized) recorder.finish()
        if (OpenMouseService.current != null) OpenMouseService.disable()
        resetSettings()
    }

    @Test
    fun dwellTapLandsExactlyUnderTheCrossHair() {
        mouse.approachAndRest(app)

        val down = waitFor("the dwell tap", TIMEOUT_MS) { touchDowns().firstOrNull() }
        assertNear(app, down.x, down.y)
        waitUntil("the tap to lift") { TouchLog.snapshot().any { it.action == MotionEvent.ACTION_UP } }

        // Resting on: a dwell click must not repeat until the pointer moves away.
        SystemClock.sleep(DWELL_MS * 3L)
        assertEquals("a resting pointer clicked again", 1, touchDowns().size)
    }

    @Test
    fun consecutiveTapsEachLand() {
        // On API 24-33 the overlay must go click-through for each gesture and
        // come back afterwards; a passthrough that never ended (or never began
        // again) would drop the second tap.
        val size = Device.displaySize()
        val second = PointF(size.width * 0.4f, size.height * 0.6f)

        mouse.approachAndRest(app)
        waitUntil("the first tap", TIMEOUT_MS) { touchDowns().size == 1 }
        mouse.approachAndRest(second)
        waitUntil("the second tap", TIMEOUT_MS) { touchDowns().size == 2 }

        val (a, b) = touchDowns()
        assertNear(app, a.x, a.y)
        assertNear(second, b.x, b.y)
    }

    @Test
    fun legacyOverlayCapturesTheMouseAgainAfterAGesture() {
        assumeTrue(
            "API 34+ never makes the overlay touchable",
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        )
        mouse.approachAndRest(app)
        waitFor("the dwell tap", TIMEOUT_MS) { touchDowns().firstOrNull() }
        // Keep the mouse "present" so the idle watchdog has no reason to drop
        // touchability; then the overlay must be touchable again once the
        // gesture's passthrough ends, or the mouse would stop reaching it.
        waitUntil("the overlay to be touchable again", TIMEOUT_MS) {
            mouse.moveTo(app.x + 1f, app.y)
            overlayWindowDump()?.let { "NOT_TOUCHABLE" !in it } == true
        }
    }

    @Test
    fun smallTremorDoesNotRestartTheCountdown() {
        mouse.approachAndRest(app)
        // Jitter inside the movement tolerance (8 dp by default) for longer than
        // the dwell time: the click still lands, at the rest point.
        val d = Device.context.resources.displayMetrics.density
        val start = SystemClock.uptimeMillis()
        var i = 0
        while (touchDowns().isEmpty() && SystemClock.uptimeMillis() - start < TIMEOUT_MS) {
            val off = if (i++ % 2 == 0) 3f * d else -3f * d
            mouse.moveTo(app.x + off, app.y - off)
            SystemClock.sleep(40)
        }
        // It must fire while the tremor is still going on. If every jitter
        // restarted the countdown, the loop would run out with nothing fired.
        val down = touchDowns().firstOrNull()
            ?: throw AssertionError("a small tremor kept restarting the dwell countdown")
        assertNear(app, down.x, down.y, tolerance = 4f * d)
    }

    @Test
    fun withDwellOffRestingNeverClicksButThePhysicalButtonDoes() {
        OpenMouseSettings(Device.context).dwellEnabled = false
        Device.instrumentation.waitForIdleSync()

        mouse.approachAndRest(app)
        SystemClock.sleep(DWELL_MS * 3L)
        assertTrue("dwell is off but a rest clicked", touchDowns().isEmpty())

        mouse.clickPrimary()
        val down = waitFor("the physical click", TIMEOUT_MS) { touchDowns().firstOrNull() }
        assertNear(app, down.x, down.y)
    }

    @Test
    fun physicalClickTapsAtOnceAndLocksTheDwell() {
        mouse.approachAndRest(app)
        val clickedAt = SystemClock.uptimeMillis()
        mouse.clickPrimary()
        val down = waitFor("the physical click", TIMEOUT_MS) { touchDowns().firstOrNull() }
        assertNear(app, down.x, down.y)
        // Event times share the uptime clock: the tap came from the click, not
        // from a dwell that happened to complete afterwards.
        assertTrue(
            "the tap came ${down.timeMs - clickedAt}ms after the click, as late as a dwell",
            down.timeMs - clickedAt < DWELL_MS,
        )

        // The click locked the machine, so the rest that follows adds nothing.
        SystemClock.sleep(DWELL_MS * 3L)
        assertEquals(1, touchDowns().size)
    }

    @Test
    fun menuHomeLeavesTheAppWithoutTouchingIt() {
        openMenu()
        dwellOnMenu(GestureAction.HOME)

        waitUntil("the launcher to come to the front", TIMEOUT_MS) { !recorder.hasWindowFocus() }
        assertTrue("a menu choice leaked a touch into the app", touchDowns().isEmpty())
    }

    @Test
    fun pauseStopsDwellOnTheAppButTheMenuStillWorks() {
        openMenu()
        dwellOnMenu(GestureAction.PAUSE)
        waitUntil("dwell to pause", TIMEOUT_MS) { Device.onMain { service.isPaused } }

        mouse.approachAndRest(app)
        SystemClock.sleep(DWELL_MS * 3L)
        assertTrue("paused, yet a dwell reached the app", touchDowns().isEmpty())

        // Dwelling still works on the menu, which is how a user who cannot click
        // gets back: the same entry now reads Resume.
        dwellOnMenu(GestureAction.PAUSE)
        waitUntil("dwell to resume", TIMEOUT_MS) { Device.onMain { !service.isPaused } }

        mouse.approachAndRest(app)
        val down = waitFor("a dwell tap after resuming", TIMEOUT_MS) { touchDowns().firstOrNull() }
        assertNear(app, down.x, down.y)
    }

    @Test
    fun doubleTapModeTapsTwiceThenRevertsToTap() {
        openMenu()
        dwellOnMenu(GestureAction.DOUBLE_TAP)
        waitUntil("double-tap mode", TIMEOUT_MS) { Device.onMain { service.currentMode } == GestureAction.DOUBLE_TAP }

        mouse.approachAndRest(app)
        waitUntil("two taps", TIMEOUT_MS) { touchDowns().size >= 2 }
        touchDowns().forEach { assertNear(app, it.x, it.y) }
        assertEquals(GestureAction.TAP, Device.onMain { service.currentMode })
    }

    @Test
    fun dragModeDragsFromTheFirstDwellToTheSecond() {
        openMenu()
        dwellOnMenu(GestureAction.DRAG)
        waitUntil("drag mode", TIMEOUT_MS) { Device.onMain { service.currentMode } == GestureAction.DRAG }

        val size = Device.displaySize()
        val from = PointF(size.width * 0.25f, size.height * 0.3f)
        val to = PointF(size.width * 0.4f, size.height * 0.55f)

        mouse.approachAndRest(from)
        // The first dwell only marks the start point; nothing is touched yet.
        waitUntil("the drag start point", TIMEOUT_MS) { Device.onMain { service.hasPendingPoint } }
        assertTrue("the drag start point touched the app", touchDowns().isEmpty())

        mouse.approachAndRest(to)
        val up = waitFor("the drag to finish", TIMEOUT_MS) {
            TouchLog.snapshot().lastOrNull { it.action == MotionEvent.ACTION_UP }
        }
        val down = touchDowns().single()
        assertNear(from, down.x, down.y)
        assertNear(to, up.x, up.y)
        assertTrue(
            "a drag should pass through intermediate points",
            TouchLog.snapshot().count { it.action == MotionEvent.ACTION_MOVE } > 1,
        )
        assertEquals(GestureAction.TAP, Device.onMain { service.currentMode })
    }

    @Test
    fun wheelDownInjectsAnUpwardStrokeAtThePointer() {
        OpenMouseSettings(Device.context).dwellEnabled = false
        Device.instrumentation.waitForIdleSync()
        mouse.moveTo(app)

        mouse.scroll(-1f)
        val up = waitFor("the scroll stroke", TIMEOUT_MS) {
            TouchLog.snapshot().lastOrNull { it.action == MotionEvent.ACTION_UP }
        }
        val down = touchDowns().single()
        assertEquals(app.x, down.x, PIXEL_TOLERANCE)
        // Wheel down reveals content below: the finger drags upward.
        assertTrue("wheel down should drag up (down=${down.y}, up=${up.y})", up.y < down.y)
    }

    @Test
    fun crossHairIsDrawnWhereTheTapWillLandInTheChosenColor() {
        val settings = OpenMouseSettings(Device.context)
        settings.dwellEnabled = false
        settings.cursorColorIndex = CYAN
        Device.instrumentation.waitForIdleSync()
        mouse.moveTo(app)

        val expected = OpenMouseSettings.CURSOR_COLORS[CYAN]
        val d = Device.context.resources.displayMetrics.density
        // The center dot and a point halfway along the right arm (the arm runs
        // from 7 dp to 41 dp out at scale 1). Poll: the overlay frame reaches
        // the screen some time after the move, later on a slow emulator.
        val points = listOf(app.x.toInt() to app.y.toInt(), (app.x + 24f * d).toInt() to app.y.toInt())
        var miss: String
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        do {
            val shot = Device.screenshot()
            miss = try {
                points.firstNotNullOfOrNull { (x, y) -> colorMissAt(expected, shot, x, y) } ?: return
            } finally {
                shot.recycle()
            }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError(miss)
    }

    // --- helpers ---

    private fun openMenu() {
        val toggle = waitFor("the menu toggle") { service.centerOf(GestureAction.TOGGLE) }
        mouse.moveTo(toggle)
        waitFor("the menu to expand", TIMEOUT_MS) { service.centerOf(GestureAction.HOME) }
    }

    /** Move onto [action]'s menu entry and wait out one dwell there. */
    private fun dwellOnMenu(action: GestureAction) {
        val target = waitFor("menu entry $action") { service.centerOf(action) }
        // Entering an entry arms its countdown at once; no approach needed.
        mouse.moveTo(target)
        SystemClock.sleep(DWELL_MS + 400L)
    }

    private fun assertNear(expected: PointF, x: Float, y: Float, tolerance: Float = PIXEL_TOLERANCE) {
        assertTrue(
            "touch at ($x, $y), expected within ${tolerance}px of (${expected.x}, ${expected.y})",
            abs(x - expected.x) <= tolerance && abs(y - expected.y) <= tolerance,
        )
    }

    /** Null if a pixel near ([x], [y]) has the [expected] color, else what was there. */
    private fun colorMissAt(expected: Int, shot: android.graphics.Bitmap, x: Int, y: Int): String? {
        // Anti-aliasing and scaling can soften one pixel; accept any pixel in a
        // 5x5 neighborhood that is close to the expected color.
        for (dy in -2..2) for (dx in -2..2) {
            val c = shot.getPixel((x + dx).coerceIn(0, shot.width - 1), (y + dy).coerceIn(0, shot.height - 1))
            if (abs(Color.red(c) - Color.red(expected)) <= 24 &&
                abs(Color.green(c) - Color.green(expected)) <= 24 &&
                abs(Color.blue(c) - Color.blue(expected)) <= 24
            ) {
                return null
            }
        }
        return "no pixel near ($x, $y) has color #%08X; center is #%08X".format(expected, shot.getPixel(x, y))
    }

    companion object {
        // Fast but not so fast that the slow software-rendered CI emulator
        // misses ticks.
        private const val DWELL_MS = 600
        private const val TIMEOUT_MS = 5_000L
        // Injected strokes land on whole pixels; allow rounding either way.
        private const val PIXEL_TOLERANCE = 1.5f
        private const val CYAN = 2
    }
}
