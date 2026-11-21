package io.github.owenpkent.openmouse.settings

import kotlin.math.roundToLong

/**
 * Puts a value on a slider's tick grid: `from`, `from + step`, ... `to`.
 *
 * A Material Slider throws if it is handed a value off that grid, and two routes
 * produce one: an accessibility service's absolute-progress action, which clamps
 * to the range but does not snap to a step, and a stored preference that came
 * from such an action (or from float noise, 0.7f * 100 = 70.00001). Snapping
 * every value on its way into a slider keeps Settings openable whatever is stored.
 *
 * Defined for any input: NaN lands on `from`, and a non-positive or non-finite
 * step only clamps.
 */
object SliderGrid {
    fun snap(value: Float, from: Float, to: Float, step: Float): Float {
        if (!from.isFinite() || !to.isFinite()) return if (from.isFinite()) from else 0f
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        if (value.isNaN()) return lo
        val clamped = value.coerceIn(lo, hi)
        if (!(step > 0f) || !step.isFinite()) return clamped
        val steps = ((clamped - lo) / step).roundToLong()
        // The top tick may not be a whole number of steps from the bottom; never
        // round past it.
        return (lo + steps * step).coerceAtMost(hi)
    }
}
