package io.github.owenpkent.openmouse

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.owenpkent.openmouse.settings.OpenMouseSettings

/**
 * Settings screen. Every control writes straight to [OpenMouseSettings] on
 * change; there is no Save button. The running accessibility service listens for
 * those preference changes and re-applies them live, so adjustments take effect
 * immediately while the cursor is on screen.
 *
 * Uses plain findViewById (not viewBinding) so the same sources build under both
 * Gradle and the AOSP/Soong platform build.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: OpenMouseSettings
    private val colorButtons = mutableListOf<RadioButton>()

    private lateinit var dwellLabel: TextView
    private lateinit var moveLabel: TextView
    private lateinit var sizeLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = OpenMouseSettings(this)

        dwellLabel = findViewById(R.id.dwellLabel)
        moveLabel = findViewById(R.id.moveLabel)
        sizeLabel = findViewById(R.id.sizeLabel)

        setupDwellToggle()
        setupSliders()
        setupColors()
        setupMenuSide()
    }

    private fun setupDwellToggle() {
        val toggle = findViewById<SwitchMaterial>(R.id.dwellEnabledSwitch)
        toggle.isChecked = settings.dwellEnabled
        toggle.setOnCheckedChangeListener { _, checked -> settings.dwellEnabled = checked }
    }

    private fun setupSliders() {
        val dwell = findViewById<Slider>(R.id.dwellSlider)
        dwell.value = settings.dwellTimeMs.toFloat()
        updateDwellLabel(settings.dwellTimeMs)
        dwell.addOnChangeListener { _, value, _ ->
            settings.dwellTimeMs = value.toLong()
            updateDwellLabel(value.toLong())
        }

        val move = findViewById<Slider>(R.id.moveSlider)
        move.value = settings.moveThresholdDp
        updateMoveLabel(settings.moveThresholdDp)
        move.addOnChangeListener { _, value, _ ->
            settings.moveThresholdDp = value
            updateMoveLabel(value)
        }

        val size = findViewById<Slider>(R.id.sizeSlider)
        size.value = settings.cursorScale * 100f
        updateSizeLabel(settings.cursorScale)
        size.addOnChangeListener { _, value, _ ->
            settings.cursorScale = value / 100f
            updateSizeLabel(value / 100f)
        }
    }

    private fun updateDwellLabel(ms: Long) {
        dwellLabel.text = getString(R.string.settings_dwell_value, ms / 1000f)
    }

    private fun updateMoveLabel(dp: Float) {
        moveLabel.text = getString(R.string.settings_move_value, dp.toInt())
    }

    private fun updateSizeLabel(scale: Float) {
        sizeLabel.text = getString(R.string.settings_size_value, (scale * 100).toInt())
    }

    private fun setupColors() {
        val group = findViewById<RadioGroup>(R.id.colorGroup)
        val names = resources.getStringArray(R.array.cursor_color_names)
        OpenMouseSettings.CURSOR_COLORS.forEachIndexed { index, color ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = names.getOrElse(index) { "Color ${index + 1}" }
                textSize = 16f
                buttonTintList = ColorStateList.valueOf(color)
            }
            group.addView(button)
            colorButtons.add(button)
        }
        group.check(colorButtons[settings.cursorColorIndex].id)
        group.setOnCheckedChangeListener { _, checkedId ->
            val index = colorButtons.indexOfFirst { it.id == checkedId }
            if (index >= 0) settings.cursorColorIndex = index
        }
    }

    private fun setupMenuSide() {
        val group = findViewById<RadioGroup>(R.id.menuSideGroup)
        if (settings.menuOnRight) {
            findViewById<RadioButton>(R.id.menuSideRight).isChecked = true
        } else {
            findViewById<RadioButton>(R.id.menuSideLeft).isChecked = true
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            settings.menuOnRight = checkedId == R.id.menuSideRight
        }
    }
}
