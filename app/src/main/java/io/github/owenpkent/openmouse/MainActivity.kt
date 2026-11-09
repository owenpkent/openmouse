package io.github.owenpkent.openmouse

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.owenpkent.openmouse.service.MouseAccessibilityService
import io.github.owenpkent.openmouse.settings.OpenMouseSettings
import io.github.owenpkent.openmouse.update.LaunchCheck
import io.github.owenpkent.openmouse.update.UpdateChecker
import io.github.owenpkent.openmouse.update.UpdatePrompt

/**
 * Onboarding screen. OpenMouse has no UI of its own once running -- the cursor
 * lives in an accessibility overlay -- so this activity just explains the app
 * and sends the user to the accessibility settings to enable the service.
 *
 * Uses plain findViewById (not viewBinding) so the same sources build under both
 * Gradle and the AOSP/Soong platform build (see Android.bp / platform/).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.openSettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.appSettingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.refreshButton).setOnClickListener { updateStatus() }

        maybeCheckForUpdates()
    }

    /**
     * The launch check. It runs once per process, on by default unless the user
     * turned it off in Settings (see [LaunchCheck]), and stays quiet unless a
     * newer release exists: "up to date" and failures are not worth
     * interrupting the onboarding screen for. The build flag removes it entirely
     * from F-Droid and Play builds.
     */
    private fun maybeCheckForUpdates() {
        // The constant flag first, so R8 drops the whole path (UpdateChecker
        // included) from builds compiled with -PopenmouseUpdater=false.
        if (!BuildConfig.UPDATER_ENABLED) return
        val stored = OpenMouseSettings(this).storedUpdateCheckOnLaunch
        if (!LaunchCheck.shouldRun(BuildConfig.UPDATER_ENABLED, stored, checkedThisProcess)) return
        checkedThisProcess = true
        val checker = UpdateChecker(this)
        checker.check { result ->
            if (isFinishing || isDestroyed) return@check
            if (result is UpdateChecker.Result.Available) {
                UpdatePrompt.show(this, checker, result.release)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        statusText.setText(
            if (isServiceEnabled()) R.string.status_enabled else R.string.status_disabled,
        )
    }

    private fun isServiceEnabled(): Boolean {
        val expectedId = "$packageName/${MouseAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedId, ignoreCase = true)) return true
        }
        return false
    }

    companion object {
        /** Set once the launch check has run, so rotation or re-opening does not repeat it. */
        private var checkedThisProcess = false
    }
}
