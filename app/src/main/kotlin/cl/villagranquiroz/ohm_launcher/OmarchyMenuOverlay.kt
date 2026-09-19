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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
    val trailingIcon: (() -> String)? = null,
    val trailingContentDescription: (() -> String)? = null,
    val trailingAction: (() -> Unit)? = null,
)

internal object OmarchyMenuSearch {
    fun results(
        roots: List<OmarchyMenuEntry>,
        additionalEntries: List<OmarchyMenuEntry>,
        rawQuery: String,
        mode: OmarchyMenuSearchMode = OmarchyMenuSearchMode.ALL,
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
        val menuMatches = if (mode == OmarchyMenuSearchMode.APPS_ONLY) emptyList() else flatten(roots).filterNot { entry ->
            entry.iconKey?.let(additionalKeys::contains) == true || additionalEntries.any { it === entry }
        }.filter(::matches)
        val applicationMatches = additionalEntries.filter(::matches)
        return menuMatches + applicationMatches
    }
}

/** Enter (hardware or IME) executes the first visible entry, dmenu-style. */
internal object OmarchyMenuEnterPolicy {
    fun firstVisible(visible: List<OmarchyMenuEntry>): OmarchyMenuEntry? = visible.firstOrNull()

    fun shouldOpen(entry: OmarchyMenuEntry): Boolean = entry.children.isNotEmpty()

    fun shouldRun(entry: OmarchyMenuEntry): Boolean = entry.action != null
}

internal enum class OmarchyMenuSearchMode { ALL, APPS_ONLY }

internal enum class OmarchyMenuInputPlacement { TOP, BOTTOM }

internal enum class OmarchyMenuMode(val inputPlacement: OmarchyMenuInputPlacement) {
    FULL(OmarchyMenuInputPlacement.TOP),
    APPS_ONLY(OmarchyMenuInputPlacement.BOTTOM),
}

internal object OmarchyMenuHeightPolicy {
    fun maximumHeight(viewportHeight: Int, mode: OmarchyMenuMode): Int =
        (viewportHeight * if (mode == OmarchyMenuMode.APPS_ONLY) 0.576f else 0.72f).toInt()
}

internal object OmarchyMenuDismissPolicy {
    fun isBackgroundTap(x: Float, y: Float, left: Int, top: Int, right: Int, bottom: Int): Boolean =
        x < left || x >= right || y < top || y >= bottom
}

internal object OmarchyMenuVerticalPlacement {
    fun top(
        viewportHeight: Int,
        cardHeight: Int,
        mode: OmarchyMenuMode,
        searchFocused: Boolean,
        centeredTop: Int,
        statusBar: Int,
        gap: Int,
    ): Int = if (mode == OmarchyMenuMode.APPS_ONLY && searchFocused) {
        (viewportHeight - cardHeight - gap).coerceAtLeast(statusBar + gap)
    } else {
        OmarchyMenuCardPlacement.top(searchFocused, centeredTop, statusBar, gap)
    }
}

internal object OmarchyMenuCardHeightPolicy {
    fun height(dynamicHeight: Int, maximumHeight: Int, mode: OmarchyMenuMode): Int =
        dynamicHeight.coerceAtMost(maximumHeight)
}

internal object OmarchyMenuAvailableHeightPolicy {
    fun bottom(fullHeight: Int, imeBottom: Int, mode: OmarchyMenuMode): Int =
        if (mode == OmarchyMenuMode.APPS_ONLY) (fullHeight - imeBottom).coerceAtLeast(0) else fullHeight
}

internal object OmarchyMenuBottomAnchorPolicy {
    fun anchor(existing: Int?, availableBottom: Int, gap: Int): Int =
        existing ?: (availableBottom - gap).coerceAtLeast(0)

    fun adjustForImeChange(existing: Int, oldImeBottom: Int, newImeBottom: Int): Int =
        existing + (newImeBottom - oldImeBottom)
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
        trigger == OmarchyMenuOpenTrigger.DOUBLE_TAP || trigger == OmarchyMenuOpenTrigger.SWIPE_UP
}

internal object OmarchyMenuFocusPolicy {
    fun shouldClearFocus(inputFocused: Boolean, imeWasVisible: Boolean, imeVisible: Boolean): Boolean =
        inputFocused && imeWasVisible && !imeVisible

    fun shouldDismiss(
        mode: OmarchyMenuMode,
        inputFocused: Boolean,
        imeWasVisible: Boolean,
        imeVisible: Boolean,
    ): Boolean =
        mode == OmarchyMenuMode.APPS_ONLY &&
            inputFocused &&
            imeWasVisible &&
            !imeVisible
}

internal class OmarchyMenuDismissGuard {
    private var started = false

    fun begin(): Boolean {
        if (started) return false
        started = true
        return true
    }
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
    private val mode: OmarchyMenuMode = OmarchyMenuMode.FULL,
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
    private val dismissGuard = OmarchyMenuDismissGuard()
    private var imeWasVisible = false
    private var imeBottomInset = 0
    private var visibleEntries: List<OmarchyMenuEntry> = emptyList()
    private var appsOnlyInputBottomOnScreen: Int? = null
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private val backCallback = OnBackInvokedCallback {
        if (header.hasFocus()) {
            val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(windowToken, 0)
            handleImeDismissal()
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
        // Enter runs the first visible entry even when the search input is not focused
        // (the menu often opens without the keyboard, focus on the overlay root).
        setOnKeyListener { _, keyCode, event ->
            val enter = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (enter && event?.action == KeyEvent.ACTION_DOWN) {
                executeFirstVisible()
                true
            } else false
        }
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
            onImeBack = ::handleImeDismissal
            setSingleLine(true)
            textSize = 17f
            typeface = nerdFont
            setTextColor(colors.foreground)
            setHintTextColor(withAlpha(colors.foreground, 0x94))
            background = null
            setPadding(0, 0, 0, 0)
            hint = rootTitle ?: context.getString(R.string.menu_go)
            setOnKeyListener { _, keyCode, event ->
                val enter = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                if (enter && event?.action == KeyEvent.ACTION_DOWN) {
                    executeFirstVisible()
                    true
                } else false
            }
            setOnEditorActionListener { _, actionId, event ->
                val enterKey = event?.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event?.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                val submitAction = actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_UNSPECIFIED
                if (enterKey || submitAction) {
                    executeFirstVisible()
                    true
                } else false
            }
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
        if (mode.inputPlacement == OmarchyMenuInputPlacement.TOP) {
            card.addView(headerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }

        rows.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = rowAdapter
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        card.addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (mode.inputPlacement == OmarchyMenuInputPlacement.BOTTOM) {
            card.addView(headerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val backgroundTap = OmarchyMenuDismissPolicy.isBackgroundTap(
            event.x,
            event.y,
            card.left,
            card.top,
            card.right,
            card.bottom,
        )
        if (backgroundTap) {
            if (event.actionMasked == MotionEvent.ACTION_UP) dismiss()
            return true
        }
        return super.dispatchTouchEvent(event)
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
            OmarchyMenuSearch.results(rootEntries, searchEntries, query, searchMode())
        val fixedHeight = dp(12 + 44 + 16)
        val maximumHeight = OmarchyMenuHeightPolicy.maximumHeight(height, mode)
        var visibleRowsHeight = 0
        visibleEntries.forEach { entry ->
            val rowHeight = dp(if (entry.detail == null) 54 else 62)
            if (fixedHeight + visibleRowsHeight + rowHeight <= maximumHeight) visibleRowsHeight += rowHeight
        }
        if (visibleEntries.isEmpty()) visibleRowsHeight = dp(50)
        val dynamicHeight = (fixedHeight + visibleRowsHeight).coerceAtLeast(dp(120))
        val cardHeight = OmarchyMenuCardHeightPolicy.height(dynamicHeight, maximumHeight, mode)
        val availableBottom = OmarchyMenuAvailableHeightPolicy.bottom(height, imeBottomInset, mode)
        val centeredTop = (availableBottom - cardHeight) / 2
        val statusBar = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())
            ?.top
            ?.coerceAtLeast(dp(24)) ?: dp(24)
        card.layoutParams = LayoutParams(cardWidth, cardHeight, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = OmarchyMenuVerticalPlacement.top(
                viewportHeight = availableBottom,
                cardHeight = cardHeight,
                mode = mode,
                searchFocused = header.hasFocus(),
                centeredTop = centeredTop,
                statusBar = statusBar,
                gap = dp(16),
            )
        }
        if (mode == OmarchyMenuMode.APPS_ONLY && imeBottomInset > 0) {
            postDelayed(::stabilizeAppsOnlyInputBottom, 120)
        }
    }

    private fun stabilizeAppsOnlyInputBottom() {
        if (mode != OmarchyMenuMode.APPS_ONLY || imeBottomInset <= 0 || header.height <= 0) return
        val location = IntArray(2)
        header.getLocationOnScreen(location)
        val currentBottom = location[1] + header.height
        val targetBottom = appsOnlyInputBottomOnScreen
        if (targetBottom == null) {
            appsOnlyInputBottomOnScreen = currentBottom
        } else {
            card.translationY += (targetBottom - currentBottom).toFloat()
        }
    }

    private fun updateImeVisibility(imeVisible: Boolean, imeBottom: Int = if (imeVisible) 1 else 0) {
        val actuallyVisible = imeVisible && imeBottom > 0
        val nextInset = if (actuallyVisible) imeBottom else 0
        if (!actuallyVisible) {
            appsOnlyInputBottomOnScreen = null
            card.translationY = 0f
        }
        if (imeBottomInset != nextInset) {
            imeBottomInset = nextInset
            post(::updateCardLayout)
        }
        if (OmarchyMenuFocusPolicy.shouldDismiss(mode, header.hasFocus(), imeWasVisible, actuallyVisible)) {
            dismiss()
            return
        }
        if (OmarchyMenuFocusPolicy.shouldClearFocus(header.hasFocus(), imeWasVisible, actuallyVisible)) {
            exitInputMode()
        }
        imeWasVisible = header.hasFocus() && actuallyVisible
    }

    private fun handleImeDismissal() {
        if (mode == OmarchyMenuMode.APPS_ONLY) dismiss() else exitInputMode()
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
            OmarchyMenuSearch.results(rootEntries, searchEntries, normalized, searchMode())
        visibleEntries = visible
        rowAdapter.submit(visible)
    }

    /** Enter runs the first visible entry (dmenu-style): submenus open, actions execute. */
    private fun executeFirstVisible() {
        val entry = OmarchyMenuEnterPolicy.firstVisible(visibleEntries) ?: return
        when {
            OmarchyMenuEnterPolicy.shouldOpen(entry) -> open(entry)
            OmarchyMenuEnterPolicy.shouldRun(entry) -> entry.action?.let { dismiss(it) }
        }
    }

    private fun searchMode(): OmarchyMenuSearchMode = when (mode) {
        OmarchyMenuMode.FULL -> OmarchyMenuSearchMode.ALL
        OmarchyMenuMode.APPS_ONLY -> OmarchyMenuSearchMode.APPS_ONLY
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

        if (entry.trailingIcon != null && entry.trailingAction != null) {
            addView(TextView(context).apply {
                text = entry.trailingIcon.invoke()
                textSize = 20f
                typeface = nerdFont
                gravity = Gravity.CENTER
                setTextColor(colors.muted)
                contentDescription = entry.trailingContentDescription?.invoke()
                isClickable = true
                isFocusable = false
                elevation = dp(4).toFloat()
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        entry.trailingAction.invoke()
                        text = entry.trailingIcon.invoke()
                        contentDescription = entry.trailingContentDescription?.invoke()
                        setTextColor(colors.selectedText)
                    }
                    true
                }
            }, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
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
        if (!dismissGuard.begin()) return
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
