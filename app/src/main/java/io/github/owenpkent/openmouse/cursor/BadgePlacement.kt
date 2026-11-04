package io.github.owenpkent.openmouse.cursor

import io.github.owenpkent.openmouse.menu.Bounds

/**
 * Where the action badge (the small pill naming the current mode) sits relative
 * to the cross-hair. Pure, so it is unit tested; [CursorView] only draws what it
 * returns.
 *
 * The badge goes up and to the left of the cross-hair, clear of the arms, where
 * the eye already is (the PowerToys Dwell Click badge sits there too, away from
 * the arrow cursor's hotspot). Near the left or top edge it flips to the other
 * side so it never leaves the screen, and whatever is left is clamped into the
 * view, so a badge wider than a tiny view is pinned to its top-left corner rather
 * than lost.
 */
object BadgePlacement {

    /**
     * @param cursorX, cursorY the cross-hair center
     * @param offset the gap from the cross-hair center to the badge's near corner
     *   (the arm length plus a margin)
     * @param width, height the badge size
     * @param viewWidth, viewHeight the view the badge must stay inside
     */
    fun place(
        cursorX: Float,
        cursorY: Float,
        offset: Float,
        width: Float,
        height: Float,
        viewWidth: Float,
        viewHeight: Float,
    ): Bounds {
        // Prefer up-left; flip an axis whose preferred side would leave the view.
        var left = cursorX - offset - width
        if (left < 0f) left = cursorX + offset
        var top = cursorY - offset - height
        if (top < 0f) top = cursorY + offset
        // Clamp into the view. coerceIn needs min <= max, so a view smaller than
        // the badge pins it at 0 instead of throwing.
        left = left.coerceIn(0f, (viewWidth - width).coerceAtLeast(0f))
        top = top.coerceIn(0f, (viewHeight - height).coerceAtLeast(0f))
        return Bounds(left, top, left + width, top + height)
    }
}
