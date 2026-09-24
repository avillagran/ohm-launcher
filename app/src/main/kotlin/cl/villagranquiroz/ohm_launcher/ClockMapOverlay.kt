package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding
import kotlin.math.min

/** Omarchy-styled Equal Earth world clock with the original Rust zone commands. */
class ClockMapOverlay(
    context: Context,
    private val colors: Colors,
    private val shapePalette: OmarchyThemePalette?,
    private val controller: ClockMapController,
    private val onDismissed: () -> Unit,
    private val onRefreshRequested: () -> Unit,
) : FrameLayout(context) {

    data class Colors(
        val background: Int,
        val foreground: Int,
        val accent: Int,
        val urgent: Int,
        val border: Int,
        val scrim: Int,
        val muted: Int,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val outsideTapPolicy = OverlayOutsideTapPolicy(
        ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
    )
    private val title = TextView(context).apply {
        text = context.getString(R.string.world_clock_map_title)
        textSize = 17f
        setTextColor(colors.foreground)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val addButton = TextView(context).apply {
        text = "+"
        textSize = 24f
        gravity = Gravity.CENTER
        setTextColor(colors.accent)
        contentDescription = context.getString(R.string.world_clock_add_location)
        isClickable = true
        isFocusable = true
    }
    private val status = TextView(context).apply {
        textSize = 12f
        setTextColor(colors.muted)
        gravity = Gravity.CENTER
    }
    private val map = ClockWorldMapView(context, colors)
    private val searchInput = EditText(context).apply {
        hint = context.getString(R.string.world_clock_search_location)
        textSize = 14f
        setTextColor(colors.foreground)
        setHintTextColor(colors.muted)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        setSingleLine(true)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            setColor(this@ClockMapOverlay.colors.background)
            setStroke(dp(1), this@ClockMapOverlay.colors.border)
            cornerRadius = themedRadius(9f)
        }
    }
    private val searchResults = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val searchResultScroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
        addView(searchResults, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val searchPanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = GONE
    }
    private val locationHeading = TextView(context).apply {
        text = context.getString(R.string.world_clock_saved_locations)
        textSize = 11f
        setTextColor(colors.muted)
    }
    private val locations = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val locationScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(locations, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14))
        background = GradientDrawable().apply {
            setColor(this@ClockMapOverlay.colors.background)
            setStroke(dp(1), this@ClockMapOverlay.colors.border)
            cornerRadius = themedRadius(16f)
        }
        elevation = dp(20).toFloat()
        isClickable = true
    }
    private val cardScroll = ScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private var searchGeneration = 0

    private val searchDebounce = Runnable { requestZoneSearch() }
    private val refreshTicker = Runnable { onRefreshRequested() }

    init {
        setBackgroundColor(colors.scrim)
        isClickable = true
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(addButton, LinearLayout.LayoutParams(dp(44), dp(40)))
        }
        content.addView(header, matchWrap())
        searchPanel.addView(searchInput, matchWrap())
        searchPanel.addView(searchResultScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(132),
        ).apply { topMargin = dp(4) })
        content.addView(searchPanel, matchWrap(top = 4))
        content.addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(218)).apply {
            topMargin = dp(6)
        })
        content.addView(status, matchWrap(top = 4))
        content.addView(locationHeading, matchWrap(top = 8))
        content.addView(locationScroll, matchWrap(top = 4))
        val cardWidth = min(dp(560), resources.displayMetrics.widthPixels - dp(32))
        val cardHeight = min(dp(620), resources.displayMetrics.heightPixels - dp(64))
        addView(cardScroll, LayoutParams(cardWidth, cardHeight, Gravity.CENTER))
        addButton.setOnClickListener { toggleSearch() }
        map.onClockTapped = ::selectClock
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                handler.removeCallbacks(searchDebounce)
                if (text.isNullOrBlank()) {
                    searchResults.removeAllViews()
                } else {
                    handler.postDelayed(searchDebounce, SEARCH_DEBOUNCE_MS)
                }
            }
            override fun afterTextChanged(editable: Editable?) = Unit
        })
        showLoading()
    }

    fun showClocks(clocks: List<ClockMapParser.Clock>) {
        map.setClocks(clocks)
        renderLocations(clocks)
        status.visibility = GONE
        handler.removeCallbacks(refreshTicker)
        handler.postDelayed(refreshTicker, REFRESH_INTERVAL_MS)
    }

    fun showLoading() {
        status.text = context.getString(R.string.world_clock_map_loading)
        status.visibility = VISIBLE
    }

    fun showError(message: String) {
        status.text = message
        status.visibility = VISIBLE
        handler.removeCallbacks(refreshTicker)
        handler.postDelayed(refreshTicker, REFRESH_INTERVAL_MS)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val contentBounds = OverlayContentBounds.withinScrollView(
            scrollLeft = cardScroll.left.toFloat(),
            scrollTop = cardScroll.top.toFloat(),
            scrollRight = cardScroll.right.toFloat(),
            scrollBottom = cardScroll.bottom.toFloat(),
            scrollX = cardScroll.scrollX.toFloat(),
            scrollY = cardScroll.scrollY.toFloat(),
            contentLeft = content.left.toFloat(),
            contentTop = content.top.toFloat(),
            contentRight = content.right.toFloat(),
            contentBottom = content.bottom.toFloat(),
        )
        val outsideContent = !contentBounds.contains(event.x, event.y)
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
        handler.removeCallbacks(searchDebounce)
        handler.removeCallbacks(refreshTicker)
        hideKeyboard()
        (parent as? ViewGroup)?.removeView(this)
        onDismissed()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(searchDebounce)
        handler.removeCallbacks(refreshTicker)
        super.onDetachedFromWindow()
    }

    private fun toggleSearch() {
        val show = searchPanel.visibility != VISIBLE
        searchPanel.visibility = if (show) VISIBLE else GONE
        addButton.text = if (show) "×" else "+"
        addButton.contentDescription = context.getString(
            if (show) R.string.world_clock_remove_location else R.string.world_clock_add_location,
        )
        if (show) {
            searchInput.requestFocus()
            searchInput.post {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
            }
        } else {
            handler.removeCallbacks(searchDebounce)
            searchInput.text.clear()
            hideKeyboard()
        }
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun requestZoneSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) return
        val generation = ++searchGeneration
        controller.searchZones(query) { result ->
            if (parent == null || generation != searchGeneration) return@searchZones
            when (result) {
                is ClockMapController.Result.ZoneOptions -> renderZoneResults(result.zones)
                is ClockMapController.Result.Failure -> renderSearchMessage(result.message)
                ClockMapController.Result.Unsupported -> renderSearchMessage(
                    context.getString(R.string.world_clock_map_unsupported),
                )
                is ClockMapController.Result.Success -> showClocks(result.clocks)
            }
        }
    }

    private fun renderZoneResults(zones: List<String>) {
        searchResults.removeAllViews()
        if (zones.isEmpty()) {
            renderSearchMessage(context.getString(R.string.world_clock_no_locations_found))
            return
        }
        zones.forEach { timeZone ->
            val name = timeZone.substringAfterLast('/').replace('_', ' ')
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(7), dp(10), dp(7))
                isClickable = true
                isFocusable = true
                background = pill(colors.background, colors.border)
                setOnClickListener {
                    controller.addZone(timeZone) { result ->
                        if (parent == null) return@addZone
                        when (result) {
                            is ClockMapController.Result.Success -> {
                                showClocks(result.clocks)
                                toggleSearch()
                            }
                            is ClockMapController.Result.Failure -> renderSearchMessage(result.message)
                            ClockMapController.Result.Unsupported -> renderSearchMessage(
                                context.getString(R.string.world_clock_map_unsupported),
                            )
                            is ClockMapController.Result.ZoneOptions -> Unit
                        }
                    }
                }
            }
            row.addView(TextView(context).apply {
                text = name
                textSize = 13f
                setTextColor(colors.foreground)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(context).apply {
                text = timeZone
                textSize = 10f
                setTextColor(colors.muted)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            searchResults.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            })
        }
    }

    private fun renderSearchMessage(message: String) {
        searchResults.removeAllViews()
        searchResults.addView(TextView(context).apply {
            text = message
            textSize = 12f
            setTextColor(colors.muted)
            setPadding(dp(8))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun selectClock(timeZone: String) {
        controller.selectZone(timeZone) { result ->
            if (parent == null) return@selectZone
            when (result) {
                is ClockMapController.Result.Success -> showClocks(result.clocks)
                is ClockMapController.Result.Failure -> showError(result.message)
                ClockMapController.Result.Unsupported -> showError(
                    context.getString(R.string.world_clock_map_unsupported),
                )
                is ClockMapController.Result.ZoneOptions -> Unit
            }
        }
    }

    private fun removeClock(timeZone: String) {
        controller.removeZone(timeZone) { result ->
            if (parent == null) return@removeZone
            when (result) {
                is ClockMapController.Result.Success -> showClocks(result.clocks)
                is ClockMapController.Result.Failure -> showError(result.message)
                ClockMapController.Result.Unsupported -> showError(
                    context.getString(R.string.world_clock_map_unsupported),
                )
                is ClockMapController.Result.ZoneOptions -> Unit
            }
        }
    }

    private fun renderLocations(clocks: List<ClockMapParser.Clock>) {
        locations.removeAllViews()
        clocks.forEach { clock ->
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(5), dp(4), dp(5))
                background = pill(
                    if (clock.primary) withAlpha(colors.accent, 0x2C) else colors.background,
                    if (clock.primary) colors.accent else colors.border,
                )
                isClickable = true
                isFocusable = true
                contentDescription = "${clock.name}, ${clock.timeZone}, ${clock.time}"
                setOnClickListener { selectClock(clock.timeZone) }
            }
            val text = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            text.addView(TextView(context).apply {
                this.text = "${clock.name}  ${clock.time}"
                textSize = 12f
                setTextColor(if (clock.primary) colors.accent else colors.foreground)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            text.addView(TextView(context).apply {
                this.text = clock.timeZone
                textSize = 9f
                setTextColor(colors.muted)
            })
            tile.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (!clock.local) {
                tile.addView(TextView(context).apply {
                    this.text = "×"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(colors.muted)
                    contentDescription = context.getString(R.string.world_clock_remove_location)
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(8), 0, dp(5), 0)
                    setOnClickListener { removeClock(clock.timeZone) }
                }, LinearLayout.LayoutParams(dp(28), dp(38)))
            }
            locations.addView(tile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                marginEnd = dp(6)
            })
        }
    }

    private fun pill(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = themedRadius(9f)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun themedRadius(radiusDp: Float): Float {
        val density = resources.displayMetrics.density
        return OmarchyThemeShapePolicy.surfaceRadius(radiusDp, shapePalette) * density
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REFRESH_INTERVAL_MS = 60_000L
        private const val SEARCH_DEBOUNCE_MS = 250L
    }
}

private class ClockWorldMapView(context: Context, private val colors: ClockMapOverlay.Colors) : View(context) {
    private val mapBitmap = BitmapFactory.decodeResource(resources, R.drawable.world_equal_earth)
    private val mapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(colors.foreground, PorterDuff.Mode.SRC_IN)
        alpha = 158
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            11f,
            resources.displayMetrics,
        )
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private var clocks: List<ClockMapParser.Clock> = emptyList()
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    var onClockTapped: ((String) -> Unit)? = null

    fun setClocks(clocks: List<ClockMapParser.Clock>) {
        this.clocks = clocks
        contentDescription = clocks.joinToString(separator = ", ") { "${it.name}, ${it.time}" }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (kotlin.math.abs(event.x - downX) <= touchSlop && kotlin.math.abs(event.y - downY) <= touchSlop) {
                    nearestClock(event.x, event.y)?.let { onClockTapped?.invoke(it.timeZone) }
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun nearestClock(x: Float, y: Float): ClockMapParser.Clock? {
        val bitmap = mapBitmap ?: return null
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        if (!scale.isFinite() || scale <= 0f) return null
        val mapWidth = bitmap.width * scale
        val mapHeight = bitmap.height * scale
        val left = (width - mapWidth) / 2f
        val top = (height - mapHeight) / 2f
        val threshold = 30f * resources.displayMetrics.density
        return clocks.mapNotNull { clock ->
            val point = ClockMapProjection.project(clock.longitude, clock.latitude, bitmap.width.toFloat(), bitmap.height.toFloat())
            val markerX = left + point.x * scale
            val markerY = top + point.y * scale
            val distance = kotlin.math.hypot(markerX - x, markerY - y)
            if (distance <= threshold) clock to distance else null
        }.minByOrNull { it.second }?.first
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = mapBitmap ?: return
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        if (!scale.isFinite() || scale <= 0f) return
        val mapWidth = bitmap.width * scale
        val mapHeight = bitmap.height * scale
        val left = (width - mapWidth) / 2f
        val top = (height - mapHeight) / 2f
        val bounds = RectF(left, top, left + mapWidth, top + mapHeight)
        canvas.drawBitmap(bitmap, null, bounds, mapPaint)
        val density = resources.displayMetrics.density
        for (clock in clocks) {
            val point = ClockMapProjection.project(
                longitude = clock.longitude,
                latitude = clock.latitude,
                width = bitmap.width.toFloat(),
                height = bitmap.height.toFloat(),
            )
            val x = left + point.x * scale
            val y = top + point.y * scale
            markerPaint.color = if (clock.primary) colors.accent else colors.urgent
            canvas.drawCircle(x, y, 5f * density, markerPaint)
            markerPaint.color = colors.background
            canvas.drawCircle(x, y, 2f * density, markerPaint)
            textPaint.color = if (clock.primary) colors.accent else colors.foreground
            val label = "${clock.name}  ${clock.time}"
            val baseline = (y + 18f * density).coerceIn(top + textPaint.textSize, top + mapHeight - 2f * density)
            canvas.drawText(label, x.coerceIn(left + 4f * density, left + mapWidth - 4f * density), baseline, textPaint)
        }
    }
}
