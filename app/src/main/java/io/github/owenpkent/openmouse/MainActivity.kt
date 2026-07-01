package io.github.owenpkent.openmouse

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.owenpkent.openmouse.service.MouseAccessibilityService

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
}
