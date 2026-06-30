package io.github.owenpkent.openmouse.menu

/** A plain rectangle in screen pixels. No Android types, so it is unit testable. */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/**
 * Pure layout and hit-testing for the gesture menu, with no Android dependencies.
 *
 * The menu is a vertical strip docked to the right edge: a full-width toggle row
 * on top, then a [columns]-wide grid of [itemCount] entries below it when
 * expanded. The whole strip is centered vertically.
 */
class MenuGeometry(
    private val itemCount: Int,
    private val itemW: Float,
    private val itemH: Float,
    private val gap: Float,
    private val margin: Float,
    private val columns: Int = 2,
) {
    /** The toggle (hamburger) row. Valid after [layout]. */
    var toggle: Bounds = Bounds(0f, 0f, 0f, 0f)
        private set

    private val cells = ArrayList<Bounds>(itemCount)

    /** Total width of the strip. */
    val panelWidth: Float get() = columns * itemW + (columns - 1) * gap

    /** Bounds of item [index] (only meaningful while expanded). */
    fun item(index: Int): Bounds = cells[index]

    /** Recompute positions for the given screen size and expand state. */
    fun layout(width: Int, height: Int, expanded: Boolean) {
        cells.clear()
        val rows = if (expanded) ceilDiv(itemCount, columns) else 0
        val totalRows = 1 + rows // toggle row plus the item grid
        val totalH = totalRows * itemH + (totalRows - 1) * gap
        val top = (height - totalH) / 2f
        val left = width - margin - panelWidth
        val right = width - margin.toFloat()

        toggle = Bounds(left, top, right, top + itemH)
        if (!expanded) return

        val gridTop = top + itemH + gap
        for (i in 0 until itemCount) {
            val row = i / columns
            val col = i % columns
            val l = left + col * (itemW + gap)
            val t = gridTop + row * (itemH + gap)
            cells.add(Bounds(l, t, l + itemW, t + itemH))
        }
    }

    /**
     * What sits under ([x], [y]): [TOGGLE_INDEX] for the toggle, `0..itemCount-1`
     * for an item, or null for nothing.
     */
    fun hitTest(x: Float, y: Float, expanded: Boolean): Int? {
        if (toggle.contains(x, y)) return TOGGLE_INDEX
        if (expanded) {
            for (i in cells.indices) if (cells[i].contains(x, y)) return i
        }
        return null
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    companion object {
        const val TOGGLE_INDEX = -1
    }
}
