package io.github.owenpkent.openmouse.cursor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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

    /** Called when any other mouse button (right, middle, back, forward) is pressed. */
    var onOtherButton: (() -> Unit)? = null

    /** Called on a scroll-wheel turn: (x, y, vscroll) where vscroll > 0 is wheel-up. */
    var onScroll: ((Float, Float, Float) -> Unit)? = null

    /**
     * Whether any mouse button is down after the event just reported, called on
     * every mouse event. The service keeps dwelling suppressed while one is.
     */
    var onButtonsChanged: ((Boolean) -> Unit)? = null

    /** The gesture menu to draw and hit-test; set by the service. */
    var gestureMenu: GestureMenu? = null

    /**
     * Called after the menu has been laid out for a new view size (rotation, a
     * folding screen), which moves the menu under a pointer that has not sent
     * an event; the service re-anchors the dwell machine on what is under the
     * cursor now.
     */
    var onLayoutChanged: (() -> Unit)? = null

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

    // The action badge: a pill naming the current mode whenever it is not a
    // plain tap, drawn beside the cross-hair so a sticky scroll or the second
    // half of a drag is visible where the user is looking, not only on the menu
    // at the screen edge. Same colors as a selected menu entry, so it reads as
    // "this entry is active". Unscaled: it is a label, not part of the cursor.
    private var badge: String? = null
    private val badgeHeight = 28f * d
    private val badgePadX = 10f * d
    private val badgeGap = 6f * d // between the arm tip and the pill
    private val badgeRadius = 8f * d
    private val badgeRect = RectF()
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.menu_item_selected)
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.menu_text)
        textAlign = Paint.Align.CENTER
        textSize = 14f * d
        isFakeBoldText = true
    }

    /** Move the cursor without a pointer event (e.g. driven by the service). */
    fun setCursorPosition(x: Float, y: Float) {
        if (x == cursorX && y == cursorY) return
        cursorX = x
        cursorY = y
        invalidate()
    }

    /** Set dwell countdown progress, 0 (idle) to 1 (about to click). */
    fun setCountdown(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        // Only redraw when the value actually changes; the dwell ticker calls
        // this ~60x/s and is usually idle (0), so this avoids constant redraws.
        if (clamped == countdown) return
        countdown = clamped
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

    /**
     * Show [text] in the action badge beside the cross-hair, or hide the badge
     * (null). The service passes the menu label of the current mode, and null
     * for a plain tap.
     */
    fun setBadge(text: String?) {
        if (text == badge) return
        badge = text
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gestureMenu?.updateLayout(w, h)
        // Seed the cross-hair at screen center so it is visible right away,
        // rather than hidden until the first mouse move.
        if (cursorX < 0f && w > 0 && h > 0) {
            cursorX = w / 2f
            cursorY = h / 2f
        }
        onLayoutChanged?.invoke()
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

        badge?.let { drawBadge(canvas, it) }
    }

    private fun drawBadge(canvas: Canvas, text: String) {
        val w = badgeTextPaint.measureText(text) + 2f * badgePadX
        val b = BadgePlacement.place(
            cursorX, cursorY,
            offset = armLength + badgeGap,
            width = w,
            height = badgeHeight,
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
        )
        badgeRect.set(b.left, b.top, b.right, b.bottom)
        canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgePaint)
        val baseline = badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
        canvas.drawText(text, badgeRect.centerX(), baseline, badgeTextPaint)
    }

    private fun drawArms(canvas: Canvas, paint: Paint) {
        val x = cursorX
        val y = cursorY
        canvas.drawLine(x, y - centerGap, x, y - armLength, paint) // up
        canvas.drawLine(x, y + centerGap, x, y + armLength, paint) // down
        canvas.drawLine(x - centerGap, y, x - armLength, y, paint) // left
        canvas.drawLine(x + centerGap, y, x + armLength, y, paint) // right
    }

    // Only a mouse drives the cursor (this is the legacy pre-API-34 capture path;
    // on API 34+ the overlay is non-touchable and the service uses onMotionEvent).
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return super.onGenericMotionEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            -> {
                report(event)
                return true
            }

            MotionEvent.ACTION_SCROLL -> {
                report(event)
                val vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (vscroll != 0f) onScroll?.invoke(cursorX, cursorY, vscroll)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // This overlay captures raw pointer input; it is not a clickable control, so
    // the performClick() contract does not apply.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Ignore non-mouse touches entirely: a stray finger or palm must not move
        // the cross-hair or fire a click. A mouse with a button held delivers
        // touch (not hover) events, so we still track those.
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                report(event)
                // ACTION_DOWN is the first button down (a second button while one
                // is held arrives as ACTION_BUTTON_PRESS, not here), so buttonState
                // names that button. Left is an immediate "standard click"; any
                // other button is the user's own action, and the service locks
                // dwell so it does not stack a click on top.
                if ((event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0) {
                    onPrimaryButton?.invoke(cursorX, cursorY)
                } else {
                    onOtherButton?.invoke()
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
        // A press is reported before the position, so a move with a button
        // held is seen as held when the dwell machine receives it; a release is
        // reported after, so the lock it leaves anchors on what the pointer is
        // over when the button comes up (the same order as the API 34+ path).
        val held = event.buttonState != 0
        if (held) onButtonsChanged?.invoke(true)
        onMove?.invoke(cursorX, cursorY)
        if (!held) onButtonsChanged?.invoke(false)
        invalidate()
    }

    // Hide the hardware pointer while it is over this overlay; OpenMouse draws
    // its own cross instead.
    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon {
        return PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
    }
}
