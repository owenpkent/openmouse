package io.github.owenpkent.openmouse.menu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import io.github.owenpkent.openmouse.R

/** Everything the menu can do. The first three are sticky-ish "modes"; the rest
 *  are one-shot actions. TOGGLE expands/collapses the menu. */
enum class GestureAction { TAP, DOUBLE_TAP, LONG_PRESS, BACK, HOME, RECENTS, TOGGLE }

/**
 * The on-screen gesture menu: a vertical strip docked to the right edge.
 *
 * It is not a real View hierarchy. The full-screen [io.github.owenpkent.openmouse.cursor.CursorView]
 * owns all pointer input, so the menu is drawn by that view and hit-tested
 * against the cursor position here. Selecting a tap mode (Tap/2×/Hold) changes
 * [currentMode]; the navigation entries fire immediately; TOGGLE expands the
 * strip. The service drives all of this through [hitTest].
 */
class GestureMenu(context: Context) {

    var expanded = false
        private set

    /** The active tap mode, always one of TAP / DOUBLE_TAP / LONG_PRESS. */
    var currentMode = GestureAction.TAP

    private class Item(val action: GestureAction, val label: String) {
        val rect = RectF()
    }

    private val toggle = Item(GestureAction.TOGGLE, "")
    private val items = listOf(
        Item(GestureAction.TAP, context.getString(R.string.menu_tap)),
        Item(GestureAction.DOUBLE_TAP, context.getString(R.string.menu_double_tap)),
        Item(GestureAction.LONG_PRESS, context.getString(R.string.menu_long_press)),
        Item(GestureAction.BACK, context.getString(R.string.menu_back)),
        Item(GestureAction.HOME, context.getString(R.string.menu_home)),
        Item(GestureAction.RECENTS, context.getString(R.string.menu_recents)),
    )

    private val d = context.resources.displayMetrics.density
    private val itemW = 92f * d
    private val itemH = 56f * d
    private val gap = 6f * d
    private val margin = 8f * d
    private val radius = 12f * d

    private var lastWidth = 0
    private var lastHeight = 0

    private val bgPaint = fillPaint(context, R.color.menu_item_bg)
    private val selectedPaint = fillPaint(context, R.color.menu_item_selected)
    private val hoverPaint = fillPaint(context, R.color.menu_item_hover)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.menu_text)
        textAlign = Paint.Align.CENTER
        textSize = 16f * d
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

    /** Recompute button positions for the current screen size and expand state. */
    fun updateLayout(width: Int, height: Int) {
        lastWidth = width
        lastHeight = height
        val rows = if (expanded) items.size + 1 else 1
        val totalH = rows * itemH + (rows - 1) * gap
        var top = (height - totalH) / 2f
        val left = width - margin - itemW
        val right = width - margin

        toggle.rect.set(left, top, right, top + itemH)
        if (expanded) {
            for (item in items) {
                top += itemH + gap
                item.rect.set(left, top, right, top + itemH)
            }
        }
    }

    fun toggleExpanded() {
        expanded = !expanded
        updateLayout(lastWidth, lastHeight)
    }

    /** Which menu entry (if any) sits under [x], [y]. */
    fun hitTest(x: Float, y: Float): GestureAction? {
        if (toggle.rect.contains(x, y)) return GestureAction.TOGGLE
        if (expanded) {
            for (item in items) if (item.rect.contains(x, y)) return item.action
        }
        return null
    }

    fun draw(canvas: Canvas, cursorX: Float, cursorY: Float) {
        drawItem(canvas, toggle, cursorX, cursorY)
        if (expanded) {
            for (item in items) drawItem(canvas, item, cursorX, cursorY)
        }
    }

    private fun drawItem(canvas: Canvas, item: Item, cursorX: Float, cursorY: Float) {
        val r = item.rect
        val base = if (item.action == currentMode) selectedPaint else bgPaint
        canvas.drawRoundRect(r, radius, radius, base)
        if (r.contains(cursorX, cursorY)) canvas.drawRoundRect(r, radius, radius, hoverPaint)

        if (item.action == GestureAction.TOGGLE) {
            drawHamburger(canvas, r)
        } else {
            val baseline = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(item.label, r.centerX(), baseline, textPaint)
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
