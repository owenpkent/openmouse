package io.github.owenpkent.openmouse.menu

/** A plain rectangle in screen pixels. No Android types, so it is unit testable. */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/**
 * Pure layout and hit-testing for the gesture menu, with no Android dependencies.
 *
 * The menu is a vertical strip docked to the right edge: a full-width toggle row
 * on top, then a grid of [itemCount] entries below it when expanded. The grid is
 * [minColumns] wide on a tall screen; on a short one it widens, one column at a
 * time, until the expanded strip fits the height or the screen width runs out,
 * so the last entries (Pause / Resume among them) stay reachable in landscape.
 * The whole strip is centered vertically.
 *
 * Degenerate construction values are clamped rather than trusted (a negative
 * item count becomes 0, fewer than one column becomes 1), so the layout is
 * defined for any input; the fuzz target in `app/src/test/.../fuzz` relies on it.
 */
class MenuGeometry(
    itemCount: Int,
    private val itemW: Float,
    private val itemH: Float,
    private val gap: Float,
    private val margin: Float,
    minColumns: Int = 2,
) {
    private val itemCount = itemCount.coerceAtLeast(0)
    private val minColumns = minColumns.coerceAtLeast(1)

    /** The toggle (hamburger) row. Valid after [layout]. */
    var toggle: Bounds = Bounds(0f, 0f, 0f, 0f)
        private set

    /** Columns the last [layout] used: [minColumns], or more on a short screen. */
    var columns: Int = this.minColumns
        private set

    private val cells = ArrayList<Bounds>(this.itemCount)

    /** Total width of the strip as last laid out. */
    val panelWidth: Float get() = panelWidth(columns)

    private fun panelWidth(columns: Int): Float = columns * itemW + (columns - 1) * gap

    /** Bounds of item [index] (only meaningful while expanded). */
    fun item(index: Int): Bounds = cells[index]

    /** Recompute positions for the given screen size, expand state, and dock side. */
    fun layout(width: Int, height: Int, expanded: Boolean, dockRight: Boolean = true) {
        cells.clear()
        columns = fitColumns(width, height)
        val panelWidth = panelWidth(columns)
        val left = if (dockRight) width - margin - panelWidth else margin
        val right = left + panelWidth

        val rows = if (expanded) rows(columns) else 0
        val stripHeight = stripHeight(rows)

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

    // The column count is chosen from the expanded strip even while collapsed, so
    // the toggle keeps one width and one place whether or not the grid is shown.
    private fun fitColumns(width: Int, height: Int): Int {
        var columns = minColumns
        // Past itemCount columns the grid is a single row and cannot get shorter,
        // which also bounds the loop for an absurd count or a NaN size.
        while (columns < itemCount &&
            stripHeight(rows(columns)) > height &&
            panelWidth(columns + 1) <= width - 2 * margin
        ) {
            columns++
        }
        return columns
    }

    private fun rows(columns: Int): Int = ceilDiv(itemCount, columns)

    private fun stripHeight(rows: Int): Float {
        val gridHeight = if (rows > 0) rows * itemH + (rows - 1) * gap else 0f
        val gridGap = if (rows > 0) gap else 0f
        return itemH + gridGap + gridHeight
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

    // Integer math on a count that is at most a few dozen; use Long so an absurd
    // count handed in by a fuzzer cannot overflow the intermediate sum.
    private fun ceilDiv(a: Int, b: Int): Int = ((a.toLong() + b - 1) / b).toInt()

    companion object {
        const val TOGGLE_INDEX = -1
    }
}
