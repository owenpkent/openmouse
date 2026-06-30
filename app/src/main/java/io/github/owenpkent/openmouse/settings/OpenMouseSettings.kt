package io.github.owenpkent.openmouse.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Typed wrapper over SharedPreferences for OpenMouse's user settings.
 *
 * Reads are synchronous, which is fine for the accessibility service. All
 * get/set values are clamped to their valid ranges here, so callers (the
 * settings UI and the service) never have to re-validate. Register a listener to
 * react live while the cursor is running; both the settings screen and the
 * service share the same per-name SharedPreferences instance in-process, so a
 * change in the UI reaches the service immediately.
 */
class OpenMouseSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var dwellEnabled: Boolean
        get() = prefs.getBoolean(KEY_DWELL_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DWELL_ENABLED, value).apply()

    /** Dwell countdown length in milliseconds. Clamped to range on read and write. */
    var dwellTimeMs: Long
        get() = prefs.getInt(KEY_DWELL_MS, DEFAULT_DWELL_MS).coerceIn(MIN_DWELL_MS, MAX_DWELL_MS).toLong()
        set(value) = prefs.edit()
            .putInt(KEY_DWELL_MS, value.toInt().coerceIn(MIN_DWELL_MS, MAX_DWELL_MS))
            .apply()

    /** How far (in dp) the pointer may drift and still count as resting. */
    var moveThresholdDp: Float
        get() = prefs.getFloat(KEY_MOVE_DP, DEFAULT_MOVE_DP).coerceIn(MIN_MOVE_DP, MAX_MOVE_DP)
        set(value) = prefs.edit()
            .putFloat(KEY_MOVE_DP, value.coerceIn(MIN_MOVE_DP, MAX_MOVE_DP))
            .apply()

    /** Cross-hair size multiplier. */
    var cursorScale: Float
        get() = prefs.getFloat(KEY_CURSOR_SCALE, DEFAULT_CURSOR_SCALE).coerceIn(MIN_CURSOR_SCALE, MAX_CURSOR_SCALE)
        set(value) = prefs.edit()
            .putFloat(KEY_CURSOR_SCALE, value.coerceIn(MIN_CURSOR_SCALE, MAX_CURSOR_SCALE))
            .apply()

    /** Index into [CURSOR_COLORS]. */
    var cursorColorIndex: Int
        get() = prefs.getInt(KEY_CURSOR_COLOR, 0).coerceIn(0, CURSOR_COLORS.size - 1)
        set(value) = prefs.edit()
            .putInt(KEY_CURSOR_COLOR, value.coerceIn(0, CURSOR_COLORS.size - 1))
            .apply()

    /** True docks the gesture menu on the right edge, false on the left. */
    var menuOnRight: Boolean
        get() = prefs.getBoolean(KEY_MENU_RIGHT, true)
        set(value) = prefs.edit().putBoolean(KEY_MENU_RIGHT, value).apply()

    /** The resolved cursor color (ARGB int) for the current index. */
    val cursorColor: Int get() = CURSOR_COLORS[cursorColorIndex]

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        private const val PREFS_NAME = "openmouse_settings"

        private const val KEY_DWELL_ENABLED = "dwell_enabled"
        private const val KEY_DWELL_MS = "dwell_ms"
        private const val KEY_MOVE_DP = "move_dp"
        private const val KEY_CURSOR_SCALE = "cursor_scale"
        private const val KEY_CURSOR_COLOR = "cursor_color"
        private const val KEY_MENU_RIGHT = "menu_right"

        const val DEFAULT_DWELL_MS = 1000
        const val MIN_DWELL_MS = 400
        const val MAX_DWELL_MS = 2500

        const val DEFAULT_MOVE_DP = 8f
        const val MIN_MOVE_DP = 4f
        const val MAX_MOVE_DP = 24f

        const val DEFAULT_CURSOR_SCALE = 1.0f
        const val MIN_CURSOR_SCALE = 0.6f
        const val MAX_CURSOR_SCALE = 1.8f

        /**
         * Cursor color presets; the chosen index is stored in prefs. Keep this in
         * the same order as the `cursor_color_names` string-array.
         */
        val CURSOR_COLORS = listOf(
            0xFFFFEB3B.toInt(), // yellow
            0xFF00E676.toInt(), // green
            0xFF18FFFF.toInt(), // cyan
            0xFFFF4081.toInt(), // magenta
            0xFFFFFFFF.toInt(), // white
            0xFFFFAB40.toInt(), // orange
        )
    }
}
