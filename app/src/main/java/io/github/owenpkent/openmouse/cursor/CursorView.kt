package io.github.owenpkent.openmouse.cursor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import androidx.core.content.ContextCompat
import io.github.owenpkent.openmouse.R
import io.github.owenpkent.openmouse.menu.GestureMenu

/**
 * Full-screen transparent overlay that draws the big cross-hair cursor and
 * captures raw pointer input from a mouse or trackball.
 *
 * It also hides the system pointer over itself (a null [PointerIcon]) so the
 * user sees only OpenMouse's cross. Pointer movement is reported through
 * [onMove]; the service feeds those positions to the dwell clicker and pushes
 * the countdown back via [setCountdown].
 */
class CursorView(context: Context) : View(context) {

    /** Called with screen coordinates whenever the pointer moves. */
    var onMove: ((Float, Float) -> Unit)? = null

    /** Called when the physical primary (left) mouse button is pressed. */
    var onPrimaryButton: ((Float, Float) -> Unit)? = null

    /** The gesture menu to draw and hit-test; set by the service. */
    var gestureMenu: GestureMenu? = null

    private var cursorX = -1f
    private var cursorY = -1f
    private var countdown = 0f // 0 = idle, approaches 1 just before a click
    private var pendingX = -1f // start point of a pending two-point gesture
    private var pendingY = -1f

    private val d = resources.displayMetrics.density

    // Base sizes at scale 1.0; the cross-hair scales with cursorScale.
    private val baseArm = 34f * d
    private val baseGap = 7f * d
    private val baseCountdown = 30f * d
    private val baseDot = 3f * d
    private val baseFillStroke = 4f * d
    private val baseOutlineStroke = 8f * d
    private val pendingRadius = 10f * d // start marker, intentionally unscaled

    private var cursorScale = 1f
    private val armLength get() = baseArm * cursorScale
    private val centerGap get() = baseGap * cursorScale
    private val maxCountdownRadius get() = baseCountdown * cursorScale
    private val dotRadius get() = baseDot * cursorScale

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = baseFillStroke
        color = ContextCompat.getColor(context, R.color.cursor_fill)
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = baseOutlineStroke
        color = ContextCompat.getColor(context, R.color.cursor_outline)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.cursor_fill)
    }

    private val countdownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.cursor_countdown)
    }

    private val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        color = ContextCompat.getColor(context, R.color.cursor_pending)
    }

    /** Move the cursor without a pointer event (e.g. driven by the service). */
    fun setCursorPosition(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        invalidate()
    }

    /** Set dwell countdown progress, 0 (idle) to 1 (about to click). */
    fun setCountdown(value: Float) {
        countdown = value.coerceIn(0f, 1f)
        invalidate()
    }

    /** Scale the cross-hair (1.0 = default size). */
    fun setCursorScale(scale: Float) {
        cursorScale = scale
        fillPaint.strokeWidth = baseFillStroke * scale
        outlinePaint.strokeWidth = baseOutlineStroke * scale
        invalidate()
    }

    /** Set the cross-hair fill color (ARGB int). The dark outline is unchanged. */
    fun setCursorColor(color: Int) {
        fillPaint.color = color
        dotPaint.color = color
        invalidate()
    }

    /** Mark the start point of a two-point gesture (drag/swipe). */
    fun setPendingPoint(x: Float, y: Float) {
        pendingX = x
        pendingY = y
        invalidate()
    }

    /** Clear the two-point start marker. */
    fun clearPendingPoint() {
        pendingX = -1f
        pendingY = -1f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gestureMenu?.updateLayout(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        // The menu is always visible, even before the first pointer move.
        gestureMenu?.draw(canvas, cursorX, cursorY)

        // Start marker for an in-progress drag/swipe.
        if (pendingX >= 0f) {
            canvas.drawCircle(pendingX, pendingY, pendingRadius, pendingPaint)
        }

        if (cursorX < 0f) return // no cursor to draw until the first move

        // Countdown shrinks toward the center as the dwell timer fills.
        if (countdown > 0f) {
            val radius = maxCountdownRadius * (1f - countdown)
            canvas.drawCircle(cursorX, cursorY, radius, countdownPaint)
        }

        // Each arm is drawn outline-first, then fill, so the cross stays legible
        // over any background.
        drawArms(canvas, outlinePaint)
        drawArms(canvas, fillPaint)
        canvas.drawCircle(cursorX, cursorY, dotRadius, dotPaint)
    }

    private fun drawArms(canvas: Canvas, paint: Paint) {
        val x = cursorX
        val y = cursorY
        canvas.drawLine(x, y - centerGap, x, y - armLength, paint) // up
        canvas.drawLine(x, y + centerGap, x, y + armLength, paint) // down
        canvas.drawLine(x - centerGap, y, x - armLength, y, paint) // left
        canvas.drawLine(x + centerGap, y, x + armLength, y, paint) // right
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
        ) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_HOVER_MOVE,
                -> {
                    report(event)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // This overlay captures raw pointer input; it is not a clickable control, so
    // the performClick() contract does not apply.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // With a button held, a mouse delivers touch events rather than hover,
        // so track those too to keep the cursor following a drag.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                report(event)
                // A physical left-click is an immediate "standard click".
                if (event.isFromSource(InputDevice.SOURCE_MOUSE) &&
                    (event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
                ) {
                    onPrimaryButton?.invoke(cursorX, cursorY)
                }
                return true
            }

            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            -> {
                report(event)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun report(event: MotionEvent) {
        cursorX = event.x
        cursorY = event.y
        onMove?.invoke(cursorX, cursorY)
        invalidate()
    }

    // Hide the hardware pointer while it is over this overlay; OpenMouse draws
    // its own cross instead.
    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon {
        return PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
    }
}
