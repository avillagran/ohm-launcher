package cl.villagranquiroz.ohm_launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Neon pole glow for edge drop targets while an edge box or launcher bar is
 * being dragged. Ports the Flutter `_PoleGlow` band (a wide gradient fading
 * from the screen edge toward the center) and animates a wave of light that
 * travels along the band: soft while dragging around, strong on the edge the
 * pointer is hovering (the drop target), tinted with the dragged box's accent.
 */
internal class EdgePoleGlowView(
    context: Context,
    val edge: EdgePosition,
) : View(context) {

    enum class GlowState { IDLE, SOURCE, DEST }

    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private var state = GlowState.IDLE
    private var accent = Color.WHITE
    private var phase = 0f

    /** One endless 0..1 cycle; per-state frequency derives the wave speed. */
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setGlow(state: GlowState, accent: Int) {
        val wasActive = this.state != GlowState.IDLE
        this.state = state
        this.accent = accent
        val active = state != GlowState.IDLE
        if (active && !wasActive) animator.start()
        if (!active && wasActive) animator.cancel()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val horizontal = edge == EdgePosition.TOP || edge == EdgePosition.BOTTOM
        drawBaseFade(canvas, horizontal)
        if (state != GlowState.IDLE) drawWave(canvas, horizontal)
    }

    /** Static gradient from the screen edge toward the center. */
    private fun drawBaseFade(canvas: Canvas, horizontal: Boolean) {
        val base = if (state == GlowState.DEST) accent else Color.WHITE
        val alpha = when (state) {
            GlowState.DEST -> .30f
            GlowState.SOURCE -> .18f
            GlowState.IDLE -> .08f
        }
        fadePaint.shader = edgeGradient(base, alpha)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)
    }

    /** A gaussian band of light sliding along the pole. Soft (SOURCE) vs
     *  strong (DEST): stronger peak, narrower band, faster travel. */
    private fun drawWave(canvas: Canvas, horizontal: Boolean) {
        val strong = state == GlowState.DEST
        val color = if (strong) accent else Color.WHITE
        val strength = if (strong) .55 else .22
        val bandWidth = if (strong) .16 else .30
        val cycles = if (strong) 2.2 else 1.0
        val offset = edge.ordinal * 1.7
        val angle = 2.0 * PI * (phase * cycles) + offset
        val center = (.5 + .42 * sin(angle)).toFloat()
        val stops = 13
        val positions = FloatArray(stops)
        val colors = IntArray(stops)
        for (i in 0 until stops) {
            val position = i / (stops - 1f)
            val distance = abs(position - center)
            val profile = exp(-((distance / bandWidth) * (distance / bandWidth)) * 3.0)
            val flicker = .85 + .15 * cos(angle * 2.0)
            positions[i] = position
            colors[i] = withAlpha(color, (strength * profile * flicker).toFloat())
        }
        wavePaint.shader = if (horizontal) {
            LinearGradient(0f, 0f, width.toFloat(), 0f, colors, positions, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, 0f, height.toFloat(), colors, positions, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), wavePaint)
    }

    private fun edgeGradient(color: Int, fraction: Float): Shader {
        val core = withAlpha(color, fraction)
        val fade = withAlpha(color, 0f)
        return when (edge) {
            EdgePosition.TOP -> LinearGradient(0f, 0f, 0f, height.toFloat(), core, fade, Shader.TileMode.CLAMP)
            EdgePosition.BOTTOM -> LinearGradient(0f, height.toFloat(), 0f, 0f, core, fade, Shader.TileMode.CLAMP)
            EdgePosition.LEFT -> LinearGradient(0f, 0f, width.toFloat(), 0f, core, fade, Shader.TileMode.CLAMP)
            EdgePosition.RIGHT -> LinearGradient(width.toFloat(), 0f, 0f, 0f, core, fade, Shader.TileMode.CLAMP)
        }
    }

    private fun withAlpha(color: Int, fraction: Float): Int {
        val alpha = (Color.alpha(color) * fraction).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }
}
