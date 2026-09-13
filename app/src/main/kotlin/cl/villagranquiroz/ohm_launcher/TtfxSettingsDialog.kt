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
            background = rounded(0xFF090D12.toInt(), dp(18).toFloat(), 0x8066E0FF.toInt(), dp(1))
            clipToOutline = true
            addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(176)))
            addView(TextView(context).apply {
                text = "VISTA PREVIA · EN VIVO"
                setTextColor(accent)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = rounded(0xCC101820.toInt(), dp(10).toFloat(), 0x5566E0FF, dp(1))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            })
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(12))
            addView(previewCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(176)).apply {
                bottomMargin = dp(16)
            })
        }
        column.addView(section(context, "COMPOSICIÓN", "Los cambios se aplican al escritorio mientras editas.", accent, foreground, muted))
        val enabled = SwitchCompat(context).apply {
            text = "Fondo TTFX"
            setTextColor(foreground)
            isChecked = current.enabled
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val text = EditText(context).apply {
            hint = "Texto de la animación"
            setText(current.text)
            setTextColor(foreground)
            setHintTextColor(muted)
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(0xFF151D26.toInt(), dp(14).toFloat(), 0x5566E0FF, dp(1))
        }
        val effect = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, effects).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(effects.indexOf(current.effect).coerceAtLeast(0))
            background = rounded(0xFF151D26.toInt(), dp(14).toFloat(), 0x5566E0FF, dp(1))
        }
        val audio = SwitchCompat(context).apply {
            this.text = "Reaccionar al audio"
            setTextColor(foreground)
            isChecked = current.audio
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        column.addView(enabled)
        column.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { bottomMargin = dp(10) })
        column.addView(labeled(context, "Efecto", effect, foreground, muted))
        val size = seek(context, column, "Tamaño", 1, 7, current.textSize, foreground, muted)
        val resolution = seek(context, column, "Resolución · mayor es más rápida", 1, 8, current.resolution, foreground, muted)
        val speed = seek(context, column, "Velocidad", 2, 50, (current.speed * 10).toInt(), foreground, muted) { "${it / 10.0}×" }
        column.addView(section(context, "POSICIÓN", "Se actualiza sin reiniciar el motor TTFX.", accent, foreground, muted))
        val x = seek(context, column, "Posición X", 0, 100, (current.textX * 100).toInt(), foreground, muted) { "$it%" }
        val y = seek(context, column, "Posición Y", 0, 100, (current.textY * 100).toInt(), foreground, muted) { "$it%" }
        column.addView(section(context, "AUDIO", "Color e intensidad responden en tiempo real.", accent, foreground, muted))
        column.addView(audio)
        val intensity = seek(context, column, "Intensidad", 0, 10, current.intensity, foreground, muted)
        val reactivity = seek(context, column, "Reactividad", 0, 5, current.reactivity, foreground, muted)

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
        val dialog = AlertDialog.Builder(context)
            .setTitle("Editor TTFX")
            .setView(scroll)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                session.update(configFromControls())
                session.commit(onSave)
            }
            .create()
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            session.cancel()
        }
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

    private fun rounded(fill: Int, radius: Float, stroke: Int, strokeWidth: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
        setStroke(strokeWidth, stroke)
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
