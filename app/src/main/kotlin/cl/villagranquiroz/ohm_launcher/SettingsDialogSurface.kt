package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlin.math.min
import kotlin.math.roundToInt

data class SettingsDialogSurfaceSpec(
    val widthPx: Int,
    val maxContentHeightPx: Int,
    val opacity: Float,
    val dimAmount: Float,
)

object SettingsDialogSurfacePolicy {
    fun resolve(screenWidthPx: Int, screenHeightPx: Int, density: Float, opacity: Double): SettingsDialogSurfaceSpec {
        val safeOpacity = opacity.coerceIn(0.5, 1.0).toFloat()
        return SettingsDialogSurfaceSpec(
            widthPx = min((screenWidthPx * 0.86f).roundToInt(), (560f * density).roundToInt()),
            maxContentHeightPx = min((screenHeightPx * 0.68f).roundToInt(), (680f * density).roundToInt()),
            opacity = safeOpacity,
            dimAmount = ((safeOpacity - 0.5f) * 0.36f).coerceIn(0f, 0.18f),
        )
    }
}

object SettingsDialogSurface {
    fun spec(context: Context, opacity: Double): SettingsDialogSurfaceSpec {
        val metrics = context.resources.displayMetrics
        return SettingsDialogSurfacePolicy.resolve(metrics.widthPixels, metrics.heightPixels, metrics.density, opacity)
    }

    fun constrainContent(context: Context, content: android.view.View, opacity: Double) {
        content.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            spec(context, opacity).maxContentHeightPx,
        )
    }

    fun apply(dialog: AlertDialog, opacity: Double) {
        val window = dialog.window ?: return
        val resolved = spec(dialog.context, opacity)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = OmarchyThemeShapeState.surfaceRadiusPx(
                22f * dialog.context.resources.displayMetrics.density,
                dialog.context.resources.displayMetrics.density,
            )
            val surface = OmarchyUiTheme.color("background", 0xFF090D12.toInt())
            setColor((surface and 0x00FFFFFF) or ((resolved.opacity * 255).roundToInt() shl 24))
            setStroke(
                (dialog.context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1),
                (OmarchyUiTheme.color("accent", 0xFF66E0FF.toInt()) and 0x00FFFFFF) or (110 shl 24),
            )
        }
        window.setBackgroundDrawable(background)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = resolved.dimAmount }
        window.setLayout(resolved.widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        applyContentColors(window.decorView)
    }

    private fun applyContentColors(view: View) {
        val foreground = OmarchyUiTheme.color("foreground", 0xFFE8F1F8.toInt())
        val muted = OmarchyUiTheme.color("muted", 0xFF9FB3C8.toInt())
        val accent = OmarchyUiTheme.color("accent", 0xFF66E0FF.toInt())
        if (view is TextView) {
            val color = when (view.currentTextColor) {
                0xFF66E0FF.toInt(), accent -> accent
                0xFF9FB3C8.toInt(), muted -> muted
                else -> foreground
            }
            view.setTextColor(color)
            if (view is EditText) view.setHintTextColor(muted)
        }
        if (view is SeekBar) {
            view.progressTintList = ColorStateList.valueOf(accent)
            view.thumbTintList = ColorStateList.valueOf(accent)
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) applyContentColors(view.getChildAt(index))
    }
}
