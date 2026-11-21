package io.github.owenpkent.openmouse.cursor

/**
 * Pure opacity arithmetic for the cross-hair and the dock. No Android imports,
 * defined for any input (clamps, never throws), so it is JVM-tested and fuzz-safe.
 *
 * The floor keeps both visible: a user who cannot see the cursor or the dock
 * cannot recover, so even a corrupt stored value never fades below [MIN_OPACITY].
 */
object Opacity {
    const val MIN_OPACITY = 0.3f
    const val MAX_OPACITY = 1.0f

    /** Clamp to [MIN_OPACITY]..[MAX_OPACITY]. NaN becomes fully opaque, the safe direction. */
    fun clamp(opacity: Float): Float =
        if (opacity.isNaN()) MAX_OPACITY else opacity.coerceIn(MIN_OPACITY, MAX_OPACITY)

    /** The 0..255 alpha for [opacity], after clamping. */
    fun toAlpha(opacity: Float): Int = Math.round(clamp(opacity) * 255f)

    /** Alpha for a dock cell: the cell under the cursor is always fully opaque. */
    fun cellAlpha(dockOpacity: Float, hovered: Boolean): Int = if (hovered) 255 else toAlpha(dockOpacity)
}
