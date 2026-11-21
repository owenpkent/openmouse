package io.github.owenpkent.openmouse

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.owenpkent.openmouse.settings.OpenMouseSettings
import io.github.owenpkent.openmouse.settings.SliderGrid
import io.github.owenpkent.openmouse.update.UpdateChecker
import io.github.owenpkent.openmouse.update.UpdatePrompt

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
    private lateinit var postActionLabel: TextView
    private lateinit var repeatLabel: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var cursorOpacityLabel: TextView
    private lateinit var dockOpacityLabel: TextView

    private var updateChecker: UpdateChecker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = OpenMouseSettings(this)

        dwellLabel = findViewById(R.id.dwellLabel)
        moveLabel = findViewById(R.id.moveLabel)
        postActionLabel = findViewById(R.id.postActionLabel)
        repeatLabel = findViewById(R.id.repeatLabel)
        sizeLabel = findViewById(R.id.sizeLabel)
        cursorOpacityLabel = findViewById(R.id.cursorOpacityLabel)
        dockOpacityLabel = findViewById(R.id.dockOpacityLabel)

        setupDwellToggle()
        setupSliders()
        setupColors()
        setupMenuSide()
        setupUpdates()
    }

    private fun setupDwellToggle() {
        val toggle = findViewById<SwitchMaterial>(R.id.dwellEnabledSwitch)
        toggle.isChecked = settings.dwellEnabled
        toggle.setOnCheckedChangeListener { _, checked -> settings.dwellEnabled = checked }
    }

    private fun setupSliders() {
        findViewById<Slider>(R.id.dwellSlider).bind(
            settings.dwellTimeMs.toFloat(),
            show = { updateDwellLabel(it.toLong()) },
            write = { settings.dwellTimeMs = it.toLong() },
        )
        findViewById<Slider>(R.id.moveSlider).bind(
            settings.moveThresholdDp,
            show = ::updateMoveLabel,
            write = { settings.moveThresholdDp = it },
        )
        findViewById<Slider>(R.id.postActionSlider).bind(
            settings.postActionThresholdDp,
            show = ::updatePostActionLabel,
            write = { settings.postActionThresholdDp = it },
        )
        findViewById<Slider>(R.id.repeatSlider).bind(
            settings.scrollRepeatMs.toFloat(),
            show = { updateRepeatLabel(it.toLong()) },
            write = { settings.scrollRepeatMs = it.toLong() },
        )
        findViewById<Slider>(R.id.sizeSlider).bind(
            settings.cursorScale * 100f,
            show = { updateSizeLabel(it / 100f) },
            write = { settings.cursorScale = it / 100f },
        )
        findViewById<Slider>(R.id.cursorOpacitySlider).bind(
            settings.cursorOpacity * 100f,
            show = { updateOpacityLabel(cursorOpacityLabel, R.string.settings_cursor_opacity_value, it) },
            write = { settings.cursorOpacity = it / 100f },
        )
        findViewById<Slider>(R.id.dockOpacitySlider).bind(
            settings.dockOpacity * 100f,
            show = { updateOpacityLabel(dockOpacityLabel, R.string.settings_dock_opacity_value, it) },
            write = { settings.dockOpacity = it / 100f },
        )
    }

    /**
     * Shows [stored] and writes every change through. Every value is snapped to
     * the slider's own tick grid first ([SliderGrid]): a Slider throws on an
     * off-step value, and an accessibility service's set-progress action skips
     * step snapping, so 35% would be saved and Settings would crash on reopen.
     * An off-grid change is put back on the grid, which re-enters this listener
     * with the snapped value, so only grid values are ever shown or stored.
     */
    private fun Slider.bind(stored: Float, show: (Float) -> Unit, write: (Float) -> Unit) {
        value = SliderGrid.snap(stored, valueFrom, valueTo, stepSize)
        show(value)
        addOnChangeListener { slider, changed, _ ->
            val snapped = SliderGrid.snap(changed, slider.valueFrom, slider.valueTo, slider.stepSize)
            if (snapped != changed) {
                slider.value = snapped
            } else {
                write(changed)
                show(changed)
            }
        }
    }

    private fun updateDwellLabel(ms: Long) {
        dwellLabel.text = getString(R.string.settings_dwell_value, ms / 1000f)
    }

    private fun updateMoveLabel(dp: Float) {
        moveLabel.text = getString(R.string.settings_move_value, dp.toInt())
    }

    private fun updatePostActionLabel(dp: Float) {
        postActionLabel.text = getString(R.string.settings_post_action_value, dp.toInt())
    }

    private fun updateRepeatLabel(ms: Long) {
        repeatLabel.text = getString(R.string.settings_scroll_repeat_value, ms.toInt())
    }

    private fun updateOpacityLabel(label: TextView, format: Int, percent: Float) {
        label.text = getString(format, Math.round(percent))
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

    /**
     * The Updates section is the app's only network feature. The launch check
     * is on by default and this switch turns it off (an explicit off is kept);
     * the button acts only when pressed. The section disappears entirely from
     * builds that compile the updater out.
     */
    private fun setupUpdates() {
        val section = findViewById<View>(R.id.updatesSection)
        if (!BuildConfig.UPDATER_ENABLED) {
            section.visibility = View.GONE
            return
        }
        val checker = UpdateChecker(this)
        updateChecker = checker
        val status = findViewById<TextView>(R.id.updateStatus)

        val toggle = findViewById<SwitchMaterial>(R.id.updateCheckOnLaunchSwitch)
        toggle.isChecked = settings.updateCheckOnLaunch
        toggle.setOnCheckedChangeListener { _, checked -> settings.updateCheckOnLaunch = checked }

        val button = findViewById<Button>(R.id.checkForUpdatesButton)
        button.setOnClickListener {
            button.isEnabled = false
            status.text = getString(R.string.update_status_checking)
            checker.check { result ->
                if (isFinishing || isDestroyed) return@check
                button.isEnabled = true
                when (result) {
                    is UpdateChecker.Result.UpToDate ->
                        status.text = getString(R.string.update_status_up_to_date, result.current)
                    is UpdateChecker.Result.Failed ->
                        status.text = getString(R.string.update_status_failed, result.message)
                    is UpdateChecker.Result.Available -> {
                        status.text = getString(R.string.update_status_available, result.release.version)
                        UpdatePrompt.show(this, checker, result.release) { status.text = it }
                    }
                }
            }
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
