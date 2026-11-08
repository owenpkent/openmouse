package io.github.owenpkent.openmouse.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for swallowed-gesture detection on the legacy overlay. The CI
 * emulator case that motivated it: a tap dispatched at t=314 reached the still
 * touchable overlay as a touchscreen DOWN at t=324, and finished at t=352.
 */
class SwallowedGestureTrackerTest {

    @Test
    fun aDownOnTheOverlayAfterDispatchMeansTheGestureWasSwallowed() {
        val t = SwallowedGestureTracker()
        val id = t.dispatched(314L)
        t.overlayTouched(324L)
        assertTrue(t.finished(id))
        assertEquals(0, t.outstanding)
    }

    @Test
    fun aGestureTheOverlayNeverSawWasDelivered() {
        val t = SwallowedGestureTracker()
        val id = t.dispatched(314L)
        assertFalse(t.finished(id))
    }

    @Test
    fun aDownStampedBeforeDispatchIsSomeEarlierTouchNotThisGesture() {
        val t = SwallowedGestureTracker()
        val id = t.dispatched(314L)
        t.overlayTouched(300L) // a finger that landed before the gesture went out
        assertFalse(t.finished(id))
    }

    @Test
    fun aDownAtTheExactDispatchTimeCounts() {
        val t = SwallowedGestureTracker()
        val id = t.dispatched(314L)
        t.overlayTouched(314L)
        assertTrue(t.finished(id))
    }

    @Test
    fun aDownMarksEveryEarlierOutstandingGestureButNotLaterOnes() {
        // The overlay only goes touchable -> click-through during a passthrough,
        // so touchable at t means touchable for every gesture dispatched by t.
        val t = SwallowedGestureTracker()
        val first = t.dispatched(100L)
        val second = t.dispatched(200L)
        t.overlayTouched(150L)
        val third = t.dispatched(300L)
        assertTrue(t.finished(first))
        assertFalse(t.finished(second))
        assertFalse(t.finished(third))
    }

    @Test
    fun aFinishedGestureIsNotMarkedByLaterTouches() {
        val t = SwallowedGestureTracker()
        val id = t.dispatched(100L)
        assertFalse(t.finished(id))
        t.overlayTouched(200L)
        assertFalse(t.finished(id), "an id is answered once")
    }

    @Test
    fun unknownIdsAreNeverSwallowed() {
        val t = SwallowedGestureTracker()
        t.overlayTouched(0L)
        assertFalse(t.finished(42))
        assertFalse(t.finished(-1))
    }

    @Test
    fun resetForgetsOutstandingGestures() {
        val t = SwallowedGestureTracker()
        val id = t.dispatched(100L)
        t.overlayTouched(200L)
        t.reset()
        assertEquals(0, t.outstanding)
        assertFalse(t.finished(id), "a forgotten gesture is not retried")
    }

    @Test
    fun lostCompletionsCannotGrowItPastTheCap() {
        val t = SwallowedGestureTracker()
        val first = t.dispatched(0L)
        repeat(SwallowedGestureTracker.MAX_OUTSTANDING * 3) { t.dispatched(it.toLong()) }
        assertEquals(SwallowedGestureTracker.MAX_OUTSTANDING, t.outstanding)
        t.overlayTouched(Long.MAX_VALUE)
        assertFalse(t.finished(first), "the oldest entry was dropped at the cap")
    }

    @Test
    fun retryWaitsStartAtTheOldDelayGrowAndRunOut() {
        assertEquals(24L, SwallowedGestureTracker.delayBeforeAttemptMs(0))
        var previous = 0L
        for (attempt in 0 until SwallowedGestureTracker.MAX_ATTEMPTS) {
            val wait = SwallowedGestureTracker.delayBeforeAttemptMs(attempt)!!
            assertTrue(wait > previous, "attempt $attempt should wait longer than the one before")
            previous = wait
        }
        assertNull(SwallowedGestureTracker.delayBeforeAttemptMs(SwallowedGestureTracker.MAX_ATTEMPTS))
        assertNull(SwallowedGestureTracker.delayBeforeAttemptMs(-1))
        assertNull(SwallowedGestureTracker.delayBeforeAttemptMs(Int.MAX_VALUE))
    }
}
