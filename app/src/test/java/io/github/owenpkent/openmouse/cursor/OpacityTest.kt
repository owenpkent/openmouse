package io.github.owenpkent.openmouse.cursor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpacityTest {

    @Test
    fun fullOpacityIs255() = assertEquals(255, Opacity.toAlpha(1f))

    @Test
    fun floorIsAboutThirtyPercent() = assertEquals(77, Opacity.toAlpha(0.3f))

    @Test
    fun midRange() = assertEquals(179, Opacity.toAlpha(0.7f))

    @Test
    fun belowFloorClampsUp() {
        assertEquals(77, Opacity.toAlpha(0f))
        assertEquals(77, Opacity.toAlpha(-5f))
        assertEquals(77, Opacity.toAlpha(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun aboveOneClampsDown() {
        assertEquals(255, Opacity.toAlpha(2f))
        assertEquals(255, Opacity.toAlpha(Float.POSITIVE_INFINITY))
    }

    @Test
    fun nanIsFullyOpaque() {
        assertEquals(1f, Opacity.clamp(Float.NaN), 0f)
        assertEquals(255, Opacity.toAlpha(Float.NaN))
    }

    @Test
    fun hoveredCellIsAlwaysOpaque() {
        assertEquals(255, Opacity.cellAlpha(0.3f, hovered = true))
        assertEquals(255, Opacity.cellAlpha(Float.NaN, hovered = true))
    }

    @Test
    fun otherCellsUseDockOpacity() {
        assertEquals(128, Opacity.cellAlpha(0.5f, hovered = false))
        assertEquals(255, Opacity.cellAlpha(1f, hovered = false))
    }
}
