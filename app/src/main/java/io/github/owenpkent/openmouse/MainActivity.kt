package io.github.owenpkent.openmouse

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.appcompat.app.AppCompatActivity
import io.github.owenpkent.openmouse.databinding.ActivityMainBinding
import io.github.owenpkent.openmouse.service.MouseAccessibilityService

/**
 * Onboarding screen. OpenMouse has no UI of its own once running -- the cursor
 * lives in an accessibility overlay -- so this activity just explains the app
 * and sends the user to the accessibility settings to enable the service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.openSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.refreshButton.setOnClickListener { updateStatus() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        binding.statusText.setText(
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
