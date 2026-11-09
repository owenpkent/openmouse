package io.github.owenpkent.openmouse.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The launch-check rule. Sideload builds check once per process by default; a
 * stored "off" always wins; builds compiled without the updater (F-Droid, Play)
 * never check, whatever is stored.
 */
class LaunchCheckTest {

    @Test
    fun `the default is on`() {
        assertTrue(LaunchCheck.DEFAULT_ON)
        assertTrue(LaunchCheck.enabled(stored = null))
    }

    @Test
    fun `an explicit off stays off`() {
        assertFalse(LaunchCheck.enabled(stored = false))
        assertFalse(LaunchCheck.shouldRun(updaterCompiledIn = true, stored = false, checkedThisProcess = false))
    }

    @Test
    fun `an explicit on stays on`() {
        assertTrue(LaunchCheck.enabled(stored = true))
    }

    @Test
    fun `a fresh sideload install checks on its first launch`() {
        assertTrue(LaunchCheck.shouldRun(updaterCompiledIn = true, stored = null, checkedThisProcess = false))
    }

    @Test
    fun `a second launch in the same process does not check again`() {
        assertFalse(LaunchCheck.shouldRun(updaterCompiledIn = true, stored = null, checkedThisProcess = true))
        assertFalse(LaunchCheck.shouldRun(updaterCompiledIn = true, stored = true, checkedThisProcess = true))
    }

    @Test
    fun `a build without the updater never checks, whatever is stored`() {
        for (stored in listOf(null, true, false)) {
            for (checked in listOf(true, false)) {
                assertFalse(
                    LaunchCheck.shouldRun(updaterCompiledIn = false, stored = stored, checkedThisProcess = checked),
                    "stored=$stored checked=$checked",
                )
            }
        }
    }

    @Test
    fun `every combination matches the rule`() {
        for (compiledIn in listOf(true, false)) {
            for (stored in listOf(null, true, false)) {
                for (checked in listOf(true, false)) {
                    val expected = compiledIn && !checked && stored != false
                    assertEquals(
                        expected,
                        LaunchCheck.shouldRun(compiledIn, stored, checked),
                        "compiledIn=$compiledIn stored=$stored checked=$checked",
                    )
                }
            }
        }
    }
}
