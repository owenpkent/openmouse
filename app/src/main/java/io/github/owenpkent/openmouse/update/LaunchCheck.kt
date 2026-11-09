package io.github.owenpkent.openmouse.update

/**
 * Whether opening the app should ask GitHub Releases for a newer version. Pure
 * (no Android imports) so the rule is JVM unit-tested (`LaunchCheckTest`);
 * `MainActivity` feeds it the build flag, the stored switch and its own
 * once-per-process latch.
 *
 * The launch check is on by default in builds that carry the updater (the
 * sideload APK from GitHub Releases, which has no store to update it), so a
 * fresh install hears about a newer release without having to find the switch.
 * A user who turned the switch off has an explicit stored `false`, and that
 * always wins over the default. Builds compiled with `-PopenmouseUpdater=false`
 * (F-Droid, Play) never check, whatever is stored.
 */
object LaunchCheck {

    /** The switch's value until the user touches it. */
    const val DEFAULT_ON = true

    /**
     * The effective switch value: what the user stored, or [DEFAULT_ON] when
     * they never changed it ([stored] is null).
     */
    fun enabled(stored: Boolean?): Boolean = stored ?: DEFAULT_ON

    /**
     * True when this launch should run the check: the updater is compiled in,
     * the switch is effectively on, and this process has not checked yet.
     */
    fun shouldRun(updaterCompiledIn: Boolean, stored: Boolean?, checkedThisProcess: Boolean): Boolean =
        updaterCompiledIn && !checkedThisProcess && enabled(stored)
}
