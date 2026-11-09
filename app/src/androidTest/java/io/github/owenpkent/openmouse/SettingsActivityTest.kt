package io.github.owenpkent.openmouse

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.slider.Slider
import io.github.owenpkent.openmouse.settings.OpenMouseSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings screen: it shows what is stored, and every control writes
 * straight through to [OpenMouseSettings] with no Save step. Runs with the
 * service off, so nothing on screen can intercept Espresso's touches.
 */
@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {

    private val settings get() = OpenMouseSettings(Device.context)

    @Before
    fun setUp() {
        Device.wakeAndUnlock()
        if (OpenMouseService.current != null) OpenMouseService.disable()
        resetSettings()
    }

    @After
    fun tearDown() = resetSettings()

    @Test
    fun controlsShowTheStoredSettings() {
        resetSettings {
            dwellEnabled = false
            dwellTimeMs = 1500
            moveThresholdDp = 12f
            postActionThresholdDp = 30f
            cursorScale = 1.4f
            cursorColorIndex = 3
            menuOnRight = false
        }
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            onView(withId(R.id.dwellEnabledSwitch)).check(matches(isNotChecked()))
            onView(withId(R.id.dwellLabel)).check(matches(withText(string(R.string.settings_dwell_value, 1.5f))))
            onView(withId(R.id.moveLabel)).check(matches(withText(string(R.string.settings_move_value, 12))))
            onView(withId(R.id.postActionLabel)).check(matches(withText(string(R.string.settings_post_action_value, 30))))
            onView(withId(R.id.sizeLabel)).check(matches(withText(string(R.string.settings_size_value, 140))))
            onView(withId(R.id.menuSideLeft)).check(matches(isChecked()))
            onView(withText(colorName(3))).check(matches(isChecked()))

            scenario.onActivity { a ->
                assertEquals(1500f, a.slider(R.id.dwellSlider).value)
                assertEquals(12f, a.slider(R.id.moveSlider).value)
                assertEquals(30f, a.slider(R.id.postActionSlider).value)
                assertEquals(140f, a.slider(R.id.sizeSlider).value, 0.01f)
            }
        }
    }

    @Test
    fun togglesAndRadiosWriteStraightThrough() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.dwellEnabledSwitch)).perform(scrollTo(), click())
            assertEquals(false, settings.dwellEnabled)

            onView(withText(colorName(2))).perform(scrollTo(), click())
            assertEquals(2, settings.cursorColorIndex)

            onView(withId(R.id.menuSideLeft)).perform(scrollTo(), click())
            assertEquals(false, settings.menuOnRight)
            onView(withId(R.id.menuSideRight)).perform(scrollTo(), click())
            assertEquals(true, settings.menuOnRight)
        }
    }

    @Test
    fun touchingASliderWritesItsValue() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            // A real tap in the middle of the 60-180% track picks 120%.
            onView(withId(R.id.sizeSlider)).perform(scrollTo(), click())
            assertEquals(1.2f, settings.cursorScale, 0.001f)
            onView(withId(R.id.sizeLabel)).check(matches(withText(string(R.string.settings_size_value, 120))))
        }
    }

    @Test
    fun everySliderWritesItsValueAndLabel() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.slider(R.id.dwellSlider).value = 2000f
                a.slider(R.id.moveSlider).value = 20f
                a.slider(R.id.postActionSlider).value = 40f
            }
            assertEquals(2000L, settings.dwellTimeMs)
            assertEquals(20f, settings.moveThresholdDp)
            assertEquals(40f, settings.postActionThresholdDp)
            onView(withId(R.id.dwellLabel)).check(matches(withText(string(R.string.settings_dwell_value, 2.0f))))
            onView(withId(R.id.moveLabel)).check(matches(withText(string(R.string.settings_move_value, 20))))
            onView(withId(R.id.postActionLabel)).check(matches(withText(string(R.string.settings_post_action_value, 40))))
        }
    }

    @Test
    fun outOfRangeStoredValuesAreClampedNotFatal() {
        // A Slider throws if handed a value outside its range, so a corrupt or
        // stale preference must be clamped before it reaches the screen. Write
        // raw values, bypassing OpenMouseSettings' own clamping on write.
        Device.context.getSharedPreferences(OpenMouseSettings.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt("dwell_ms", 99_999)
            .putFloat("move_dp", -5f)
            .putFloat("post_action_dp", 1_000f)
            .putFloat("cursor_scale", 0f)
            .putInt("cursor_color", 42)
            .commit()

        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                assertEquals(OpenMouseSettings.MAX_DWELL_MS.toFloat(), a.slider(R.id.dwellSlider).value)
                assertEquals(OpenMouseSettings.MIN_MOVE_DP, a.slider(R.id.moveSlider).value)
                assertEquals(OpenMouseSettings.MAX_POST_ACTION_DP, a.slider(R.id.postActionSlider).value)
                assertEquals(OpenMouseSettings.MIN_CURSOR_SCALE * 100f, a.slider(R.id.sizeSlider).value, 0.01f)
            }
            val last = OpenMouseSettings.CURSOR_COLORS.size - 1
            onView(withText(colorName(last))).check(matches(isChecked()))
        }
    }

    private fun SettingsActivity.slider(id: Int): Slider = findViewById(id)

    private fun string(id: Int, vararg args: Any): String = Device.context.getString(id, *args)

    private fun colorName(index: Int): String =
        Device.context.resources.getStringArray(R.array.cursor_color_names)[index]
}
