package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

internal data class OrbitalAction(val label: String, val action: () -> Unit)

/** A two-ring action surface that avoids long modal lists. */
internal class OrbitalActionMenu(
    context: Context,
    private val actions: List<OrbitalAction>,
    private val accent: Int,
    private val onDismissed: () -> Unit,
) : FrameLayout(context) {
    private val actionViews = mutableListOf<TextView>()

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(0xD90B0F14.toInt())
        setOnClickListener { dismiss() }

        actions.forEachIndexed { index, item ->
            val button = TextView(context).apply {
                text = item.label
                contentDescription = item.label
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = if (index < 8) 11f else 10f
                maxLines = 2
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = capsule(if (index < 8) 0xF21A2330.toInt() else 0xE6121820.toInt())
                elevation = dp(if (index < 8) 12 else 7).toFloat()
                alpha = 0f
                scaleX = .55f
                scaleY = .55f
                setOnClickListener {
                    dismiss(item.action)
                }
            }
            actionViews += button
            addView(button, LayoutParams(dp(if (index < 8) 116 else 104), dp(58)))
        }

        addView(TextView(context).apply {
            text = "OHM"
            gravity = Gravity.CENTER
            setTextColor(accent)
            textSize = 15f
            background = capsule(0xFF101820.toInt())
            elevation = dp(18).toFloat()
            setOnClickListener { dismiss() }
        }, LayoutParams(dp(74), dp(74), Gravity.CENTER))
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val points = OrbitalMenuGeometry.positions(actionViews.size, width, height)
        actionViews.zip(points).forEachIndexed { index, (view, point) ->
            view.x = point.x - view.layoutParams.width / 2f
            view.y = point.y - view.layoutParams.height / 2f
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay((index * 16L).coerceAtMost(160L))
                .setDuration(170)
                .start()
        }
    }

    private fun dismiss(after: () -> Unit = {}) {
        animate().alpha(0f).setDuration(120).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            onDismissed()
            after()
        }.start()
    }

    private fun capsule(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1), (accent and 0x00FFFFFF) or 0x77000000)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
