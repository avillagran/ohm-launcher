package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

object OmarchyNotifyPresenter {
    fun visible(messages: List<OmarchyNotification>, maxMessages: Int): List<OmarchyNotification> =
        messages.takeLast(maxMessages.coerceIn(1, 20)).asReversed()
}

class OmarchyNotifyView(
    context: Context,
    messages: List<OmarchyNotification>,
    maxMessages: Int,
    accent: Int,
    surface: Int,
    foreground: Int,
    muted: Int,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(withAlpha(surface, 0xD9), dp(16).toFloat(), withAlpha(accent, 0x88))

        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = NerdGlyph.BELL
                typeface = NerdFont.load(context)
                textSize = 17f
                setTextColor(accent)
                gravity = Gravity.CENTER
            }, LayoutParams(dp(34), dp(30)))
            addView(TextView(context).apply {
                text = context.getString(R.string.omarchy_notify)
                typeface = Typeface.DEFAULT_BOLD
                textSize = 13f
                setTextColor(foreground)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LayoutParams(0, dp(30), 1f))
        })

        val visible = OmarchyNotifyPresenter.visible(messages, maxMessages)
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            if (visible.isEmpty()) {
                addView(TextView(context).apply {
                    text = context.getString(R.string.omarchy_notify_empty)
                    textSize = 12f
                    setTextColor(muted)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(18), 0, dp(18))
                }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                visible.forEach { notification -> addView(messageCard(notification, accent, foreground, muted)) }
            }
        }
        addView(ScrollView(context).apply {
            isFillViewport = false
            addView(content)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun messageCard(notification: OmarchyNotification, accent: Int, foreground: Int, muted: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(withAlpha(levelColor(notification.level, accent), 0x22), dp(10).toFloat(), withAlpha(levelColor(notification.level, accent), 0x55))
            addView(TextView(context).apply {
                text = notification.title
                typeface = Typeface.DEFAULT_BOLD
                textSize = 12f
                maxLines = 1
                setTextColor(levelColor(notification.level, accent))
            })
            addView(TextView(context).apply {
                text = notification.message
                textSize = 12f
                maxLines = 3
                setTextColor(foreground)
            })
            addView(TextView(context).apply {
                text = "${notification.source} · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(notification.timestamp))}"
                textSize = 9f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(muted)
            })
        }.also {
            it.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }

    private fun levelColor(level: String, accent: Int): Int = when (level) {
        "success" -> 0xFF50E3A4.toInt()
        "warning" -> 0xFFFFC857.toInt()
        "error" -> 0xFFFF6B7A.toInt()
        else -> accent
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
        setStroke(dp(1), stroke)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
