package io.github.owenpkent.openmouse

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The onboarding screen: status line and navigation to settings. */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @Before
    fun setUp() {
        Device.wakeAndUnlock()
        resetSettings()
        if (OpenMouseService.current != null) OpenMouseService.disable()
    }

    @After
    fun tearDown() {
        if (OpenMouseService.current != null) OpenMouseService.disable()
    }

    @Test
    fun statusSaysOffWhileTheServiceIsDisabled() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.statusText)).check(matches(withText(R.string.status_disabled)))
        }
    }

    @Test
    fun statusSaysOnOnceTheServiceIsEnabled() {
        OpenMouseService.enable()
        // Relaunch rather than tap Refresh: on API 24-33 the cursor overlay is
        // touchable until its idle watchdog fires, so a tap could be captured.
        // onResume runs the same status check.
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.statusText)).check(matches(withText(R.string.status_enabled)))
        }
    }

    @Test
    fun refreshPicksUpAServiceDisabledElsewhere() {
        OpenMouseService.enable()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.statusText)).check(matches(withText(R.string.status_enabled)))
            // Turned off behind the screen's back; with no overlay up, Refresh
            // is reachable by a plain tap on every API level.
            OpenMouseService.disable()
            onView(withId(R.id.refreshButton)).perform(scrollTo(), click())
            onView(withId(R.id.statusText)).check(matches(withText(R.string.status_disabled)))
        }
    }

    @Test
    fun appSettingsButtonOpensSettings() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.appSettingsButton)).perform(scrollTo(), click())
            onView(withId(R.id.dwellEnabledSwitch)).check(matches(isDisplayed()))
        }
    }
}
