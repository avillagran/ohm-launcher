package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.View
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.sqrt

internal fun requiresTtfxRestart(previous: TtfxConfig, next: TtfxConfig): Boolean =
    previous.enabled != next.enabled ||
        previous.effect != next.effect ||
        previous.text != next.text ||
        previous.textSize != next.textSize ||
        previous.speed != next.speed ||
        previous.resolution != next.resolution

internal object TtfxRenderGeneration {
    fun isCurrent(token: Int, current: Int): Boolean = token == current
}

internal fun drainTtfxErrors(input: java.io.InputStream) {
    runCatching { input.bufferedReader().use { it.readText() } }
}

class NativeTtfxView @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
) : View(context, attributes) {
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
    }
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    @Volatile private var process: Process? = null
    @Volatile private var frame: TtfxFrame? = null
    @Volatile private var error: String? = null
    @Volatile private var spectrum = AudioSpectrum.SILENCE
    @Volatile private var themeAccent: Int? = null
    private var config = TtfxConfig.parse(org.json.JSONObject())
    private val audioController = AudioSpectrumController(context) {
        spectrum = it
        postInvalidateOnAnimation()
    }

    fun submit(value: TtfxConfig) {
        if (config == value) return
        val mustRestart = requiresTtfxRestart(config, value)
        config = value
        contentDescription = "TTFX ${value.effect}; tamaño ${value.textSize}; resolución ${value.resolution}; velocidad ${value.speed}"
        if (value.audio && isAttachedToWindow) audioController.start() else audioController.stop()
        if (mustRestart) restart() else invalidate()
    }

    fun refreshAudioCapture() {
        if (config.audio && isAttachedToWindow) audioController.start()
    }

    fun submitTheme(palette: OmarchyThemePalette?) {
        themeAccent = palette?.color("accent")?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (config.audio) audioController.start()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && height > 0 && (width != oldWidth || height != oldHeight)) restart()
    }

    override fun onDetachedFromWindow() {
        audioController.stop()
        stopProcess()
        executor.shutdownNow()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!config.enabled) return
        val current = frame
        if (current == null) {
            error?.let {
                glyphPaint.color = 0xFFFF6B7A.toInt()
                glyphPaint.textSize = 12f * resources.displayMetrics.density * resources.configuration.fontScale
                canvas.drawText("TTFX: $it", 16f, height / 2f, glyphPaint)
            }
            return
        }
        val cellWidth = width.toFloat() / current.columns.coerceAtLeast(1)
        val cellHeight = height.toFloat() / current.rows.coerceAtLeast(1)
        val offsetX = (config.textX.toFloat() - .5f) * width
        val offsetY = (config.textY.toFloat() - .5f) * height
        canvas.save()
        canvas.translate(offsetX, offsetY)
        glyphPaint.textSize = cellHeight * .88f
        val metrics = glyphPaint.fontMetrics
        val baselineOffset = (cellHeight - metrics.bottom - metrics.top) / 2f
        current.cells.forEachIndexed { index, cell ->
            val symbol = cell.symbol ?: return@forEachIndexed
            val column = index % current.columns
            val row = index / current.columns
            val left = column * cellWidth
            val top = row * cellHeight
            val color = applyAudioTint(cell.color, column, current.columns)
            if (symbol == "█" || symbol == "▓" || symbol == "░") {
                blockPaint.color = color
                blockPaint.alpha = when (symbol) {
                    "▓" -> 190
                    "░" -> 105
                    else -> 255
                }
                canvas.drawRect(left, top, left + cellWidth + .5f, top + cellHeight + .5f, blockPaint)
            } else {
                glyphPaint.color = color
                canvas.drawText(symbol, left, top + baselineOffset, glyphPaint)
            }
        }
        canvas.restore()
    }

    private fun applyAudioTint(source: Int, column: Int, columns: Int): Int {
        val raw = if (source == Color.TRANSPARENT) Color.WHITE else source
        val base = themeAccent?.let { OmarchyThemeColor.tint(raw, it) } ?: raw
        if (!config.audio || spectrum.volume <= 0f) return base
        val bands = spectrum.bands
        val band = bands[((column.toFloat() / columns.coerceAtLeast(1)) * bands.size)
            .toInt().coerceIn(0, bands.lastIndex)]
        val response = (config.reactivity / 5f).coerceIn(0f, 1f)
        val amount = ((spectrum.volume * .35f + band * .65f) *
            (config.intensity / 10f) * (0.35f + response * .65f)).coerceIn(0f, 1f)
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[0] = (hsv[0] + amount * if (spectrum.beat) 24f else 10f) % 360f
        hsv[1] = (hsv[1] + amount * .2f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] + amount * .25f).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(base), hsv)
    }

    private fun restart() {
        val token = generation.incrementAndGet()
        stopProcess()
        frame = null
        error = null
        invalidate()
        if (!config.enabled || width <= 0 || height <= 0 || executor.isShutdown) return
        executor.execute { renderLoop(token, config) }
    }

    private fun renderLoop(token: Int, snapshot: TtfxConfig) {
        if (!TtfxRenderGeneration.isCurrent(token, generation.get())) return
        try {
            val directory = File(context.filesDir, "ttfx-native").apply { mkdirs() }
            val binary = extractBinary(directory)
            val columns = if (snapshot.text.trim().equals("omarchy", ignoreCase = true)) {
                OmarchyWordmark.canvasColumns(snapshot.resolution, snapshot.textSize)
            } else {
                (160.0 / sqrt(snapshot.resolution.coerceIn(1, 8).toDouble()))
                    .toInt().coerceIn(40, 120)
            }
            val cellWidth = width.toDouble() / columns
            val rows = (height / (cellWidth / .62 * 1.05)).toInt().coerceIn(12, 160)
            val inputText = if (snapshot.text.trim().equals("omarchy", ignoreCase = true)) {
                OmarchyWordmark.render(columns, rows, snapshot.textSize)
            } else {
                snapshot.text.ifBlank { "OHM" }
            }
            val input = File(directory, "input.txt").apply { writeText("$inputText\n") }
            val fps = (15 * snapshot.speed).toInt().coerceIn(5, 30)
            while (generation.get() == token && !Thread.currentThread().isInterrupted) {
                if (!TtfxRenderGeneration.isCurrent(token, generation.get())) return
                val command = listOf(
                    "/system/bin/linker64",
                    binary.absolutePath,
                    "--input-file", input.absolutePath,
                    "--frame-rate", fps.toString(),
                    "--canvas-width", columns.toString(),
                    "--canvas-height", rows.toString(),
                    "--ignore-terminal-dimensions",
                    "--final-text-bands",
                    "--anchor-canvas", "c",
                    "--anchor-text", "c",
                    "--parity-dump",
                    "--pace-dump",
                    engineEffect(snapshot.effect),
                )
                val child = ProcessBuilder(command)
                    .directory(directory)
                    .redirectErrorStream(false)
                    .start()
                process = child
                val stderr = Thread { drainTtfxErrors(child.errorStream) }.apply { start() }
                val reader = TtfxFrameReader(child.inputStream)
                var previous: String? = null
                while (generation.get() == token) {
                    val raw = reader.next() ?: break
                    if (raw == previous) continue
                    previous = raw
                    val parsed = TtfxFrameParser.parse(raw)
                    frame = parsed
                    postInvalidateOnAnimation()
                }
                val exit = child.waitFor()
                stderr.join(500)
                if (generation.get() != token) return
                if (exit != 0) error("TTFX exited with $exit")
            }
        } catch (throwable: Throwable) {
            if (generation.get() == token) {
                error = throwable.message ?: throwable.javaClass.simpleName
                postInvalidate()
            }
        } finally {
            process = null
        }
    }

    private fun extractBinary(directory: File): File {
        val asset = if (Build.SUPPORTED_ABIS.any { it == "x86_64" }) {
            "bin/ttfx-x86_64"
        } else {
            "bin/ttfx-aarch64"
        }
        val target = File(directory, "ttfx")
        val bytes = context.assets.open(asset).use { it.readBytes() }
        if (!target.exists() || target.length() != bytes.size.toLong()) target.writeBytes(bytes)
        target.setReadable(true, true)
        return target
    }

    private fun engineEffect(effect: String): String = when (effect) {
        "ttfx-matrix" -> "matrix"
        "ttfx-rain" -> "rain"
        else -> effect
    }

    private fun stopProcess() {
        val child = process ?: return
        process = null
        child.destroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && child.isAlive) child.destroyForcibly()
    }
}
