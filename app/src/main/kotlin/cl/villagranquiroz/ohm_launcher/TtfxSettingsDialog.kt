package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.doAfterTextChanged

object TtfxSettingsDialog {
    val effects = listOf(
        "beams", "binarypath", "blackhole", "bouncyballs", "bubbles", "burn",
        "colorshift", "crumble", "decrypt", "errorcorrect", "expand", "fireworks",
        "highlight", "laseretch", "matrix", "middleout", "orbittingvolley", "overflow",
        "pour", "print", "rain", "randomsequence", "rings", "scattered", "slice",
        "slide", "smoke", "spotlights", "spray", "swarm", "sweep", "synthgrid",
        "thunderstorm", "unstable", "vhstape", "waves", "wipe",
    )

    fun show(
        context: Context,
        current: TtfxConfig,
        panelOpacity: Double = 0.86,
        onPreview: (TtfxConfig) -> Unit = {},
        onSave: (TtfxConfig) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val accent = 0xFF66E0FF.toInt()
        val foreground = 0xFFE8F1F8.toInt()
        val muted = 0xFF9FB3C8.toInt()
        val session = TtfxEditorSession(current, onPreview)
        val preview = NativeTtfxView(context)
        val previewCard = FrameLayout(context).apply {
            background = rounded(0xFF090D12.toInt(), dp(18).toFloat(), 0x8066E0FF.toInt(), dp(1), density)
            clipToOutline = true
            addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(136)))
            addView(TextView(context).apply {
                text = context.getString(R.string.ttfx_live_preview)
                setTextColor(accent)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = rounded(0xCC101820.toInt(), dp(10).toFloat(), 0x5566E0FF, dp(1), density)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            })
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(8))
            addView(previewCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(136)).apply {
                bottomMargin = dp(10)
            })
        }
        column.addView(section(context, context.getString(R.string.ttfx_composition), context.getString(R.string.ttfx_composition_help), accent, foreground, muted))
        val enabled = SwitchCompat(context).apply {
            text = context.getString(R.string.ttfx_background)
            setTextColor(foreground)
            isChecked = current.enabled
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val text = EditText(context).apply {
            hint = context.getString(R.string.ttfx_text_hint)
            setText(current.text)
            setTextColor(foreground)
            setHintTextColor(muted)
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(0xFF151D26.toInt(), dp(14).toFloat(), 0x5566E0FF, dp(1), density)
        }
        val effect = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, effects).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(effects.indexOf(current.effect).coerceAtLeast(0))
            background = rounded(0xFF151D26.toInt(), dp(14).toFloat(), 0x5566E0FF, dp(1), density)
        }
        val audio = SwitchCompat(context).apply {
            this.text = context.getString(R.string.ttfx_react_audio)
            setTextColor(foreground)
            isChecked = current.audio
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val floatingControls = SwitchCompat(context).apply {
            this.text = context.getString(R.string.ttfx_show_floating_controls)
            setTextColor(foreground)
            isChecked = current.controlsVisible
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        column.addView(enabled)
        column.addView(floatingControls)
        column.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { bottomMargin = dp(10) })
        column.addView(labeled(context, context.getString(R.string.ttfx_effect), effect, foreground, muted))
        val size = seek(context, column, context.getString(R.string.ttfx_size), 1, 12, current.textSize, foreground, muted)
        val resolution = seek(context, column, context.getString(R.string.ttfx_resolution), 1, 8, current.resolution, foreground, muted)
        val speed = seek(context, column, context.getString(R.string.ttfx_speed), 2, 50, (current.speed * 10).toInt(), foreground, muted) { "${it / 10.0}×" }
        column.addView(section(context, context.getString(R.string.ttfx_position), context.getString(R.string.ttfx_position_help), accent, foreground, muted))
        val x = seek(context, column, context.getString(R.string.ttfx_position_x), 0, 100, (current.textX * 100).toInt(), foreground, muted) { "$it%" }
        val y = seek(context, column, context.getString(R.string.ttfx_position_y), 0, 100, (current.textY * 100).toInt(), foreground, muted) { "$it%" }
        column.addView(section(context, context.getString(R.string.ttfx_audio), context.getString(R.string.ttfx_audio_help), accent, foreground, muted))
        column.addView(audio)
        val intensity = seek(context, column, context.getString(R.string.ttfx_intensity), 0, 10, current.intensity, foreground, muted)
        val reactivity = seek(context, column, context.getString(R.string.ttfx_reactivity), 0, 5, current.reactivity, foreground, muted)

        fun configFromControls() = TtfxConfig(
            enabled = enabled.isChecked,
            effect = effects[effect.selectedItemPosition.coerceIn(effects.indices)],
            text = text.text.toString().ifBlank { "OHM" },
            textSize = size.value,
            textX = x.value / 100.0,
            textY = y.value / 100.0,
            audio = audio.isChecked,
            intensity = intensity.value,
            speed = speed.value / 10.0,
            resolution = resolution.value,
            reactivity = reactivity.value,
            controlsVisible = floatingControls.isChecked,
        )
        val handler = Handler(Looper.getMainLooper())
        var ready = false
        val liveUpdate = Runnable {
            if (!ready) return@Runnable
            val value = configFromControls()
            preview.submit(value.copy(audio = false))
            session.update(value)
        }
        fun updateLive(debounce: Boolean = false) {
            handler.removeCallbacks(liveUpdate)
            handler.postDelayed(liveUpdate, if (debounce) 180L else 0L)
        }
        enabled.setOnCheckedChangeListener { _, _ -> updateLive() }
        floatingControls.setOnCheckedChangeListener { _, _ -> updateLive() }
        audio.setOnCheckedChangeListener { _, _ -> updateLive() }
        text.doAfterTextChanged { updateLive(debounce = true) }
        effect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = updateLive()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        listOf(size, resolution, speed, x, y, intensity, reactivity).forEach { control ->
            val restartsEngine = control in listOf(size, resolution, speed)
            control.onChanged = { updateLive(debounce = restartsEngine) }
            control.onStopped = { if (restartsEngine) updateLive() }
        }
        ready = true
        preview.submit(current.copy(audio = false))
        val scroll = ScrollView(context).apply { addView(column) }
        SettingsDialogSurface.constrainContent(context, scroll, panelOpacity)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.ttfx_editor)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                session.update(configFromControls())
                session.commit(onSave)
            }
            .create()
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            session.cancel()
        }
        dialog.setOnShowListener { SettingsDialogSurface.apply(dialog, panelOpacity) }
        dialog.show()
    }

    private class SeekControl(val bar: SeekBar, private val min: Int) {
        var onChanged: (() -> Unit)? = null
        var onStopped: (() -> Unit)? = null
        val value: Int get() = bar.progress + min
    }

    private fun seek(
        context: Context,
        parent: LinearLayout,
        name: String,
        min: Int,
        max: Int,
        value: Int,
        foreground: Int,
        muted: Int,
        format: (Int) -> String = Int::toString,
    ): SeekControl {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val label = TextView(context).apply { setTextColor(foreground); textSize = 13f }
        val valueLabel = TextView(context).apply { setTextColor(muted); textSize = 12f; gravity = Gravity.END }
        val seek = SeekBar(context).apply {
            this.max = max - min
            progress = value.coerceIn(min, max) - min
        }
        val control = SeekControl(seek, min)
        fun update() {
            label.text = name
            valueLabel.text = format(control.value)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                update()
                if (fromUser) control.onChanged?.invoke()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) {
                control.onStopped?.invoke()
            }
        })
        update()
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(valueLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(row)
        parent.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return control
    }

    private fun labeled(context: Context, title: String, child: android.view.View, foreground: Int, muted: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = title; setTextColor(muted); textSize = 11f })
            addView(child, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 48)))
        }

    private fun section(context: Context, title: String, subtitle: String, accent: Int, foreground: Int, muted: Int) =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 14), 0, dp(context, 8))
            addView(TextView(context).apply { text = title; setTextColor(accent); textSize = 11f; typeface = Typeface.DEFAULT_BOLD })
            addView(TextView(context).apply { text = subtitle; setTextColor(muted); textSize = 10f })
        }

    private fun rounded(fill: Int, radius: Float, stroke: Int, strokeWidth: Int, density: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = OmarchyThemeShapeState.surfaceRadiusPx(radius, density)
        setStroke(strokeWidth, stroke)
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
