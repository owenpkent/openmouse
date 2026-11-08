package io.github.owenpkent.openmouse.gesture

import io.github.owenpkent.openmouse.gesture.SwallowedGestureTracker.Companion.MAX_RETRY_LAG_MS
import io.github.owenpkent.openmouse.gesture.SwallowedGestureTracker.Companion.TOMBSTONE_MS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for swallowed-gesture detection on the legacy overlay. The CI
 * emulator case that motivated it: a tap at (270, 960) dispatched at t=314
 * reached the still touchable overlay as a touchscreen DOWN at t=324 and
 * finished at t=352.
 *
 * The rule under test above all: never send again what reached the app.
 */
class SwallowedGestureTrackerTest {

    private fun tap(x: Float = 270f, y: Float = 960f) = floatArrayOf(x, y)
    private fun doubleTap(x: Float = 270f, y: Float = 960f) = floatArrayOf(x, y, x, y)

    @Nested
    inner class Detection {

        @Test
        fun aSwallowedTapIsRetried() {
            val t = SwallowedGestureTracker()
            val id = t.dispatched(t.requested(), 314L, tap())
            assertTrue(t.overlayTouched(324L, 270f, 960f))
            assertTrue(t.settle(id, attempt = 0, nowMs = 400L))
            assertEquals(0, t.outstanding)
        }

        @Test
        fun aTapTheOverlayNeverSawIsNotRetried() {
            val t = SwallowedGestureTracker()
            val id = t.dispatched(t.requested(), 314L, tap())
            assertFalse(t.settle(id, attempt = 0, nowMs = 400L))
        }

        @Test
        fun aDownStampedBeforeDispatchClaimsNothing() {
            val t = SwallowedGestureTracker()
            val id = t.dispatched(t.requested(), 314L, tap())
            assertFalse(t.overlayTouched(300L, 270f, 960f), "a finger that landed before the gesture went out")
            assertFalse(t.settle(id, attempt = 0, nowMs = 400L))
        }

        @Test
        fun aFingerElsewhereClaimsNothing() {
            val t = SwallowedGestureTracker()
            val id = t.dispatched(t.requested(), 314L, tap())
            assertFalse(t.overlayTouched(324L, 600f, 960f))
            assertFalse(t.overlayTouched(324L, 270f + 3f, 960f), "just past the tolerance")
            assertFalse(t.settle(id, attempt = 0, nowMs = 400L))
        }

        @Test
        fun aTouchWithinTheToleranceStillMatches() {
            val t = SwallowedGestureTracker()
            val id = t.dispatched(t.requested(), 314L, tap())
            assertTrue(t.overlayTouched(324L, 271.5f, 958.5f))
            assertTrue(t.settle(id, attempt = 0, nowMs = 400L))
        }

        @Test
        fun aDownAtTheExactDispatchTimeCounts() {
            val t = SwallowedGestureTracker()
            val id = t.dispatched(t.requested(), 314L, tap())
            assertTrue(t.overlayTouched(314L, 270f, 960f))
            assertTrue(t.settle(id, attempt = 0, nowMs = 400L))
        }
    }

    @Nested
    inner class NeverRepeatWhatReachedTheApp {

        @Test
        fun aDoubleTapWhoseSecondStrokeGotThroughIsNotRetried() {
            // Stroke 1 hit the overlay, then the flag applied and stroke 2 hit
            // the app. Re-sending would give the app a tap plus a double tap.
            val t = SwallowedGestureTracker()
            val id = t.dispatched(t.requested(), 100L, doubleTap())
            t.overlayTouched(110L, 270f, 960f)
            assertFalse(t.settle(id, attempt = 0, nowMs = 300L))
        }

        @Test
        fun aDoubleTapWithBothStrokesSwallowedIsRetried() {
            val t = SwallowedGestureTracker()
            val id = t.dispatched(t.requested(), 100L, doubleTap())
            t.overlayTouched(110L, 270f, 960f)
            t.overlayTouched(260L, 270f, 960f)
            assertTrue(t.settle(id, attempt = 0, nowMs = 300L))
        }

        @Test
        fun oneDownClaimsOnlyTheOldestMatchingStroke() {
            // Two quick clicks at the same spot: the first was swallowed, the
            // second got through. Its DOWN must not be counted twice.
            val t = SwallowedGestureTracker()
            val first = t.dispatched(t.requested(), 24L, tap())
            val second = t.dispatched(t.requested(), 34L, tap())
            t.overlayTouched(70L, 270f, 960f)
            assertFalse(t.settle(second, attempt = 0, nowMs = 100L), "the delivered click must not be resent")
            // The first was swallowed, but a newer action exists: resending it
            // now would reorder the user's actions.
            assertFalse(t.settle(first, attempt = 0, nowMs = 100L))
        }

        @Test
        fun aLateDownIsAbsorbedByItsOwnSettledGesture() {
            // O was swallowed but its DOWN is slow to arrive; O settles first
            // (not retried: nothing seen yet). W, at the same spot, reached the
            // app. O's late DOWN must not be credited to W.
            val t = SwallowedGestureTracker()
            val o = t.dispatched(t.requested(), 100L, tap())
            assertFalse(t.settle(o, attempt = 0, nowMs = 150L))
            val w = t.dispatched(t.requested(), 160L, tap())
            assertTrue(t.overlayTouched(170L, 270f, 960f), "absorbed by O's tombstone")
            assertFalse(t.settle(w, attempt = 0, nowMs = 220L), "W reached the app")
        }

        @Test
        fun tombstonesExpire() {
            val t = SwallowedGestureTracker()
            val o = t.dispatched(t.requested(), 0L, tap())
            t.settle(o, attempt = 0, nowMs = 10L)
            val w = t.dispatched(t.requested(), 10L + TOMBSTONE_MS + 1, tap())
            assertTrue(t.overlayTouched(10L + TOMBSTONE_MS + 2, 270f, 960f))
            assertTrue(t.settle(w, attempt = 0, nowMs = 10L + TOMBSTONE_MS + 50), "O is gone; the DOWN was W's")
        }

        @Test
        fun anIdIsAnsweredOnce() {
            val t = SwallowedGestureTracker()
            val id = t.dispatched(t.requested(), 100L, tap())
            assertFalse(t.settle(id, attempt = 0, nowMs = 150L))
            assertFalse(t.settle(id, attempt = 0, nowMs = 150L), "an id is answered once")
        }
    }

    @Nested
    inner class OnlyTheNewestActionRetriesAndOnlySoon {

        @Test
        fun aSwallowedGestureIsDroppedOnceSomethingNewerIsAskedFor() {
            val t = SwallowedGestureTracker()
            val id = t.dispatched(t.requested(), 100L, tap())
            t.overlayTouched(110L, 270f, 960f)
            t.requested() // the user dwelled elsewhere, or chose Back or Home
            assertFalse(t.settle(id, attempt = 0, nowMs = 160L))
        }

        @Test
        fun aPendingRetryIsNotSentOnceSomethingNewerIsAskedFor() {
            val t = SwallowedGestureTracker()
            val request = t.requested()
            val id = t.dispatched(request, 100L, tap())
            t.overlayTouched(110L, 270f, 960f)
            assertTrue(t.settle(id, attempt = 0, nowMs = 160L))
            assertTrue(t.shouldDispatch(request, attempt = 1, nowMs = 260L))
            t.requested()
            assertFalse(t.shouldDispatch(request, attempt = 1, nowMs = 260L))
            assertTrue(t.shouldDispatch(request, attempt = 0, nowMs = 260L), "a first attempt always goes out")
        }

        @Test
        fun aRetryIsNotSentPastTheRetryWindow() {
            // Past this the user may already have clicked again themselves;
            // their click reached the app, so a retry would duplicate it.
            val t = SwallowedGestureTracker()
            val request = t.requested()
            val id = t.dispatched(request, 100L, tap())
            t.overlayTouched(110L, 270f, 960f)
            assertTrue(t.settle(id, attempt = 0, nowMs = 160L))
            assertTrue(t.shouldDispatch(request, attempt = 1, nowMs = 160L + MAX_RETRY_LAG_MS))
            assertFalse(t.shouldDispatch(request, attempt = 1, nowMs = 160L + MAX_RETRY_LAG_MS + 1))
        }

        @Test
        fun theWindowRunsFromTheFirstAttemptNotTheLatest() {
            val t = SwallowedGestureTracker()
            val request = t.requested()
            val first = t.dispatched(request, 100L, tap())
            t.overlayTouched(110L, 270f, 960f)
            assertTrue(t.settle(first, attempt = 0, nowMs = 160L))
            val second = t.dispatched(request, 260L, tap())
            t.overlayTouched(270L, 270f, 960f)
            assertFalse(
                t.settle(second, attempt = 1, nowMs = 160L + MAX_RETRY_LAG_MS + 1),
                "a second retry this late is refused",
            )
        }

        @Test
        fun timeRunningBackwardsIsNeverInsideTheWindow() {
            val t = SwallowedGestureTracker()
            val request = t.requested()
            val id = t.dispatched(request, 100L, tap())
            t.overlayTouched(110L, 270f, 960f)
            assertTrue(t.settle(id, attempt = 0, nowMs = 160L))
            assertFalse(t.shouldDispatch(request, attempt = 1, nowMs = 159L))
            assertFalse(t.shouldDispatch(request, attempt = 1, nowMs = Long.MIN_VALUE))
        }

        @Test
        fun attemptsRunOut() {
            val t = SwallowedGestureTracker()
            val request = t.requested()
            val last = SwallowedGestureTracker.MAX_ATTEMPTS - 1
            val id = t.dispatched(request, 100L, tap())
            t.overlayTouched(110L, 270f, 960f)
            assertFalse(t.settle(id, attempt = last, nowMs = 160L), "the last attempt is not retried")

            val again = t.dispatched(request, 200L, tap())
            t.overlayTouched(210L, 270f, 960f)
            assertFalse(t.settle(again, attempt = Int.MAX_VALUE, nowMs = 260L))
        }
    }

    @Nested
    inner class DefinedForAnyInput {

        @Test
        fun unknownIdsAreNeverRetried() {
            val t = SwallowedGestureTracker()
            assertFalse(t.settle(42, attempt = 0, nowMs = 0L))
            assertFalse(t.settle(-1, attempt = -1, nowMs = Long.MAX_VALUE))
        }

        @Test
        fun aGestureWithNoStrokesIsNeverRetried() {
            val t = SwallowedGestureTracker()
            val id = t.dispatched(t.requested(), 100L, FloatArray(0))
            assertFalse(t.overlayTouched(110L, 0f, 0f))
            assertFalse(t.settle(id, attempt = 0, nowMs = 160L))
        }

        @Test
        fun aTrailingOddCoordinateIsIgnored() {
            val t = SwallowedGestureTracker()
            val id = t.dispatched(t.requested(), 100L, floatArrayOf(270f, 960f, 5f))
            t.overlayTouched(110L, 270f, 960f)
            assertTrue(t.settle(id, attempt = 0, nowMs = 160L))
        }

        @Test
        fun resetForgetsEverything() {
            val t = SwallowedGestureTracker()
            val id = t.dispatched(t.requested(), 100L, tap())
            t.overlayTouched(110L, 270f, 960f)
            t.reset()
            assertEquals(0, t.outstanding)
            assertFalse(t.settle(id, attempt = 0, nowMs = 160L), "a forgotten gesture is not retried")
        }

        @Test
        fun lostCompletionsCannotGrowItPastTheCap() {
            val t = SwallowedGestureTracker()
            val request = t.requested()
            val first = t.dispatched(request, 0L, tap())
            repeat(SwallowedGestureTracker.MAX_REMEMBERED * 3) { t.dispatched(request, it.toLong(), tap(1f, 1f)) }
            assertEquals(SwallowedGestureTracker.MAX_REMEMBERED, t.outstanding)
            t.overlayTouched(Long.MAX_VALUE, 270f, 960f)
            assertFalse(t.settle(first, attempt = 0, nowMs = 0L), "the oldest entry was dropped at the cap")
        }

        @Test
        fun aNegativeToleranceMatchesOnlyExactPoints() {
            val t = SwallowedGestureTracker(tolerancePx = -5f)
            val id = t.dispatched(t.requested(), 0L, tap())
            assertFalse(t.overlayTouched(1L, 270.5f, 960f))
            assertTrue(t.overlayTouched(1L, 270f, 960f))
            assertTrue(t.settle(id, attempt = 0, nowMs = 50L))
        }
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
