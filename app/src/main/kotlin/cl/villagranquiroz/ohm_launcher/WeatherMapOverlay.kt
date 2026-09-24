package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.setPadding
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.min

/** Omarchy-styled host for Supernotch's native Rust weather-map frames and controls. */
class WeatherMapOverlay(
    context: Context,
    private val colors: Colors,
    private val shapePalette: OmarchyThemePalette?,
    cityName: String,
    cityInfo: String,
    cityMetrics: String,
    initialLayer: String,
    initialZoom: Int,
    private val onViewChanged: (String, Int) -> Unit,
    private val onDismissed: () -> Unit,
) : FrameLayout(context) {

    data class Colors(
        val background: Int,
        val foreground: Int,
        val accent: Int,
        val border: Int,
        val scrim: Int,
        val muted: Int,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val outsideTapPolicy = OverlayOutsideTapPolicy(
        ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
    )
    private var layer = WeatherMapViewPolicy.normalizeLayer(initialLayer)
    private var zoom = WeatherMapViewPolicy.clampZoom(initialZoom)
    private var frameIndex = 0
    private var chart: WeatherMapController.Chart? = null
    private var playing = false
    private var updatingSeekBar = false

    private val title = TextView(context).apply {
        text = cityName
        textSize = 18f
        setTextColor(colors.foreground)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val cityDetails = TextView(context).apply {
        text = cityInfo
        textSize = 13f
        setTextColor(colors.muted)
        maxLines = 2
    }
    private val cityMetricsText = TextView(context).apply {
        text = cityMetrics
        textSize = 11f
        setTextColor(colors.muted)
        maxLines = 2
        visibility = if (cityMetrics.isBlank()) GONE else VISIBLE
    }
    private val layerRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val layerScroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(layerRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val mapImage = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = cityName
    }
    private val annotations = WeatherMapAnnotationsView(context, colors)
    private val layerStamp = TextView(context).apply {
        textSize = 11f
        setTextColor(colors.foreground)
        setPadding(dp(7))
        background = pill(colors.background, colors.border)
    }
    private val valueStamp = TextView(context).apply {
        textSize = 12f
        setTextColor(colors.foreground)
        setPadding(dp(7))
        background = pill(colors.background, colors.border)
    }
    private val loading = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(8))
        background = pill(colors.background, colors.border)
    }
    private val loadingLabel = TextView(context).apply {
        textSize = 12f
        setTextColor(colors.foreground)
        setPadding(dp(6), 0, 0, 0)
    }
    private val scaleBar = WeatherMapScaleView(context, colors.foreground)
    private val mapFrame = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            setColor(withAlpha(this@WeatherMapOverlay.colors.background, 0xCC))
            setStroke(dp(1), this@WeatherMapOverlay.colors.border)
            cornerRadius = themedRadius(10f)
        }
        clipToPadding = true
        clipChildren = true
        clipToOutline = true
    }
    private val playButton = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 16f
        setTextColor(colors.accent)
        isClickable = true
        isFocusable = true
    }
    private val frameStamp = TextView(context).apply {
        textSize = 12f
        setTextColor(colors.muted)
        gravity = Gravity.CENTER_VERTICAL
        minWidth = dp(74)
    }
    private val frameSeekBar = SeekBar(context).apply {
        progressTintList = ColorStateList.valueOf(colors.accent)
        thumbTintList = ColorStateList.valueOf(colors.accent)
        minimumHeight = dp(32)
    }
    private val legendRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val details = TextView(context).apply {
        textSize = 10f
        setTextColor(colors.muted)
        maxLines = 2
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14))
        background = GradientDrawable().apply {
            setColor(this@WeatherMapOverlay.colors.background)
            setStroke(dp(1), this@WeatherMapOverlay.colors.border)
            cornerRadius = themedRadius(16f)
        }
        elevation = dp(20).toFloat()
        isClickable = true
    }

    private val ticker = object : Runnable {
        override fun run() {
            advanceFrame()
        }
    }

    init {
        setBackgroundColor(colors.scrim)
        isClickable = true
        content.addView(title, matchWrap())
        content.addView(cityDetails, matchWrap(top = 2))
        content.addView(cityMetricsText, matchWrap(top = 2))
        content.addView(layerScroller, matchWrap(top = 10))
        content.addView(mapFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(218)).apply {
            topMargin = dp(8)
        })
        content.addView(timelineControls(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
            topMargin = dp(4)
        })
        content.addView(legendRow, matchWrap(top = 2))
        content.addView(details, matchWrap(top = 4))
        val cardWidth = min(dp(560), resources.displayMetrics.widthPixels - dp(32))
        addView(content, LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        installMapLayers()
        installMapChildren()
        installTimeline()
    }

    fun updateCityInfo(name: String, info: String, metrics: String) {
        title.text = name
        mapImage.contentDescription = name
        cityDetails.text = info
        cityMetricsText.text = metrics
        cityMetricsText.visibility = if (metrics.isBlank()) GONE else VISIBLE
    }

    fun showLoading() {
        loadingLabel.text = context.getString(R.string.weather_map_loading)
        loading.visibility = if (WeatherMapViewPolicy.shouldShowLoading(isLoading = true, cacheHit = false)) VISIBLE else GONE
    }

    fun showError(message: String) {
        playing = false
        handler.removeCallbacks(ticker)
        loadingLabel.text = message
        loading.visibility = VISIBLE
        updatePlaybackButton()
    }

    fun showChart(chart: WeatherMapController.Chart) {
        this.chart = chart
        layer = WeatherMapViewPolicy.normalizeLayer(chart.layer)
        zoom = WeatherMapViewPolicy.clampZoom(chart.zoom)
        frameIndex = 0
        loading.visibility = GONE
        playing = chart.frames.size > 1 && !chart.snapshot
        frameSeekBar.max = (chart.frames.size - 1).coerceAtLeast(0)
        frameSeekBar.isEnabled = chart.frames.size > 1 && !chart.snapshot
        installMapLayers()
        renderFrame()
        updatePlaybackButton()
        handler.removeCallbacks(ticker)
        if (playing) handler.postDelayed(ticker, FRAME_DELAY_MS)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val outsideContent = event.x < content.left || event.x > content.right ||
            event.y < content.top || event.y > content.bottom
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> outsideTapPolicy.onDown(event.x, event.y, outsideContent)
            MotionEvent.ACTION_MOVE -> outsideTapPolicy.onMove(event.x, event.y)
            MotionEvent.ACTION_UP -> if (outsideTapPolicy.onUp(event.x, event.y, outsideContent)) {
                close()
                return true
            }
            MotionEvent.ACTION_CANCEL -> outsideTapPolicy.onCancel()
        }
        return super.dispatchTouchEvent(event)
    }

    fun close() {
        playing = false
        handler.removeCallbacks(ticker)
        (parent as? ViewGroup)?.removeView(this)
        onDismissed()
    }

    override fun onDetachedFromWindow() {
        playing = false
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun installMapChildren() {
        mapFrame.addView(mapImage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        mapFrame.addView(annotations, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        mapFrame.addView(layerStamp, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        mapFrame.addView(valueStamp, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        ).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        mapFrame.addView(scaleBar, FrameLayout.LayoutParams(dp(72), dp(34), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(6), dp(6), dp(8), dp(6))
        })
        val zoomColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(colors.background, colors.border)
            elevation = dp(3).toFloat()
        }
        val zoomIn = controlButton("+", R.string.weather_map_zoom_in)
        val zoomOut = controlButton("−", R.string.weather_map_zoom_out)
        zoomIn.setOnClickListener { changeZoom(1) }
        zoomOut.setOnClickListener { changeZoom(-1) }
        zoomColumn.addView(zoomIn, LinearLayout.LayoutParams(dp(38), dp(38)))
        zoomColumn.addView(zoomOut, LinearLayout.LayoutParams(dp(38), dp(38)))
        mapFrame.addView(zoomColumn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        loading.addView(ProgressBar(context), LinearLayout.LayoutParams(dp(18), dp(18)))
        loading.addView(loadingLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mapFrame.addView(loading, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
    }

    private fun installMapLayers() {
        layerRow.removeAllViews()
        WeatherMapViewPolicy.layers.forEach { option ->
            val selected = option.id == layer
            val chip = TextView(context).apply {
                text = context.getString(option.labelRes)
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(10), dp(7), dp(10), dp(7))
                setTextColor(if (selected) colors.accent else colors.foreground)
                background = pill(
                    if (selected) withAlpha(colors.accent, 0x2E) else colors.background,
                    if (selected) colors.accent else colors.border,
                )
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(option.labelRes)
                setOnClickListener {
                    if (layer != option.id) {
                        layer = option.id
                        playing = false
                        handler.removeCallbacks(ticker)
                        installMapLayers()
                        updatePlaybackButton()
                        onViewChanged(layer, zoom)
                    }
                }
            }
            layerRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                marginEnd = dp(6)
            })
        }
    }

    private fun installTimeline() {
        playButton.setOnClickListener {
            val current = chart ?: return@setOnClickListener
            if (current.frames.size < 2 || current.snapshot) return@setOnClickListener
            playing = !playing
            handler.removeCallbacks(ticker)
            updatePlaybackButton()
            if (playing) handler.postDelayed(ticker, FRAME_DELAY_MS)
        }
        frameSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !updatingSeekBar) {
                    frameIndex = progress.coerceIn(0, (chart?.frames?.lastIndex ?: 0).coerceAtLeast(0))
                    playing = false
                    handler.removeCallbacks(ticker)
                    renderFrame()
                    updatePlaybackButton()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                playing = false
                handler.removeCallbacks(ticker)
                updatePlaybackButton()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun timelineControls(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(playButton, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(frameStamp, LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(frameSeekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun changeZoom(delta: Int) {
        val selected = WeatherMapViewPolicy.clampZoom(zoom + delta)
        if (selected == zoom) return
        zoom = selected
        playing = false
        handler.removeCallbacks(ticker)
        updatePlaybackButton()
        onViewChanged(layer, zoom)
    }

    private fun renderFrame() {
        val current = chart ?: return
        if (current.frames.isEmpty()) return
        frameIndex = frameIndex.coerceIn(0, current.frames.lastIndex)
        val frame = current.frames[frameIndex]
        mapImage.setImageBitmap(frame.bitmap)
        annotations.setChart(current)
        scaleBar.setScale(frame.bitmap, current.scaleKm, current.scalePx)
        val titleLayer = WeatherMapViewPolicy.layers.firstOrNull { it.id == current.layer }
            ?.let { context.getString(it.labelRes) }.orEmpty()
        layerStamp.text = "$titleLayer · ${current.zoom}×"
        valueStamp.text = if (!frame.hasSignal || !frame.value.isFinite()) {
            context.getString(R.string.weather_map_no_signal)
        } else {
            "${DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.getDefault())).format(frame.value)} ${current.unit}"
        }
        val validTime = frame.valid.substringAfter('T', frame.valid)
        frameStamp.text = validTime
        updatingSeekBar = true
        frameSeekBar.progress = frameIndex
        updatingSeekBar = false
        renderLegend(current.legend)
        details.text = buildList {
            if (current.flowMarkers) add(context.getString(R.string.weather_map_layer_wind))
            if (current.snapshot) add(context.getString(R.string.weather_map_snapshot))
            if (current.stale) add(context.getString(R.string.weather_map_stale))
            if (current.model.isNotBlank()) add(current.model.uppercase(Locale.ROOT))
            if (current.licence.isNotBlank()) add(current.licence)
        }.distinct().joinToString(" · ")
    }

    private fun renderLegend(entries: List<WeatherMapChartParser.LegendEntry>) {
        legendRow.removeAllViews()
        entries.forEach { entry ->
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val swatch = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor(entry.color))
                }
            }
            item.addView(swatch, LinearLayout.LayoutParams(dp(8), dp(8)))
            item.addView(TextView(context).apply {
                text = entry.label
                textSize = 10f
                setTextColor(colors.muted)
                setPadding(dp(3), 0, 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            legendRow.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22)).apply {
                marginEnd = dp(10)
            })
        }
    }

    private fun advanceFrame() {
        val current = chart ?: return
        if (!playing || current.frames.size < 2) return
        frameIndex = (frameIndex + 1) % current.frames.size
        renderFrame()
        handler.postDelayed(ticker, FRAME_DELAY_MS)
    }

    private fun updatePlaybackButton() {
        playButton.text = if (playing) "Ⅱ" else "▶"
        playButton.contentDescription = context.getString(
            if (playing) R.string.weather_map_pause else R.string.weather_map_play,
        )
    }

    private fun controlButton(glyph: String, descriptionRes: Int): TextView = TextView(context).apply {
        text = glyph
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(colors.accent)
        contentDescription = context.getString(descriptionRes)
        isClickable = true
        isFocusable = true
    }

    private fun pill(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = themedRadius(9f)
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun themedRadius(radiusDp: Float): Float {
        val density = resources.displayMetrics.density
        return OmarchyThemeShapePolicy.surfaceRadius(radiusDp, shapePalette) * density
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val FRAME_DELAY_MS = 950L
    }
}

private class WeatherMapAnnotationsView(
    context: Context,
    private val colors: WeatherMapOverlay.Colors,
) : View(context) {
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            10f,
            resources.displayMetrics,
        )
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(2f * resources.displayMetrics.density, 0f, 1f, colors.background)
    }
    private var chart: WeatherMapController.Chart? = null

    fun setChart(chart: WeatherMapController.Chart) {
        this.chart = chart
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = chart?.frames?.getOrNull(0)?.bitmap ?: return
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        if (!scale.isFinite() || scale <= 0f) return
        val mapWidth = bitmap.width * scale
        val mapHeight = bitmap.height * scale
        val left = (width - mapWidth) / 2f
        val top = (height - mapHeight) / 2f
        val density = resources.displayMetrics.density
        markerPaint.color = colors.accent
        canvas.drawCircle(left + mapWidth / 2f, top + mapHeight / 2f, 5f * density, markerPaint)
        markerPaint.color = colors.background
        canvas.drawCircle(left + mapWidth / 2f, top + mapHeight / 2f, 2f * density, markerPaint)
        chart?.labels?.forEach { label ->
            val x = left + label.x * mapWidth
            val y = top + label.y * mapHeight
            labelPaint.color = colors.foreground
            canvas.drawText(label.name, x.coerceIn(left + 2f * density, left + mapWidth - 38f * density), y, labelPaint)
        }
    }
}

private class WeatherMapScaleView(context: Context, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = 2f * resources.displayMetrics.density
        textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            9f,
            resources.displayMetrics,
        )
        textAlign = Paint.Align.CENTER
    }
    private var bitmapWidth = 0
    private var scaleKm = 0
    private var scalePx = 0f

    fun setScale(bitmap: Bitmap, distanceKm: Int, imagePixels: Float) {
        bitmapWidth = bitmap.width
        scaleKm = distanceKm
        scalePx = imagePixels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bitmapWidth <= 0 || scaleKm <= 0 || scalePx <= 0f) return
        val mapScale = min(width.toFloat() / bitmapWidth, height.toFloat() / 640f)
        val barWidth = (scalePx * mapScale).coerceIn(12f, width - 8f * resources.displayMetrics.density)
        val centerX = width - barWidth / 2f - 4f * resources.displayMetrics.density
        val y = height - 11f * resources.displayMetrics.density
        canvas.drawLine(centerX - barWidth / 2f, y, centerX + barWidth / 2f, y, paint)
        canvas.drawLine(centerX - barWidth / 2f, y - 3f, centerX - barWidth / 2f, y + 2f, paint)
        canvas.drawLine(centerX + barWidth / 2f, y - 3f, centerX + barWidth / 2f, y + 2f, paint)
        canvas.drawText("${scaleKm} km", centerX, y - 4f * resources.displayMetrics.density, paint)
    }
}
