package io.github.owenpkent.openmouse.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The liveness rule every update callback runs through before touching its
 * activity. A result may be delivered only to an activity that is neither
 * finishing nor destroyed; anything else is dropped rather than shown on a
 * screen the user has left (rotation, Back out of Settings mid-download).
 */
class UpdatePromptTest {

    @Test
    fun `a live activity receives the result`() {
        assertTrue(UpdatePrompt.shouldDeliver(isFinishing = false, isDestroyed = false))
    }

    @Test
    fun `a finishing activity does not`() {
        assertFalse(UpdatePrompt.shouldDeliver(isFinishing = true, isDestroyed = false))
    }

    @Test
    fun `a destroyed activity does not`() {
        assertFalse(UpdatePrompt.shouldDeliver(isFinishing = false, isDestroyed = true))
    }

    @Test
    fun `an activity that is both finishing and destroyed does not`() {
        assertFalse(UpdatePrompt.shouldDeliver(isFinishing = true, isDestroyed = true))
    }
}
