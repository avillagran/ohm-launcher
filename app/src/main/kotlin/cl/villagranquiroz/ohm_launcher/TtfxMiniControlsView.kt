package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

object TtfxMiniEditorLogic {
    fun previous(current: TtfxConfig, effects: List<String>): TtfxConfig = cycle(current, effects, -1)
    fun next(current: TtfxConfig, effects: List<String>): TtfxConfig = cycle(current, effects, 1)

    fun position(current: TtfxConfig, x: Double, y: Double): TtfxConfig = current.copy(
        textX = x.coerceIn(0.0, 1.0),
        textY = y.coerceIn(0.0, 1.0),
    )

    fun textSize(current: TtfxConfig, size: Int): TtfxConfig =
        current.copy(textSize = size.coerceIn(1, 12))

    private fun cycle(current: TtfxConfig, effects: List<String>, delta: Int): TtfxConfig {
        if (effects.isEmpty()) return current
        val index = effects.indexOf(current.effect).takeIf { it >= 0 } ?: 0
        val next = (index + delta).mod(effects.size)
        return current.copy(effect = effects[next])
    }
}

/** Compact on-desktop TTFX editor equivalent to Flutter's mini controls. */
class TtfxMiniControlsView(context: Context) : LinearLayout(context) {
    var onPreview: ((TtfxConfig) -> Unit)? = null
    var onCommit: ((TtfxConfig) -> Unit)? = null
    private var current = TtfxConfig.parse(org.json.JSONObject())
    private val collapsedButton = button("✦", "Abrir editor TTFX en miniatura") { setExpanded(true) }
    private val panel = LinearLayout(context)
    private val effectLabel = TextView(context)
    private val sizeLabel = TextView(context)
    private val xLabel = TextView(context)
    private val yLabel = TextView(context)
    private val sizeBar = SeekBar(context)
    private val xBar = SeekBar(context)
    private val yBar = SeekBar(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.START
        elevation = dp(14).toFloat()
        addView(collapsedButton, LayoutParams(dp(44), dp(44)))
        buildPanel()
        addView(panel, LayoutParams(dp(250), ViewGroup.LayoutParams.WRAP_CONTENT))
        setExpanded(false)
    }

    fun submit(value: TtfxConfig) {
        current = value
        effectLabel.text = value.effect
        sizeBar.progress = (value.textSize - 1).coerceIn(0, 11)
        xBar.progress = (value.textX * 20).roundToInt().coerceIn(0, 20)
        yBar.progress = (value.textY * 20).roundToInt().coerceIn(0, 20)
        updateLabels()
        visibility = if (value.enabled) View.VISIBLE else View.GONE
    }

    private fun buildPanel() {
        panel.apply {
            orientation = VERTICAL
            setPadding(dp(7), dp(6), dp(7), dp(7))
            background = rounded(0xEE10161C.toInt(), dp(15).toFloat(), 0x8866E0FF.toInt())
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹", "Efecto anterior") {
            current = TtfxMiniEditorLogic.previous(current, TtfxSettingsDialog.effects)
            publish(commit = true)
        })
        effectLabel.apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFE8F1F8.toInt())
            textSize = 10f
            maxLines = 1
            typeface = Typeface.MONOSPACE
        }
        header.addView(effectLabel, LayoutParams(0, dp(36), 1f))
        header.addView(button("›", "Efecto siguiente") {
            current = TtfxMiniEditorLogic.next(current, TtfxSettingsDialog.effects)
            publish(commit = true)
        })
        header.addView(button("×", "Cerrar editor TTFX en miniatura") { setExpanded(false) })
        panel.addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        panel.addView(controlRow(sizeLabel, sizeBar, 11, context.getString(R.string.ttfx_size)))
        panel.addView(controlRow(xLabel, xBar, 20, context.getString(R.string.ttfx_position_accessibility, "X")))
        panel.addView(controlRow(yLabel, yBar, 20, context.getString(R.string.ttfx_position_accessibility, "Y")))
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                current = if (bar === sizeBar) {
                    TtfxMiniEditorLogic.textSize(current, sizeBar.progress + 1)
                } else {
                    TtfxMiniEditorLogic.position(current, xBar.progress / 20.0, yBar.progress / 20.0)
                }
                updateLabels()
                publish(commit = false)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) {
                onCommit?.invoke(current)
            }
        }
        sizeBar.setOnSeekBarChangeListener(listener)
        xBar.setOnSeekBarChangeListener(listener)
        yBar.setOnSeekBarChangeListener(listener)
    }

    private fun controlRow(valueLabel: TextView, bar: SeekBar, maximum: Int, description: String): LinearLayout =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            valueLabel.apply {
                setTextColor(0xFF9FB3C8.toInt())
                textSize = 9f
                typeface = Typeface.MONOSPACE
            }
            addView(valueLabel, LayoutParams(dp(42), dp(34)))
            bar.max = maximum
            bar.contentDescription = description
            addView(bar, LayoutParams(0, dp(34), 1f))
        }

    private fun updateLabels() {
        sizeLabel.text = "S ${current.textSize}"
        xLabel.text = "X ${(current.textX * 100).roundToInt()}"
        yLabel.text = "Y ${(current.textY * 100).roundToInt()}"
    }

    private fun publish(commit: Boolean) {
        effectLabel.text = current.effect
        onPreview?.invoke(current)
        if (commit) onCommit?.invoke(current)
    }

    private fun setExpanded(expanded: Boolean) {
        collapsedButton.visibility = if (expanded) View.GONE else View.VISIBLE
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        animate().alpha(1f).setDuration(140).start()
    }

    private fun button(label: String, description: String, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        contentDescription = description
        gravity = Gravity.CENTER
        setTextColor(0xFF66E0FF.toInt())
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(0xDD151D26.toInt(), dp(12).toFloat(), 0x7766E0FF)
        setOnClickListener { action() }
        layoutParams = LayoutParams(dp(38), dp(36))
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = OmarchyThemeShapeState.surfaceRadiusPx(radius, resources.displayMetrics.density)
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
