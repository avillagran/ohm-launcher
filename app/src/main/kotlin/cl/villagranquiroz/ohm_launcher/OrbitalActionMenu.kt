package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

internal object NerdGlyph {
    const val LEFT = "\uF060"
    const val RIGHT = "\uF061"
    const val UP = "\uF062"
    const val DOWN = "\uF063"
    const val ADD = "\uF067"
    const val BOX = "\uF1B2"
    const val EDIT = "\uF044"
    const val SETTINGS = "\uF013"
    const val TUNE = "\uF1DE"
    const val LINK = "\uF0C1"
    const val RESTART = "\uF2F9"
    const val TRASH = "\uF1F8"
    const val DESKTOP = "\uF108"
    const val WIDGETS = "\uF009"
    const val PUZZLE = "\uF12E"
    const val BLUETOOTH = "\uF293"
    const val QR = "\uF029"
    const val CAMERA = "\uF030"
    const val HOME = "\uF015"
    const val HAND = "\uF256"
    const val STORAGE = "\uF0A0"
    const val CHECK = "\uF00C"
    const val SQUARE = "\uF0C8"
    const val CHECK_SQUARE = "\uF14A"
    const val EXPAND = "\uF065"
    const val COMPRESS = "\uF066"
    const val EYE = "\uF06E"
    const val EYE_SLASH = "\uF070"
    const val BELL = "\uF0F3"
    const val CLOSE = "\uF00D"
    const val MENU = "\uF0C9"
    const val PALETTE = "\uF53F"
    const val STYLE = "\uEBCF"
    const val THEME = "\uDB83\uDE0C"
    const val IMAGE = "\uF03E"
    const val SYNC = "\uF021"
    const val APPS = "\uDB80\uDC3B"
    const val SEARCH = "\uF002"
    const val STAR = "\uF005"
    const val STAR_EMPTY = "\uF006"
}

internal object NerdFont {
    fun load(context: Context): Typeface =
        Typeface.createFromAsset(context.assets, "fonts/SymbolsNerdFontMono-Regular.ttf")
}

internal data class OrbitalAction(
    val icon: String,
    val label: String,
    val closeOnInvoke: Boolean = true,
    val action: () -> Unit,
)

/** A two-ring action surface that avoids long modal lists. */
internal class OrbitalActionMenu(
    context: Context,
    private val actions: List<OrbitalAction>,
    private val accent: Int,
    backgroundAlpha: Int = 0xD9,
    dismissOnBackgroundTap: Boolean = true,
    private val anchorX: Float? = null,
    private val anchorY: Float? = null,
    private val closeAtRelease: Boolean = false,
    private val onDismissed: () -> Unit,
) : FrameLayout(context) {
    private val actionViews = mutableListOf<View>()
    private val nerdFont = NerdFont.load(context)
    private val closeButton = TextView(context)
    private var closeRevealScheduled = false

    val awaitsCloseRelease: Boolean
        get() = closeAtRelease && !closeRevealScheduled

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor((backgroundAlpha.coerceIn(0, 255) shl 24) or
            (OmarchyUiTheme.color("dark_background", 0xFF0B0F14.toInt()) and 0x00FFFFFF))
        setOnClickListener { if (dismissOnBackgroundTap) dismiss() }

        actions.forEachIndexed { index, item ->
            val button = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                contentDescription = item.label
                elevation = dp(if (index < 8) 12 else 7).toFloat()
                alpha = 0f
                scaleX = .55f
                scaleY = .55f
                isClickable = true
                setOnClickListener {
                    if (item.closeOnInvoke) dismiss(item.action) else item.action()
                }
                addView(TextView(context).apply {
                    text = item.icon
                    gravity = Gravity.CENTER
                    setTextColor(accent)
                    textSize = 22f
                    typeface = nerdFont
                    background = circle((OmarchyUiTheme.color("lighter_background", 0xFF1A2330.toInt()) and 0x00FFFFFF) or
                        (if (index < 8) 0xF2000000.toInt() else 0xE6000000.toInt()))
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(TextView(context).apply {
                    text = item.label
                    gravity = Gravity.CENTER
                    setTextColor(OmarchyUiTheme.color("muted", 0xFF9AA7B4.toInt()))
                    textSize = 10f
                    maxLines = 2
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
            }
            actionViews += button
            addView(button, LayoutParams(dp(if (index < 8) 104 else 94), dp(84)))
        }

        closeButton.apply {
            text = NerdGlyph.CLOSE
            contentDescription = context.getString(R.string.action_close)
            gravity = Gravity.CENTER
            setTextColor(accent)
            textSize = 24f
            typeface = nerdFont
            background = circle(OmarchyUiTheme.color("lighter_background", 0xFF101820.toInt()))
            elevation = dp(18).toFloat()
            setOnClickListener { dismiss() }
            if (closeAtRelease) {
                alpha = 0f
                scaleX = .25f
                scaleY = .25f
            }
        }
        addView(closeButton, LayoutParams(dp(74), dp(74), Gravity.CENTER))
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val points = OrbitalMenuGeometry.positions(
            count = actionViews.size,
            width = width,
            height = height,
            anchorX = anchorX,
            anchorY = anchorY,
            itemWidth = dp(104),
            itemHeight = dp(84),
        )
        actionViews.zip(points).forEachIndexed { index, (view, point) ->
            view.x = point.x - view.layoutParams.width / 2f
            view.y = point.y - view.layoutParams.height / 2f
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(OrbitalMenuMotion.actionDelayMillis(index))
                .setDuration(240)
                .start()
        }
    }

    fun revealCloseAtAnchor() {
        if (!closeAtRelease || closeRevealScheduled) return
        closeRevealScheduled = true
        post {
            val point = OrbitalMenuMotion.closeCenter(
                anchorX ?: width / 2f,
                anchorY ?: height / 2f,
                width,
                height,
                dp(74),
                dp(74),
            )
            closeButton.layoutParams = LayoutParams(dp(74), dp(74))
            closeButton.x = point.x - dp(37)
            closeButton.y = point.y - dp(37)
            closeButton.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(OrbitalMenuMotion.CLOSE_REVEAL_DELAY_MILLIS)
                .setDuration(260)
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

    fun close() = dismiss()

    private fun circle(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = OmarchyThemeShapeState.surfaceRadiusPx(
            dp(100).toFloat(),
            resources.displayMetrics.density,
        )
        setStroke(dp(1), (accent and 0x00FFFFFF) or 0x77000000)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
