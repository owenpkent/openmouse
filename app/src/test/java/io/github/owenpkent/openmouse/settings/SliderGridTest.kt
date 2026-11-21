package io.github.owenpkent.openmouse.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SliderGridTest {

    private fun opacity(percent: Float) = SliderGrid.snap(percent, 30f, 100f, 10f)

    @Test
    fun onGridValuesAreKept() {
        assertEquals(30f, opacity(30f))
        assertEquals(70f, opacity(70f))
        assertEquals(100f, opacity(100f))
    }

    @Test
    fun accessibilityValueBetweenTicksSnapsToNearest() {
        assertEquals(40f, opacity(35f))
        assertEquals(30f, opacity(34.9f))
        assertEquals(90f, opacity(88f))
    }

    @Test
    fun floatNoiseFromAStoredFractionSnaps() = assertEquals(70f, opacity(0.7f * 100f))

    @Test
    fun outOfRangeClamps() {
        assertEquals(30f, opacity(0f))
        assertEquals(100f, opacity(250f))
        assertEquals(30f, opacity(Float.NEGATIVE_INFINITY))
        assertEquals(100f, opacity(Float.POSITIVE_INFINITY))
    }

    @Test
    fun nanLandsOnTheBottomTick() = assertEquals(30f, opacity(Float.NaN))

    @Test
    fun dwellGrid() {
        assertEquals(1600f, SliderGrid.snap(1550f, 400f, 2500f, 100f))
        assertEquals(2500f, SliderGrid.snap(2499f, 400f, 2500f, 100f))
    }

    @Test
    fun neverRoundsPastAnUnevenTop() = assertEquals(25f, SliderGrid.snap(25f, 0f, 25f, 10f))

    @Test
    fun badStepOnlyClamps() {
        assertEquals(35f, SliderGrid.snap(35f, 30f, 100f, 0f))
        assertEquals(35f, SliderGrid.snap(35f, 30f, 100f, -10f))
        assertEquals(35f, SliderGrid.snap(35f, 30f, 100f, Float.NaN))
    }

    @Test
    fun badRangeStaysDefined() {
        assertEquals(40f, SliderGrid.snap(35f, 100f, 30f, 10f))
        assertEquals(0f, SliderGrid.snap(35f, Float.NaN, 100f, 10f))
    }
}
