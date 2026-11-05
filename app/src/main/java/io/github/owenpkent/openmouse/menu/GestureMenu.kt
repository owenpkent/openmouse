package io.github.owenpkent.openmouse.menu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import io.github.owenpkent.openmouse.R

/**
 * Everything the menu can do. TAP / DOUBLE_TAP / LONG_PRESS / DRAG / SWIPE /
 * SCROLL_UP / SCROLL_DOWN are selectable tap modes; BACK / HOME / RECENTS /
 * NOTIFICATIONS / QUICK_SETTINGS fire immediately; PAUSE suspends or resumes
 * dwell clicking on the app; TOGGLE expands or collapses the strip.
 */
enum class GestureAction {
    TAP, DOUBLE_TAP, LONG_PRESS, DRAG, SWIPE, SCROLL_UP, SCROLL_DOWN,
    BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, PAUSE, TOGGLE,
    ;

    /** Two-point gestures need a start point and an end point. */
    val isTwoPoint: Boolean get() = this == DRAG || this == SWIPE

    /**
     * Modes that repeat while the pointer keeps resting (one scroll notch per
     * dwell period), rather than locking until it moves.
     */
    val isRepeating: Boolean get() = this == SCROLL_UP || this == SCROLL_DOWN
}

/**
 * The on-screen gesture menu: a dock flush with one screen edge. Collapsed it
 * is a slim handle; expanded it is a hamburger row over a grid of square
 * glyph cells (two columns, wider on a short screen so the whole grid stays
 * reachable).
 *
 * It is not a real View hierarchy. The full-screen CursorView owns all pointer
 * input, so the menu is drawn by that view and hit-tested against the cursor
 * position. Layout and hit-testing live in the pure [MenuGeometry]; this class
 * adds Android drawing and maps grid cells to [GestureAction]s. The service
 * drives everything through [hitTest]. Every entry is a drawn symbol rather
 * than a word, so the cells stay small and nothing needs translating.
 */
class GestureMenu(context: Context) {

    var expanded = false
        private set

    /** Dock the strip on the right edge (true) or the left (false). */
    var dockRight = true
        private set

    /** The active tap mode, always one of the selectable modes (never a nav action). */
    var currentMode = GestureAction.TAP

    /** Whether dwell clicking is paused; the Pause entry shows a play symbol and highlights. */
    var paused = false

    private val items = listOf(
        GestureAction.TAP,
        GestureAction.DOUBLE_TAP,
        GestureAction.LONG_PRESS,
        GestureAction.DRAG,
        GestureAction.SWIPE,
        GestureAction.SCROLL_UP,
        GestureAction.SCROLL_DOWN,
        GestureAction.BACK,
        GestureAction.HOME,
        GestureAction.RECENTS,
        GestureAction.NOTIFICATIONS,
        GestureAction.QUICK_SETTINGS,
        GestureAction.PAUSE,
    )

    private val d = context.resources.displayMetrics.density
    private val cell = CELL_DP * d
    private val radius = 10f * d
    private val geometry = MenuGeometry(
        itemCount = items.size,
        itemW = cell,
        itemH = cell,
        gap = GAP_DP * d,
        margin = MARGIN_DP * d,
        minColumns = 2,
        handleW = HANDLE_W_DP * d,
        handleH = HANDLE_H_DP * d,
    )

    private var lastWidth = 0
    private var lastHeight = 0
    private val tmp = RectF()
    private val path = Path()

    private val bgPaint = fillPaint(context, R.color.menu_item_bg)
    private val selectedPaint = fillPaint(context, R.color.menu_item_selected)
    private val hoverPaint = fillPaint(context, R.color.menu_item_hover)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.menu_text)
        strokeWidth = 2.5f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.menu_text)
    }

    private fun fillPaint(context: Context, colorRes: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, colorRes)
        }

    fun updateLayout(width: Int, height: Int) {
        lastWidth = width
        lastHeight = height
        geometry.layout(width, height, expanded, dockRight)
    }

    fun toggleExpanded() {
        expanded = !expanded
        geometry.layout(lastWidth, lastHeight, expanded, dockRight)
    }

    /** Move the strip to the right (true) or left (false) edge. */
    fun setDockRight(value: Boolean) {
        dockRight = value
        geometry.layout(lastWidth, lastHeight, expanded, dockRight)
    }

    /** Which menu entry (if any) sits under [x], [y]. */
    fun hitTest(x: Float, y: Float): GestureAction? {
        val index = geometry.hitTest(x, y, expanded) ?: return null
        return if (index == MenuGeometry.TOGGLE_INDEX) GestureAction.TOGGLE else items[index]
    }

    fun draw(canvas: Canvas, cursorX: Float, cursorY: Float) {
        drawCell(canvas, geometry.toggle, GestureAction.TOGGLE, cursorX, cursorY)
        if (expanded) {
            for (i in items.indices) drawCell(canvas, geometry.item(i), items[i], cursorX, cursorY)
        }
    }

    private fun drawCell(canvas: Canvas, b: Bounds, action: GestureAction, cursorX: Float, cursorY: Float) {
        tmp.set(b.left, b.top, b.right, b.bottom)
        val selected = action == currentMode || (action == GestureAction.PAUSE && paused)
        canvas.drawRoundRect(tmp, radius, radius, if (selected) selectedPaint else bgPaint)
        if (b.contains(cursorX, cursorY)) canvas.drawRoundRect(tmp, radius, radius, hoverPaint)
        drawGlyph(canvas, action, tmp.centerX(), tmp.centerY())
    }

    /**
     * The symbol for [action], drawn inside a box about half the cell wide and
     * centred on ([cx], [cy]). Sizes are fractions of that box so the set scales
     * together with density and cell size.
     */
    private fun drawGlyph(canvas: Canvas, action: GestureAction, cx: Float, cy: Float) {
        val h = GLYPH_DP * d / 2f // half the glyph box
        val stroke = strokePaint
        when (action) {
            GestureAction.TOGGLE -> {
                if (expanded) {
                    // Hamburger: three bars, the toggle row is wider than a cell.
                    val w = h * 1.1f
                    for (i in -1..1) canvas.drawLine(cx - w, cy + i * h * 0.6f, cx + w, cy + i * h * 0.6f, stroke)
                } else {
                    // Grip: three dots down the slim handle.
                    for (i in -1..1) canvas.drawCircle(cx, cy + i * h * 0.55f, h * 0.16f, glyphFillPaint)
                }
            }

            GestureAction.TAP -> {
                // A dot inside a ring: one press.
                canvas.drawCircle(cx, cy, h * 0.28f, glyphFillPaint)
                canvas.drawCircle(cx, cy, h * 0.85f, stroke)
            }

            GestureAction.DOUBLE_TAP -> {
                // Two dots inside a ring: two presses.
                canvas.drawCircle(cx - h * 0.32f, cy, h * 0.22f, glyphFillPaint)
                canvas.drawCircle(cx + h * 0.32f, cy, h * 0.22f, glyphFillPaint)
                canvas.drawCircle(cx, cy, h * 0.85f, stroke)
            }

            GestureAction.LONG_PRESS -> {
                // A dot with a three-quarter ring: a press that is held.
                canvas.drawCircle(cx, cy, h * 0.28f, glyphFillPaint)
                tmp.set(cx - h * 0.85f, cy - h * 0.85f, cx + h * 0.85f, cy + h * 0.85f)
                canvas.drawArc(tmp, -90f, 270f, false, stroke)
            }

            GestureAction.DRAG -> {
                // A small square towed by a diagonal arrow to the bottom right.
                tmp.set(cx - h, cy - h, cx - h * 0.3f, cy - h * 0.3f)
                canvas.drawRect(tmp, stroke)
                arrow(canvas, cx - h * 0.35f, cy - h * 0.35f, cx + h, cy + h, h * 0.45f)
            }

            GestureAction.SWIPE -> arrow(canvas, cx - h, cy, cx + h, cy, h * 0.5f)

            GestureAction.SCROLL_UP -> chevron(canvas, cx, cy, h, up = true)

            GestureAction.SCROLL_DOWN -> chevron(canvas, cx, cy, h, up = false)

            GestureAction.BACK -> arrow(canvas, cx + h, cy, cx - h, cy, h * 0.5f)

            GestureAction.HOME -> canvas.drawCircle(cx, cy, h * 0.8f, stroke) // Android's home pill

            GestureAction.RECENTS -> {
                tmp.set(cx - h * 0.75f, cy - h * 0.75f, cx + h * 0.75f, cy + h * 0.75f)
                canvas.drawRoundRect(tmp, h * 0.15f, h * 0.15f, stroke) // Android's recents square
            }

            GestureAction.NOTIFICATIONS -> bell(canvas, cx, cy, h)

            GestureAction.QUICK_SETTINGS -> {
                // A gear: ring plus eight teeth.
                canvas.drawCircle(cx, cy, h * 0.5f, stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val ux = Math.cos(a).toFloat()
                    val uy = Math.sin(a).toFloat()
                    canvas.drawLine(cx + ux * h * 0.65f, cy + uy * h * 0.65f, cx + ux * h, cy + uy * h, stroke)
                }
            }

            GestureAction.PAUSE -> {
                if (paused) {
                    // Play: dwell clicking resumes.
                    path.reset()
                    path.moveTo(cx - h * 0.6f, cy - h * 0.85f)
                    path.lineTo(cx + h * 0.9f, cy)
                    path.lineTo(cx - h * 0.6f, cy + h * 0.85f)
                    path.close()
                    canvas.drawPath(path, glyphFillPaint)
                } else {
                    tmp.set(cx - h * 0.75f, cy - h * 0.85f, cx - h * 0.25f, cy + h * 0.85f)
                    canvas.drawRoundRect(tmp, h * 0.1f, h * 0.1f, glyphFillPaint)
                    tmp.set(cx + h * 0.25f, cy - h * 0.85f, cx + h * 0.75f, cy + h * 0.85f)
                    canvas.drawRoundRect(tmp, h * 0.1f, h * 0.1f, glyphFillPaint)
                }
            }
        }
    }

    /** A line from (x1, y1) to (x2, y2) with an open arrowhead of size [head] at the end. */
    private fun arrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, head: Float) {
        canvas.drawLine(x1, y1, x2, y2, strokePaint)
        val a = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        for (side in intArrayOf(-1, 1)) {
            val b = a + side * Math.toRadians(150.0)
            canvas.drawLine(x2, y2, x2 + head * Math.cos(b).toFloat(), y2 + head * Math.sin(b).toFloat(), strokePaint)
        }
    }

    private fun chevron(canvas: Canvas, cx: Float, cy: Float, h: Float, up: Boolean) {
        val tipY = if (up) cy - h * 0.45f else cy + h * 0.45f
        val baseY = if (up) cy + h * 0.35f else cy - h * 0.35f
        canvas.drawLine(cx - h * 0.8f, baseY, cx, tipY, strokePaint)
        canvas.drawLine(cx, tipY, cx + h * 0.8f, baseY, strokePaint)
    }

    private fun bell(canvas: Canvas, cx: Float, cy: Float, h: Float) {
        path.reset()
        path.moveTo(cx - h * 0.85f, cy + h * 0.45f)
        path.lineTo(cx - h * 0.6f, cy + h * 0.1f)
        path.lineTo(cx - h * 0.6f, cy - h * 0.3f)
        tmp.set(cx - h * 0.6f, cy - h * 0.9f, cx + h * 0.6f, cy + h * 0.3f)
        path.arcTo(tmp, 180f, 180f, false)
        path.lineTo(cx + h * 0.6f, cy + h * 0.1f)
        path.lineTo(cx + h * 0.85f, cy + h * 0.45f)
        path.close()
        canvas.drawPath(path, strokePaint)
        canvas.drawLine(cx - h * 0.2f, cy + h * 0.8f, cx + h * 0.2f, cy + h * 0.8f, strokePaint) // clapper
    }

    companion object {
        /** Square glyph cells; small enough that two columns of thirteen fit a phone in portrait. */
        const val CELL_DP = 48f
        const val GAP_DP = 4f

        /** Flush with the screen edge: the dock is part of the edge, not a floating panel. */
        const val MARGIN_DP = 0f

        /**
         * The collapsed handle: narrow, because the screen edge stops the
         * pointer anyway, and tall so it is easy to find by height.
         */
        const val HANDLE_W_DP = 22f
        const val HANDLE_H_DP = 96f

        /** The glyph box inside a cell. */
        private const val GLYPH_DP = 24f
    }
}
