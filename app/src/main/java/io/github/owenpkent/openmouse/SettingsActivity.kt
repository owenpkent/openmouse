package io.github.owenpkent.openmouse

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import io.github.owenpkent.openmouse.databinding.ActivitySettingsBinding
import io.github.owenpkent.openmouse.settings.OpenMouseSettings

/**
 * Settings screen. Every control writes straight to [OpenMouseSettings] on
 * change; there is no Save button. The running accessibility service listens for
 * those preference changes and re-applies them live, so adjustments take effect
 * immediately while the cursor is on screen.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: OpenMouseSettings
    private val colorButtons = mutableListOf<RadioButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = OpenMouseSettings(this)

        setupDwellToggle()
        setupSliders()
        setupColors()
        setupMenuSide()
    }

    private fun setupDwellToggle() {
        binding.dwellEnabledSwitch.isChecked = settings.dwellEnabled
        binding.dwellEnabledSwitch.setOnCheckedChangeListener { _, checked ->
            settings.dwellEnabled = checked
        }
    }

    private fun setupSliders() {
        binding.dwellSlider.value = settings.dwellTimeMs.toFloat()
        updateDwellLabel(settings.dwellTimeMs)
        binding.dwellSlider.addOnChangeListener { _, value, _ ->
            settings.dwellTimeMs = value.toLong()
            updateDwellLabel(value.toLong())
        }

        binding.moveSlider.value = settings.moveThresholdDp
        updateMoveLabel(settings.moveThresholdDp)
        binding.moveSlider.addOnChangeListener { _, value, _ ->
            settings.moveThresholdDp = value
            updateMoveLabel(value)
        }

        binding.sizeSlider.value = settings.cursorScale * 100f
        updateSizeLabel(settings.cursorScale)
        binding.sizeSlider.addOnChangeListener { _, value, _ ->
            settings.cursorScale = value / 100f
            updateSizeLabel(value / 100f)
        }
    }

    private fun updateDwellLabel(ms: Long) {
        binding.dwellLabel.text = getString(R.string.settings_dwell_time) +
            "   " + getString(R.string.settings_seconds, ms / 1000f)
    }

    private fun updateMoveLabel(dp: Float) {
        binding.moveLabel.text = getString(R.string.settings_move_tolerance) +
            "   " + getString(R.string.settings_dp, dp.toInt())
    }

    private fun updateSizeLabel(scale: Float) {
        binding.sizeLabel.text = getString(R.string.settings_cursor_size) +
            "   " + getString(R.string.settings_percent, (scale * 100).toInt())
    }

    private fun setupColors() {
        val names = resources.getStringArray(R.array.cursor_color_names)
        OpenMouseSettings.CURSOR_COLORS.forEachIndexed { index, color ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = names.getOrElse(index) { "Color ${index + 1}" }
                textSize = 16f
                buttonTintList = ColorStateList.valueOf(color)
            }
            binding.colorGroup.addView(button)
            colorButtons.add(button)
        }
        binding.colorGroup.check(colorButtons[settings.cursorColorIndex].id)
        binding.colorGroup.setOnCheckedChangeListener { _, checkedId ->
            val index = colorButtons.indexOfFirst { it.id == checkedId }
            if (index >= 0) settings.cursorColorIndex = index
        }
    }

    private fun setupMenuSide() {
        if (settings.menuOnRight) {
            binding.menuSideRight.isChecked = true
        } else {
            binding.menuSideLeft.isChecked = true
        }
        binding.menuSideGroup.setOnCheckedChangeListener { _, checkedId ->
            settings.menuOnRight = checkedId == R.id.menuSideRight
        }
    }
}
