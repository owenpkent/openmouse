package io.github.owenpkent.openmouse.testing

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Debug-only target for the instrumented UI tests: a blank full-screen window
 * that records every touch it receives, in screen coordinates, into [TouchLog].
 * The tests drive the cursor, let OpenMouse inject a gesture, then read back
 * where the gesture actually landed in a real app window.
 */
class TouchRecorderActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(RecorderView(this))
    }

    private class RecorderView(context: Context) : View(context) {
        init {
            setBackgroundColor(0xFF263238.toInt())
        }

        // A recorder, not a control: there is no click to perform for accessibility.
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            TouchLog.record(event)
            return true
        }
    }
}

/** What [TouchRecorderActivity] has seen. Thread-safe; the tests poll it. */
object TouchLog {

    /** One touch event, with raw (screen) coordinates. */
    data class Touch(val action: Int, val x: Float, val y: Float, val timeMs: Long, val toolType: Int)

    private val events = mutableListOf<Touch>()

    fun record(event: MotionEvent) {
        val touch = Touch(
            action = event.actionMasked,
            x = event.rawX,
            y = event.rawY,
            timeMs = event.eventTime,
            toolType = event.getToolType(0),
        )
        synchronized(events) { events.add(touch) }
    }

    fun snapshot(): List<Touch> = synchronized(events) { events.toList() }

    fun clear() = synchronized(events) { events.clear() }
}
