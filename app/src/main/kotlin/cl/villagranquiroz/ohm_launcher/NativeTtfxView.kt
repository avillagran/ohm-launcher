package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.graphics.Bitmap
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
import kotlin.math.roundToInt

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

internal object TtfxTextLayoutPolicy {
    /** Canvas density comes from the resolution knob for every text, exactly
     *  like the Flutter reference: (160 / sqrt(resolution)).clamp(40, 120). */
    fun canvasColumns(resolution: Int): Int =
        (160.0 / kotlin.math.sqrt(resolution.coerceIn(1, 8).toDouble()))
            .roundToInt().coerceIn(40, 120)

    /** Bigger configured text wraps earlier so each line rasterizes larger
     *  instead of being crushed into a few illegible rows. */
    fun wrapChars(columns: Int, textSize: Int): Int =
        ((columns - 4) / (4 + textSize.coerceIn(1, 12) * 1.5)).toInt().coerceIn(3, 40)

    /** Rows of cell art per source line: (4 + textSize * 3), like Flutter. */
    fun targetRows(textSize: Int, lineCount: Int, canvasRows: Int): Int =
        ((4 + textSize.coerceIn(1, 12) * 3) * lineCount)
            .coerceIn(4, (canvasRows - 4).coerceAtLeast(4))
}

internal object TtfxEffectOptions {
    fun forEffect(effect: String, speed: Double): List<String> = when (effect) {
        "decrypt" -> listOf("--typing-speed", (speed * 30).toInt().coerceIn(12, 150).toString())
        else -> emptyList()
    }
}

internal object TtfxFrameRatePolicy {
    fun frameRate(isOmarchy: Boolean, speed: Double): Int =
        if (isOmarchy) (15 * speed).toInt().coerceIn(5, 30)
        else (30 * speed).toInt().coerceIn(30, 120)
}

internal object TtfxTextRasterizer {
    /** Converts arbitrary Unicode text to centered terminal-cell artwork,
     *  porting the Flutter reference: word-wrap first so long input grows
     *  instead of crushing into a few rows, rasterize at 96 px, then
     *  downsample with terminal-cell aspect compensation (0.62 width). */
    fun render(text: String, columns: Int, rows: Int, textSize: Int): String {
        val value = text.ifBlank { "OHM" }
        val wrappedLines = wrapLines(value, TtfxTextLayoutPolicy.wrapChars(columns, textSize))
        val wrapped = wrappedLines.joinToString("\n")
        val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.textSize = 96f
        }
        val width = wrappedLines.maxOf { paint.measureText(it) }
            .let { kotlin.math.ceil(it).toInt().coerceIn(1, 2048) }
        val layout = android.text.StaticLayout.Builder
            .obtain(wrapped, 0, wrapped.length, paint, width)
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()
        val height = layout.height.coerceIn(1, 2048)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        layout.draw(canvas)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val maxRows = (rows - 4).coerceAtLeast(4)
        val maxCols = (columns - 4).coerceAtLeast(4)
        var targetRows = TtfxTextLayoutPolicy.targetRows(textSize, wrappedLines.size, rows)
        var targetCols = (targetRows * width.toDouble() / height / .62)
            .roundToInt().coerceIn(4, maxCols)
        if (targetCols >= maxCols) {
            targetRows = (maxCols * height.toDouble() * .62 / width)
                .roundToInt().coerceIn(4, maxRows)
            targetCols = (columns - 4).coerceIn(4, columns)
        }
        val art = downsample(pixels, width, height, targetRows, targetCols)
        bitmap.recycle()
        return trimCellArt(art)
    }

    private fun wrapLines(input: String, maxChars: Int): List<String> {
        val lines = mutableListOf<String>()
        for (sourceLine in input.split('\n')) {
            var remaining = sourceLine
            while (remaining.length > maxChars) {
                var cut = remaining.lastIndexOf(' ', maxChars)
                if (cut < maxChars / 2) cut = maxChars
                lines += remaining.substring(0, cut).trimEnd()
                remaining = remaining.substring(cut).trimStart()
            }
            lines += remaining
        }
        return lines
    }

    private fun downsample(
        pixels: IntArray,
        width: Int,
        height: Int,
        targetRows: Int,
        targetCols: Int,
    ): String = buildString(targetRows * (targetCols + 1)) {
        for (row in 0 until targetRows) {
            val y0 = row * height / targetRows
            val y1 = max(y0 + 1, (row + 1) * height / targetRows)
            for (col in 0 until targetCols) {
                val x0 = col * width / targetCols
                val x1 = max(x0 + 1, (col + 1) * width / targetCols)
                var alpha = 0L
                var samples = 0
                for (y in y0 until y1) {
                    val offset = y * width
                    for (x in x0 until x1) {
                        alpha += Color.alpha(pixels[offset + x])
                        samples++
                    }
                }
                val coverage = if (samples == 0) 0.0 else alpha.toDouble() / (samples * 255)
                // Binary output, like the Omarchy wordmark: only full blocks or
                // empty cells. Half-density ▓/░ cells read as a smoothing filter
                // at these block sizes and make custom text look pixelated.
                append(if (coverage > .30) '█' else ' ')
            }
            if (row + 1 < targetRows) append('\n')
        }
    }

    private fun trimCellArt(art: String): String {
        val lines = art.split('\n').toMutableList()
        while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        if (lines.isEmpty()) return art
        val leftTrim = lines.filter { it.isNotBlank() }
            .minOf { it.length - it.trimStart().length }
        return lines.joinToString("\n") { it.substring(kotlin.math.min(leftTrim, it.length)) }
    }
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
    private val blockPaint = Paint()
    @Volatile private var process: Process? = null
    @Volatile private var frame: TtfxFrame? = null
    @Volatile private var error: String? = null
    @Volatile private var spectrum = AudioSpectrum.SILENCE
    @Volatile private var themeAccent: Int? = null
    private var config = TtfxConfig.parse(org.json.JSONObject())
    /** Engines that draw the view manually (live wallpaper) get every new frame here. */
    var onNewFrame: (() -> Unit)? = null
    private val audioController = AudioSpectrumController(context) {
        spectrum = it
        postInvalidateOnAnimation()
    }

    fun submit(value: TtfxConfig) {
        if (config == value) return
        val mustRestart = requiresTtfxRestart(config, value)
        config = value
        contentDescription = context.getString(
            R.string.ttfx_accessibility,
            value.effect,
            value.textSize,
            value.resolution,
            value.speed,
        )
        if (value.audio && isAttachedToWindow) audioController.start() else audioController.stop()
        if (mustRestart) restart() else invalidate()
    }

    fun refreshAudioCapture() {
        if (config.audio && isAttachedToWindow) audioController.start()
    }

    fun submitTheme(palette: OmarchyThemePalette?) {
        val defaultColor = palette
            ?.let { OmarchyDesktopTextPolicy.preferredColor(it.colors) }
            ?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        themeAccent = OmarchyUiTheme.widgetTextColor(defaultColor ?: Color.WHITE).takeIf { palette != null }
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
        release()
        super.onDetachedFromWindow()
    }

    /** Detiene o reanuda el render sin cambiar la config (visibilidad del wallpaper). */
    fun setRenderActive(active: Boolean) {
        if (active) restart()
        else {
            generation.incrementAndGet()
            stopProcess()
        }
    }

    /** Libera proceso y executor; obligatorio en vistas que nunca se adjuntan (wallpaper). */
    fun release() {
        generation.incrementAndGet()
        stopProcess()
        executor.shutdownNow()
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
            // Snap every cell to integer pixel boundaries: fractional bounds
            // antialias against each neighbor and make the pattern look like
            // it was scaled with a smoothing filter.
            val left = (column * cellWidth).roundToInt()
            val top = (row * cellHeight).roundToInt()
            val right = ((column + 1) * cellWidth).roundToInt()
            val bottom = ((row + 1) * cellHeight).roundToInt()
            val color = applyAudioTint(cell.color, column, current.columns)
            if (symbol == "█" || symbol == "▓" || symbol == "░") {
                blockPaint.color = color
                blockPaint.alpha = when (symbol) {
                    "▓" -> 190
                    "░" -> 105
                    else -> 255
                }
                canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), blockPaint)
            } else {
                glyphPaint.color = color
                canvas.drawText(symbol, left.toFloat(), top + baselineOffset, glyphPaint)
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
            val isOmarchy = snapshot.text.trim().equals("omarchy", ignoreCase = true)
            val columns = if (isOmarchy) {
                OmarchyWordmark.canvasColumns(snapshot.resolution, snapshot.textSize)
            } else {
                TtfxTextLayoutPolicy.canvasColumns(snapshot.resolution)
            }
            val cellWidth = width.toDouble() / columns
            val rows = (height / (cellWidth / .62 * 1.05)).toInt().coerceIn(12, 160)
            val inputText = if (isOmarchy) {
                OmarchyWordmark.render(columns, rows, snapshot.textSize)
            } else {
                TtfxTextRasterizer.render(snapshot.text, columns, rows, snapshot.textSize)
            }
            val input = File(directory, "input.txt").apply { writeText("$inputText\n") }
            val fps = TtfxFrameRatePolicy.frameRate(isOmarchy, snapshot.speed)
            while (generation.get() == token && !Thread.currentThread().isInterrupted) {
                if (!TtfxRenderGeneration.isCurrent(token, generation.get())) return
                val effect = engineEffect(snapshot.effect)
                val command = buildList {
                    add("/system/bin/linker64")
                    add(binary.absolutePath)
                    addAll(listOf("--input-file", input.absolutePath))
                    addAll(listOf("--frame-rate", fps.toString()))
                    addAll(listOf("--canvas-width", columns.toString()))
                    addAll(listOf("--canvas-height", rows.toString()))
                    add("--ignore-terminal-dimensions")
                    add("--final-text-bands")
                    addAll(listOf("--anchor-canvas", "c"))
                    addAll(listOf("--anchor-text", "c"))
                    add("--parity-dump")
                    add("--pace-dump")
                    add("--reuse-canvas")
                    add("--hold-last-frame")
                    add(effect)
                    addAll(TtfxEffectOptions.forEffect(effect, snapshot.speed))
                }
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
                    onNewFrame?.invoke()
                    postInvalidateOnAnimation()
                }
                val exit = child.waitFor()
                stderr.join(500)
                if (generation.get() != token) return
                if (exit != 0) error("TTFX exited with $exit")
                if (!isOmarchy) return
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
