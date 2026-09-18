package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import kotlin.math.min

internal data class OmarchyMenuEntry(
    val icon: String,
    val label: String,
    val detail: String? = null,
    val children: List<OmarchyMenuEntry> = emptyList(),
    val action: (() -> Unit)? = null,
    val iconKey: String? = null,
    val iconLoader: (() -> Drawable?)? = null,
)

internal object OmarchyMenuSearch {
    fun results(
        roots: List<OmarchyMenuEntry>,
        additionalEntries: List<OmarchyMenuEntry>,
        rawQuery: String,
    ): List<OmarchyMenuEntry> {
        val query = rawQuery.trim().lowercase()
        if (query.isEmpty()) return emptyList()
        fun flatten(entries: List<OmarchyMenuEntry>, path: String = ""): List<OmarchyMenuEntry> = buildList {
            entries.forEach { entry ->
                add(if (entry.detail == null && path.isNotEmpty()) entry.copy(detail = path) else entry)
                val childPath = listOf(path, entry.label).filter(String::isNotEmpty).joinToString(" › ")
                addAll(flatten(entry.children, childPath))
            }
        }
        fun matches(entry: OmarchyMenuEntry): Boolean =
            entry.label.lowercase().contains(query) || entry.detail.orEmpty().lowercase().contains(query)
        val additionalKeys = additionalEntries.mapNotNull(OmarchyMenuEntry::iconKey).toSet()
        val menuMatches = flatten(roots).filterNot { entry ->
            entry.iconKey?.let(additionalKeys::contains) == true || additionalEntries.any { it === entry }
        }.filter(::matches)
        val applicationMatches = additionalEntries.filter(::matches)
        return menuMatches + applicationMatches
    }
}

internal enum class OmarchyMenuBackAction { GO_BACK, CLOSE }

internal object OmarchyMenuBackPolicy {
    fun action(levelCount: Int): OmarchyMenuBackAction =
        if (levelCount > 1) OmarchyMenuBackAction.GO_BACK else OmarchyMenuBackAction.CLOSE
}

internal object OmarchyMenuHeaderPolicy {
    fun showBack(levelCount: Int): Boolean = levelCount > 1
}

internal enum class OmarchyMenuOpenTrigger { LONG_PRESS, SWIPE_UP, DOUBLE_TAP, LOGO_TAP }

internal object OmarchyMenuInputPolicy {
    fun focusInput(trigger: OmarchyMenuOpenTrigger): Boolean =
        trigger == OmarchyMenuOpenTrigger.LONG_PRESS || trigger == OmarchyMenuOpenTrigger.SWIPE_UP
}

internal object OmarchyMenuFocusPolicy {
    fun shouldClearFocus(inputFocused: Boolean, imeWasVisible: Boolean, imeVisible: Boolean): Boolean =
        inputFocused && imeWasVisible && !imeVisible
}

internal object OmarchySystemMenuPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun showDefaultLauncherShortcut(alreadyDefault: Boolean): Boolean = true

    @Suppress("UNUSED_PARAMETER")
    fun showNotificationAccessShortcut(alreadyEnabled: Boolean): Boolean = true
}

private class MenuSearchEditText(context: Context) : EditText(context) {
    var onImeBack: (() -> Unit)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) onImeBack?.invoke()
        return super.onKeyPreIme(keyCode, event)
    }
}

internal object OmarchyMenuCardPlacement {
    fun top(searchFocused: Boolean, centeredTop: Int, statusBar: Int, gap: Int): Int =
        if (searchFocused) statusBar.coerceAtLeast(0) + gap.coerceAtLeast(0) else centeredTop.coerceAtLeast(0)
}

/** Touch adaptation of Omarchy's centered Menu.qml surface. */
internal class OmarchyMenuOverlay(
    context: Context,
    private val rootEntries: List<OmarchyMenuEntry>,
    private val searchEntries: List<OmarchyMenuEntry> = emptyList(),
    private val rootTitle: String? = null,
    private val colors: Colors,
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

    private data class Level(val title: String, val entries: List<OmarchyMenuEntry>)

    private val levels = mutableListOf(Level("", rootEntries))
    private val card = LinearLayout(context)
    private val headerRow = LinearLayout(context)
    private val backButton = TextView(context)
    private val header = MenuSearchEditText(context)
    private val rows = RecyclerView(context)
    private val rowAdapter = MenuRowAdapter()
    private val nerdFont: Typeface = NerdFont.load(context)
    private var imeWasVisible = false
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private val backCallback = OnBackInvokedCallback {
        if (header.hasFocus()) {
            val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(windowToken, 0)
            exitInputMode()
        } else {
            handleBack()
        }
    }
    private val imeProbe = object : Runnable {
        override fun run() {
            ViewCompat.getRootWindowInsets(this@OmarchyMenuOverlay)?.let { insets ->
                updateImeVisibility(
                    insets.isVisible(WindowInsetsCompat.Type.ime()),
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                )
            }
            if (isAttachedToWindow) postDelayed(this, 100)
        }
    }
    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(colors.scrim)
        setOnClickListener { dismiss() }
        card.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            background = panelBackground()
            elevation = dp(20).toFloat()
            isClickable = true
        }

        headerRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backButton.apply {
            text = NerdGlyph.LEFT
            textSize = 24f
            typeface = nerdFont
            gravity = Gravity.CENTER
            setTextColor(colors.foreground)
            contentDescription = context.getString(R.string.menu_back)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { goBack() }
        }
        headerRow.addView(backButton, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))

        header.apply {
            onImeBack = ::exitInputMode
            setSingleLine(true)
            textSize = 17f
            typeface = nerdFont
            setTextColor(colors.foreground)
            setHintTextColor(withAlpha(colors.foreground, 0x94))
            background = null
            setPadding(0, 0, 0, 0)
            hint = rootTitle ?: context.getString(R.string.menu_go)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                    renderRows(value?.toString().orEmpty())
                    post(::updateCardLayout)
                }
                override fun afterTextChanged(value: Editable?) = Unit
            })
            onFocusChangeListener = OnFocusChangeListener { _, _ -> updateCardLayout() }
            setOnLongClickListener {
                if (levels.size > 1) goBack() else false
            }
        }
        headerRow.addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        card.addView(headerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        rows.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = rowAdapter
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        card.addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(card, LayoutParams(dp(320), dp(420), Gravity.CENTER))
        renderRows("")
        post(::updateCardLayout)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(imeProbe)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backDispatcher?.unregisterOnBackInvokedCallback(backCallback)
            backDispatcher = null
        }
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(imeProbe)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backDispatcher = findOnBackInvokedDispatcher()?.also {
                it.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCallback)
            }
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        post(::updateCardLayout)
    }

    private fun updateCardLayout() {
        if (width <= 0 || height <= 0) return
        val cardWidth = min(dp(420), (width - dp(32)).coerceAtLeast(dp(280)))
        val query = header.text?.toString().orEmpty()
        val visibleEntries = if (query.isBlank()) levels.last().entries else
            OmarchyMenuSearch.results(rootEntries, searchEntries, query)
        val fixedHeight = dp(12 + 44 + 16)
        val maximumHeight = (height * .72f).toInt()
        var visibleRowsHeight = 0
        visibleEntries.forEach { entry ->
            val rowHeight = dp(if (entry.detail == null) 54 else 62)
            if (fixedHeight + visibleRowsHeight + rowHeight <= maximumHeight) visibleRowsHeight += rowHeight
        }
        if (visibleEntries.isEmpty()) visibleRowsHeight = dp(50)
        val cardHeight = (fixedHeight + visibleRowsHeight).coerceAtLeast(dp(120))
        val centeredTop = (height - cardHeight) / 2
        val statusBar = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())
            ?.top ?: 0
        card.layoutParams = LayoutParams(cardWidth, cardHeight, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = OmarchyMenuCardPlacement.top(header.hasFocus(), centeredTop, statusBar, dp(16))
        }
    }

    private fun updateImeVisibility(imeVisible: Boolean, imeBottom: Int = if (imeVisible) 1 else 0) {
        val actuallyVisible = imeVisible && imeBottom > 0
        if (OmarchyMenuFocusPolicy.shouldClearFocus(header.hasFocus(), imeWasVisible, actuallyVisible)) {
            exitInputMode()
        }
        imeWasVisible = header.hasFocus() && actuallyVisible
    }

    private fun exitInputMode() {
        header.clearFocus()
        requestFocus()
        post(::updateCardLayout)
    }

    fun close() = dismiss()

    fun focusInput() {
        header.requestFocus()
        header.setSelection(header.text?.length ?: 0)
        post {
            val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.showSoftInput(header, InputMethodManager.SHOW_IMPLICIT)
            updateCardLayout()
        }
    }


    fun handleBack(): Boolean {
        when (OmarchyMenuBackPolicy.action(levels.size)) {
            OmarchyMenuBackAction.GO_BACK -> goBack()
            OmarchyMenuBackAction.CLOSE -> dismiss()
        }
        return true
    }

    private fun renderRows(query: String) {
        val normalized = query.trim().lowercase()
        val visible = if (normalized.isEmpty()) levels.last().entries else
            OmarchyMenuSearch.results(rootEntries, searchEntries, normalized)
        rowAdapter.submit(visible)
    }

    private fun row(entry: OmarchyMenuEntry): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        background = rowBackground()
        isClickable = true
        isFocusable = true
        contentDescription = entry.label

        if (entry.iconLoader != null) {
            addView(ImageView(context).apply {
                tag = entry.iconKey
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(2), dp(8), dp(2), dp(8))
                val expectedKey = entry.iconKey
                iconExecutor.execute {
                    val drawable = entry.iconLoader.invoke()
                    post {
                        if (tag == expectedKey) setImageDrawable(drawable)
                    }
                }
            }, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            addView(TextView(context).apply {
                text = entry.icon
                textSize = 20f
                typeface = nerdFont
                gravity = Gravity.CENTER
                setTextColor(rowTextColors())
            }, LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT))
        }

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = entry.label
                textSize = 15f
                typeface = Typeface.MONOSPACE
                setTextColor(rowTextColors())
                maxLines = 1
            })
            entry.detail?.let { secondary ->
                addView(TextView(context).apply {
                    text = secondary
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(colors.muted)
                    maxLines = 1
                })
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(12) })

        if (entry.children.isNotEmpty()) {
            addView(TextView(context).apply {
                text = "›"
                textSize = 21f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setTextColor(colors.muted)
            }, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.MATCH_PARENT))
        }

        setOnClickListener {
            when {
                entry.children.isNotEmpty() -> open(entry)
                entry.action != null -> dismiss(entry.action)
            }
        }
    }

    private inner class MenuRowHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    private inner class MenuRowAdapter : RecyclerView.Adapter<MenuRowHolder>() {
        private var entries: List<OmarchyMenuEntry> = emptyList()

        fun submit(newEntries: List<OmarchyMenuEntry>) {
            entries = newEntries
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = entries.size.coerceAtLeast(1)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuRowHolder =
            MenuRowHolder(FrameLayout(parent.context))

        override fun onBindViewHolder(holder: MenuRowHolder, position: Int) {
            holder.container.removeAllViews()
            if (entries.isEmpty()) {
                holder.container.addView(TextView(context).apply {
                    text = context.getString(R.string.no_results)
                    textSize = 14f
                    typeface = Typeface.MONOSPACE
                    setTextColor(colors.muted)
                    gravity = Gravity.CENTER_VERTICAL
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                holder.container.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
                return
            }

            val entry = entries[position]
            holder.container.addView(
                row(entry),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            holder.container.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(if (entry.detail == null) 50 else 58),
            ).apply { bottomMargin = dp(4) }
        }
    }

    private fun open(entry: OmarchyMenuEntry) {
        levels += Level(entry.label, entry.children)
        header.text.clear()
        updateHeader()
        renderRows("")
        post(::updateCardLayout)
    }

    private fun goBack(): Boolean {
        if (levels.size <= 1) return false
        levels.removeAt(levels.lastIndex)
        header.text.clear()
        updateHeader()
        renderRows("")
        post(::updateCardLayout)
        return true
    }

    private fun updateHeader() {
        backButton.visibility = if (OmarchyMenuHeaderPolicy.showBack(levels.size)) View.VISIBLE else View.GONE
        header.hint = if (levels.size == 1) rootTitle ?: context.getString(R.string.menu_go) else "${levels.last().title}…"
    }

    private fun dismiss(after: (() -> Unit)? = null) {
        val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(windowToken, 0)
        animate().alpha(0f).setDuration(120).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            onDismissed()
            after?.invoke()
        }.start()
    }

    private fun panelBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(this@OmarchyMenuOverlay.colors.background)
        cornerRadius = 0f
        setStroke(dp(1), this@OmarchyMenuOverlay.colors.border)
    }

    private fun rowBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
            setColor(this@OmarchyMenuOverlay.colors.selectedBackground)
            cornerRadius = 0f
            setStroke(dp(1), this@OmarchyMenuOverlay.colors.border)
        })
        addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
    }

    private fun rowTextColors() = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(colors.selectedText, colors.foreground),
    )

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val iconExecutor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "ohm-menu-icons").apply { priority = Thread.MIN_PRIORITY }
        }
    }
}
