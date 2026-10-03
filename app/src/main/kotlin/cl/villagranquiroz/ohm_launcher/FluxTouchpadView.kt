package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView


/** Local-only gesture translation. The caller owns the authenticated session and device-credential gate. */
internal class FluxTouchpadGesturePolicy(
    private val authorized: () -> Boolean,
    private val emit: (FluxRemoteInputProtocol.Action) -> Unit,
) {
    private var closed = false
    private var touching = false
    private var held = false
    private var travelled = false
    private var x = 0f
    private var y = 0f
    private var startX = 0f
    private var startY = 0f

    private fun send(action: FluxRemoteInputProtocol.Action): Boolean {
        if (closed || !authorized()) return false
        emit(action)
        return true
    }

    fun down(px: Float, py: Float) {
        if (closed || !authorized() || !px.isFinite() || !py.isFinite()) return
        cancel()
        touching = true
        travelled = false
        x = px; y = py; startX = px; startY = py
    }

    fun move(px: Float, py: Float) {
        if (!touching || closed) return
        if (!authorized() || !px.isFinite() || !py.isFinite()) { cancel(); return }
        val dx = (px - x).toDouble()
        val dy = (py - y).toDouble()
        if (!dx.isFinite() || !dy.isFinite()) { cancel(); return }
        if (kotlin.math.abs(px - startX) > 8f || kotlin.math.abs(py - startY) > 8f) travelled = true
        x = px; y = py
        if (dx != 0.0 || dy != 0.0) send(FluxRemoteInputProtocol.move(dx.coerceIn(-2000.0, 2000.0), dy.coerceIn(-2000.0, 2000.0)))
    }

    fun hold() {
        if (touching && !held && !travelled && send(FluxRemoteInputProtocol.hold())) held = true
    }

    fun up(px: Float, py: Float) {
        if (!touching) return
        if (!px.isFinite() || !py.isFinite()) { cancel(); return }
        move(px, py)
        if (held) send(FluxRemoteInputProtocol.release())
        else if (!travelled) send(FluxRemoteInputProtocol.click(FluxRemoteInputProtocol.Click.LEFT))
        touching = false; held = false
    }

    fun cancel() {
        if (held) send(FluxRemoteInputProtocol.release())
        touching = false; held = false
    }

    /** Release while authorization is still live; the owner must revoke its gate after this call. */
    fun close() {
        if (closed) return
        cancel()
        closed = true
    }

    fun scroll(dx: Float, dy: Float) {
        if (dx.isFinite() && dy.isFinite() && (dx != 0f || dy != 0f))
            send(FluxRemoteInputProtocol.scroll(dx.toDouble().coerceIn(-2000.0, 2000.0),
                dy.toDouble().coerceIn(-2000.0, 2000.0)))
    }

    fun click(button: FluxRemoteInputProtocol.Click) { send(FluxRemoteInputProtocol.click(button)) }
    fun special(key: FluxRemoteInputProtocol.SpecialKey) { send(FluxRemoteInputProtocol.special(key)) }
    fun text(value: String) {
        if (!closed && authorized()) runCatching { FluxRemoteInputProtocol.text(value) }.getOrNull()?.let(::send)
    }
}

/** An attached, visible foreground control; never opens a socket or accepts inbound packets. */
internal class FluxTouchpadView(
    context: Context,
    private val palette: OmarchyThemePalette,
    private val labels: Labels,
    canSend: () -> Boolean,
    private val sendAction: (FluxRemoteInputProtocol.Action) -> Unit,
    private val onClosed: () -> Unit,
) : FrameLayout(context) {
    data class Labels(
        val title: String, val touchpad: String, val scroll: String,
        val left: String, val double: String, val right: String, val middle: String,
        val textHint: String, val send: String, val close: String,
        val enter: String, val backspace: String, val tab: String, val escape: String,
        val up: String, val down: String, val arrowLeft: String, val arrowRight: String,
    )

    private fun color(role: String, fallback: Int): Int = runCatching {
        Color.parseColor(palette.color(role))
    }.getOrDefault(fallback)
    private val backgroundColor = color("background", Color.rgb(24, 24, 24))
    private val surface = color("lighter_background", Color.rgb(38, 38, 38))
    private val foreground = color("foreground", Color.WHITE)
    private val muted = color("muted", Color.LTGRAY)
    private val accent = color("accent", Color.rgb(136, 192, 112))
    private val radius = OmarchyThemeShapePolicy.surfaceRadius(12f, palette)
    private val handler = Handler(Looper.getMainLooper())
    private val policy = FluxTouchpadGesturePolicy(
        { isAttachedToWindow && isShown && hasWindowFocus() && canSend() },
        { sendAction(it) },
    )
    private val holdRunnable = Runnable { policy.hold() }
    private var closed = false

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun panel(fill: Int, border: Int = accent) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1), border)
        cornerRadius = dp(radius.toInt()).toFloat()
    }
    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        contentDescription = label
        gravity = Gravity.CENTER
        setTextColor(this@FluxTouchpadView.foreground)
        typeface = Typeface.MONOSPACE
        textSize = 14f
        background = panel(surface, muted)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
    private fun row(vararg entries: Pair<String, () -> Unit>) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        entries.forEach { (label, action) ->
            addView(button(label, action), LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(4)
                bottomMargin = dp(5)
            })
        }
    }

    init {
        setBackgroundColor(backgroundColor)
        isFocusableInTouchMode = true
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.addView(row(labels.title to {}, labels.close to { close() }))
        column.addView(TextView(context).apply {
            text = labels.touchpad
            contentDescription = labels.touchpad
            gravity = Gravity.CENTER
            setTextColor(muted)
            typeface = Typeface.MONOSPACE
            background = panel(surface)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        handler.removeCallbacks(holdRunnable)
                        policy.down(event.x, event.y)
                        handler.postDelayed(holdRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        handler.removeCallbacks(holdRunnable)
                        policy.cancel()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (event.pointerCount == 1) policy.move(event.x, event.y)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(holdRunnable)
                        policy.up(event.x, event.y)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(holdRunnable)
                        policy.cancel()
                        true
                    }
                    else -> false
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(6) })
        column.addView(TextView(context).apply {
            text = labels.scroll
            contentDescription = labels.scroll
            gravity = Gravity.CENTER
            setTextColor(muted)
            typeface = Typeface.MONOSPACE
            background = panel(surface)
            var previousY = 0f
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { previousY = event.y; true }
                    MotionEvent.ACTION_MOVE -> {
                        policy.scroll(0f, event.y - previousY)
                        previousY = event.y
                        true
                    }
                    else -> event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { bottomMargin = dp(6) })
        column.addView(row(
            labels.left to { policy.click(FluxRemoteInputProtocol.Click.LEFT) },
            labels.double to { policy.click(FluxRemoteInputProtocol.Click.DOUBLE) },
            labels.right to { policy.click(FluxRemoteInputProtocol.Click.RIGHT) },
            labels.middle to { policy.click(FluxRemoteInputProtocol.Click.MIDDLE) },
        ))
        val input = EditText(context).apply {
            hint = labels.textHint
            contentDescription = labels.textHint
            setTextColor(this@FluxTouchpadView.foreground)
            setHintTextColor(muted)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = panel(surface)
            setPadding(dp(10), 0, dp(10), 0)
        }
        val textRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        textRow.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        textRow.addView(button(labels.send) {
            val value = input.text.toString()
            policy.text(value)
            input.text?.clear()
        }, LinearLayout.LayoutParams(dp(72), dp(48)))
        column.addView(textRow)
        column.addView(row(
            labels.escape to { policy.special(FluxRemoteInputProtocol.SpecialKey.ESCAPE) },
            labels.tab to { policy.special(FluxRemoteInputProtocol.SpecialKey.TAB) },
            labels.backspace to { policy.special(FluxRemoteInputProtocol.SpecialKey.BACKSPACE) },
            labels.enter to { policy.special(FluxRemoteInputProtocol.SpecialKey.ENTER) },
        ))
        column.addView(row(
            labels.arrowLeft to { policy.special(FluxRemoteInputProtocol.SpecialKey.LEFT) },
            labels.up to { policy.special(FluxRemoteInputProtocol.SpecialKey.UP) },
            labels.down to { policy.special(FluxRemoteInputProtocol.SpecialKey.DOWN) },
            labels.arrowRight to { policy.special(FluxRemoteInputProtocol.SpecialKey.RIGHT) },
        ))
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(holdRunnable)
        try { policy.close() } finally { onClosed() }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) {
            handler.removeCallbacks(holdRunnable)
            policy.cancel()
        }
        super.onWindowFocusChanged(hasWindowFocus)
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }
}
