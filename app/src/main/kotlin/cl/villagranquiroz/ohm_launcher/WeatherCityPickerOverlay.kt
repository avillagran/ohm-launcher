package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Menu-style city picker for the weather widget: type a city, pick from the
 * suggested list, and choose Celsius or Fahrenheit at the bottom. Styled
 * after the Omarchy menu card so it feels like part of the same menu system.
 */
class WeatherCityPickerOverlay(
    context: Context,
    private val colors: Colors,
    private val controller: WeatherWidgetController,
    private val onApplied: () -> Unit,
    private val onDismissed: () -> Unit,
) : FrameLayout(context) {
    data class Colors(
        val background: Int,
        val foreground: Int,
        val border: Int,
        val scrim: Int,
        val selectedBackground: Int,
        val selectedText: Int,
        val muted: Int,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var currentUnit: String = controller.currentState().unit
    private var searchGeneration = 0

    private val resultsColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val statusLabel = TextView(context).apply {
        textSize = 13f
        setTextColor(colors.muted)
        gravity = Gravity.CENTER_VERTICAL
    }
    private val searchInput = EditText(context).apply {
        hint = context.getString(R.string.weather_picker_search_hint)
        setHintTextColor(colors.muted)
        setTextColor(colors.foreground)
        setSingleLine(true)
        background = rounded(colors.selectedBackground, dp(20).toFloat(), Color.TRANSPARENT)
        setPadding(dp(18), 0, dp(18), 0)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = scheduleSearch(s?.toString().orEmpty())
        })
    }
    private val celsiusChip = unitChip("°C") { selectUnit("C") }
    private val fahrenheitChip = unitChip("°F") { selectUnit("F") }

    init {
        fitsSystemWindows = true
        isClickable = true
        isFocusable = true
        val scrim = View(context).apply {
            setBackgroundColor(colors.scrim)
            setOnClickListener { close() }
        }
        addView(scrim, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(buildCard(), LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        updateUnitChips()
        showStatus("")
        post { searchInput.requestFocus(); showKeyboard() }
    }

    private fun buildCard(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(colors.background, dp(18).toFloat(), colors.border)
            setPadding(dp(20), dp(16), dp(20), dp(14))
        }
        val marginParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        val horizontalMargin = dp(28)
        marginParams.marginStart = horizontalMargin
        marginParams.marginEnd = horizontalMargin

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.weather_picker_title)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.foreground)
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val close = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(colors.muted)
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.weather_picker_close)
            background = rounded(Color.TRANSPARENT, dp(16).toFloat(), Color.TRANSPARENT)
            setOnClickListener { close() }
        }
        titleRow.addView(close, LinearLayout.LayoutParams(dp(36), dp(36)))
        card.addView(titleRow)

        val searchParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(50))
        searchParams.topMargin = dp(12)
        card.addView(searchInput, searchParams)

        val scroll = ScrollView(context).apply {
            isScrollbarFadingEnabled = true
            addView(resultsColumn, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val scrollParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        scrollParams.topMargin = dp(10)
        card.addView(statusLabel, LinearLayout.LayoutParams(MATCH_PARENT, dp(28)))
        card.addView(scroll, scrollParams)

        val divider = View(context).apply { setBackgroundColor(colors.border) }
        val dividerParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
        dividerParams.topMargin = dp(10)
        card.addView(divider, dividerParams)

        val unitRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val chipParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        chipParams.marginEnd = dp(8)
        unitRow.addView(celsiusChip, chipParams)
        val chipParams2 = LinearLayout.LayoutParams(0, dp(44), 1f)
        chipParams2.marginStart = dp(8)
        unitRow.addView(fahrenheitChip, chipParams2)
        val unitParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        unitParams.topMargin = dp(10)
        card.addView(unitRow, unitParams)

        return FrameLayout(context).apply {
            addView(card, marginParams)
        }
    }

    private fun unitChip(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun selectUnit(unit: String) {
        if (currentUnit == unit) return
        currentUnit = unit
        controller.setUnit(unit) { onApplied() }
        updateUnitChips()
    }

    private fun updateUnitChips() {
        styleChip(celsiusChip, currentUnit == "C")
        styleChip(fahrenheitChip, currentUnit == "F")
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.setTextColor(if (selected) colors.selectedText else colors.muted)
        chip.background = rounded(
            if (selected) colors.selectedBackground else Color.TRANSPARENT,
            dp(12).toFloat(),
            if (selected) colors.selectedText else colors.border,
        )
    }

    private fun scheduleSearch(query: String) {
        val generation = ++searchGeneration
        handler.removeCallbacksAndMessages(SEARCH_TOKEN)
        if (query.isBlank()) {
            showStatus("")
            resultsColumn.removeAllViews()
            return
        }
        handler.postAtTime({
            if (generation != searchGeneration) return@postAtTime
            showStatus("…")
            controller.searchCities(query) { results ->
                if (generation != searchGeneration) return@searchCities
                renderResults(results)
            }
        }, SEARCH_TOKEN, android.os.SystemClock.uptimeMillis() + SEARCH_DEBOUNCE_MILLIS)
    }

    private fun renderResults(results: List<WeatherCityCandidate>) {
        resultsColumn.removeAllViews()
        if (results.isEmpty()) {
            showStatus(context.getString(R.string.weather_picker_no_results))
            return
        }
        showStatus("")
        results.forEach { candidate ->
            val row = TextView(context).apply {
                val region = listOf(candidate.admin1, candidate.country).filter { it.isNotBlank() }.joinToString(", ")
                text = if (region.isNotBlank()) "${candidate.name}  ·  $region" else candidate.name
                textSize = 15f
                setTextColor(colors.foreground)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(Color.TRANSPARENT, dp(10).toFloat(), Color.TRANSPARENT)
                setOnClickListener {
                    controller.applyCandidate(candidate, currentUnit) {
                        onApplied()
                        close()
                    }
                }
            }
            val params = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            params.topMargin = dp(4)
            resultsColumn.addView(row, params)
        }
    }

    private fun showStatus(text: String) {
        statusLabel.text = text
        statusLabel.visibility = if (text.isEmpty()) GONE else VISIBLE
    }

    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    fun handleBack(): Boolean {
        close()
        return true
    }

    fun close() {
        if (parent == null) return
        handler.removeCallbacksAndMessages(SEARCH_TOKEN)
        (parent as? ViewGroup)?.removeView(this)
        onDismissed()
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
        if (Color.alpha(stroke) != 0) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SEARCH_DEBOUNCE_MILLIS = 300L
        private val SEARCH_TOKEN = Any()
    }
}
