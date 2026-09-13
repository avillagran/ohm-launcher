package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.sin

/** Native particle clock compatible with Flutter's particles/Arrival clock style. */
class ParticleClockView(
    context: Context,
    private val format: String,
    color: Int,
    private val configuredTextSize: Float,
    private val samplingDensity: Float = 3f,
    private val particleSize: Float = 1.6f,
    private val wobble: Float = 1f,
) : View(context) {
    private val formatter = SimpleDateFormat(format, Locale.getDefault())
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val assignment = ParticleClockAssignment()
    private var displayedText = ""
    private var forceAll = true

    init {
        contentDescription = "Reloj de arena"
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        displayedText = ""
        forceAll = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        rebuildIfNeeded(now)
        var moving = false
        val seconds = now / 1000f
        assignment.pools.forEach { pool ->
            pool.grains.forEach { grain ->
                val dx = grain.targetX - grain.x
                val dy = grain.targetY - grain.y
                val distance = sqrt(dx * dx + dy * dy)
                if (distance > SETTLED_DISTANCE) {
                    val amplitude = wobble * (distance / 15f).coerceIn(0f, 1f)
                    grain.x += dx * .14f + sin(seconds * grain.speed + grain.phase) * .25f * amplitude
                    grain.y += dy * .14f + sin(seconds * grain.speed * .9f + grain.phase) * .2f * amplitude
                    moving = true
                } else {
                    grain.x = grain.targetX
                    grain.y = grain.targetY
                }
                grainPaint.alpha = (grain.opacity * 255).toInt().coerceIn(0, 255)
                canvas.drawCircle(
                    grain.x,
                    grain.y,
                    particleSize * resources.displayMetrics.density * grain.radiusScale,
                    grainPaint,
                )
            }
        }
        if (moving) {
            postInvalidateDelayed(FRAME_MILLIS)
        } else {
            postInvalidateDelayed((1000L - now % 1000L).coerceAtLeast(FRAME_MILLIS))
        }
    }

    private fun rebuildIfNeeded(now: Long) {
        if (width <= 0 || height <= 0) return
        val text = formatter.format(Date(now))
        if (text == displayedText) return
        displayedText = text
        assignment.assign(rasterGlyphs(text), forceAll = forceAll)
        forceAll = false
        contentDescription = "Reloj de arena $text"
    }

    private fun rasterGlyphs(text: String): List<ParticleGlyphSample> {
        val scaledDensity = resources.displayMetrics.density * resources.configuration.fontScale
        maskPaint.textSize = configuredTextSize * scaledDensity
        var widths = text.map { maskPaint.measureText(it.toString()).coerceAtLeast(1f) }
        var metrics = maskPaint.fontMetrics
        var glyphHeight = metrics.descent - metrics.ascent
        val naturalWidth = widths.sum().coerceAtLeast(1f)
        val fit = min(min(width / naturalWidth, height / glyphHeight.coerceAtLeast(1f)), MAX_UPSCALE)
        maskPaint.textSize *= fit
        widths = text.map { maskPaint.measureText(it.toString()).coerceAtLeast(1f) }
        metrics = maskPaint.fontMetrics
        glyphHeight = (metrics.descent - metrics.ascent).coerceAtLeast(1f)
        val totalWidth = widths.sum()
        var originX = (width - totalWidth) / 2f
        val originY = (height - glyphHeight) / 2f
        val stride = max(1, (samplingDensity * resources.displayMetrics.density).toInt())
        return text.mapIndexed { index, character ->
            val glyphWidth = max(1, ceil(widths[index].toDouble()).toInt())
            val bitmapHeight = max(1, ceil(glyphHeight.toDouble()).toInt())
            val bitmap = Bitmap.createBitmap(glyphWidth, bitmapHeight, Bitmap.Config.ALPHA_8)
            val glyphCanvas = Canvas(bitmap)
            glyphCanvas.drawText(character.toString(), 0f, -metrics.ascent, maskPaint)
            val points = ArrayList<ParticleTarget>()
            var y = 0
            while (y < bitmapHeight) {
                var x = 0
                while (x < glyphWidth) {
                    if (bitmap.getPixel(x, y) ushr 24 > 120) {
                        points += ParticleTarget(originX + x, originY + y)
                    }
                    x += stride
                }
                y += stride
            }
            bitmap.recycle()
            ParticleGlyphSample(character.toString(), points, originX + widths[index] / 2f).also {
                originX += widths[index]
            }
        }
    }

    private companion object {
        const val MAX_UPSCALE = 3f
        const val SETTLED_DISTANCE = .35f
        const val FRAME_MILLIS = 16L
    }
}
