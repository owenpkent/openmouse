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

    /** Recompute positions for the given screen size, expand state, and dock side. */
    fun layout(width: Int, height: Int, expanded: Boolean, dockRight: Boolean = true) {
        cells.clear()
        val left = if (dockRight) width - margin - panelWidth else margin
        val right = left + panelWidth

        val rows = if (expanded) ceilDiv(itemCount, columns) else 0
        val gridHeight = if (rows > 0) rows * itemH + (rows - 1) * gap else 0f
        val gridGap = if (rows > 0) gap else 0f
        val stripHeight = itemH + gridGap + gridHeight

        // Anchor the toggle at the vertical center it has when collapsed, so it
        // does not jump when the grid expands. Shift the whole strip up only if
        // the expanded grid would run off the bottom, and never above the top.
        val collapsedTop = (height - itemH) / 2f
        var top = collapsedTop
        val maxTop = height - stripHeight
        if (top > maxTop) top = maxTop
        if (top < 0f) top = 0f

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
