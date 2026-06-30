package io.github.owenpkent.openmouse.menu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import io.github.owenpkent.openmouse.R

/**
 * Everything the menu can do. TAP / DOUBLE_TAP / LONG_PRESS / DRAG / SWIPE /
 * SCROLL_UP / SCROLL_DOWN are selectable tap modes; BACK / HOME / RECENTS fire
 * immediately; TOGGLE expands or collapses the strip.
 */
enum class GestureAction {
    TAP, DOUBLE_TAP, LONG_PRESS, DRAG, SWIPE, SCROLL_UP, SCROLL_DOWN,
    BACK, HOME, RECENTS, TOGGLE,
    ;

    /** Two-point gestures need a start point and an end point. */
    val isTwoPoint: Boolean get() = this == DRAG || this == SWIPE
}

/**
 * The on-screen gesture menu: a right-edge strip with a hamburger toggle and,
 * when expanded, a two-column grid of actions.
 *
 * It is not a real View hierarchy. The full-screen CursorView owns all pointer
 * input, so the menu is drawn by that view and hit-tested against the cursor
 * position. Layout and hit-testing live in the pure [MenuGeometry]; this class
 * adds Android drawing and maps grid cells to [GestureAction]s. The service
 * drives everything through [hitTest].
 */
class GestureMenu(context: Context) {

    var expanded = false
        private set

    /** The active tap mode, always one of the selectable modes (never a nav action). */
    var currentMode = GestureAction.TAP

    private class Item(val action: GestureAction, val label: String)

    private val items = listOf(
        Item(GestureAction.TAP, context.getString(R.string.menu_tap)),
        Item(GestureAction.DOUBLE_TAP, context.getString(R.string.menu_double_tap)),
        Item(GestureAction.LONG_PRESS, context.getString(R.string.menu_long_press)),
        Item(GestureAction.DRAG, context.getString(R.string.menu_drag)),
        Item(GestureAction.SWIPE, context.getString(R.string.menu_swipe)),
        Item(GestureAction.SCROLL_UP, context.getString(R.string.menu_scroll_up)),
        Item(GestureAction.SCROLL_DOWN, context.getString(R.string.menu_scroll_down)),
        Item(GestureAction.BACK, context.getString(R.string.menu_back)),
        Item(GestureAction.HOME, context.getString(R.string.menu_home)),
        Item(GestureAction.RECENTS, context.getString(R.string.menu_recents)),
    )

    private val d = context.resources.displayMetrics.density
    private val itemH = 52f * d
    private val radius = 12f * d
    private val geometry = MenuGeometry(
        itemCount = items.size,
        itemW = 84f * d,
        itemH = itemH,
        gap = 6f * d,
        margin = 8f * d,
        columns = 2,
    )

    private var lastWidth = 0
    private var lastHeight = 0
    private val tmp = RectF()

    private val bgPaint = fillPaint(context, R.color.menu_item_bg)
    private val selectedPaint = fillPaint(context, R.color.menu_item_selected)
    private val hoverPaint = fillPaint(context, R.color.menu_item_hover)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.menu_text)
        textAlign = Paint.Align.CENTER
        textSize = 14f * d
        isFakeBoldText = true
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.menu_text)
        strokeWidth = 3f * d
        strokeCap = Paint.Cap.ROUND
    }

    private fun fillPaint(context: Context, colorRes: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, colorRes)
        }

    fun updateLayout(width: Int, height: Int) {
        lastWidth = width
        lastHeight = height
        geometry.layout(width, height, expanded)
    }

    fun toggleExpanded() {
        expanded = !expanded
        geometry.layout(lastWidth, lastHeight, expanded)
    }

    /** Which menu entry (if any) sits under [x], [y]. */
    fun hitTest(x: Float, y: Float): GestureAction? {
        val index = geometry.hitTest(x, y, expanded) ?: return null
        return if (index == MenuGeometry.TOGGLE_INDEX) GestureAction.TOGGLE else items[index].action
    }

    fun draw(canvas: Canvas, cursorX: Float, cursorY: Float) {
        drawCell(canvas, geometry.toggle, GestureAction.TOGGLE, label = null, cursorX, cursorY)
        if (expanded) {
            for (i in items.indices) {
                drawCell(canvas, geometry.item(i), items[i].action, items[i].label, cursorX, cursorY)
            }
        }
    }

    private fun drawCell(
        canvas: Canvas,
        b: Bounds,
        action: GestureAction,
        label: String?,
        cursorX: Float,
        cursorY: Float,
    ) {
        tmp.set(b.left, b.top, b.right, b.bottom)
        val base = if (action == currentMode) selectedPaint else bgPaint
        canvas.drawRoundRect(tmp, radius, radius, base)
        if (b.contains(cursorX, cursorY)) canvas.drawRoundRect(tmp, radius, radius, hoverPaint)

        if (action == GestureAction.TOGGLE) {
            drawHamburger(canvas, tmp)
        } else if (label != null) {
            val baseline = tmp.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, tmp.centerX(), baseline, textPaint)
        }
    }

    private fun drawHamburger(canvas: Canvas, r: RectF) {
        val cx = r.centerX()
        val cy = r.centerY()
        val halfW = 11f * d
        val spacing = 7f * d
        for (i in -1..1) {
            val y = cy + i * spacing
            canvas.drawLine(cx - halfW, y, cx + halfW, y, iconPaint)
        }
    }
}
