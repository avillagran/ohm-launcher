package cl.villagranquiroz.ohm_launcher

import android.content.ClipData
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.DragEvent
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.villagranquiroz.ohm_launcher.qml.QmlViewRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class NativeLauncherView(context: Context) : FrameLayout(context) {
    private data class WidgetDrag(val widgetIndex: Int)
    private data class EdgeBoxDrag(val id: String, val source: View)

    var onQuakeRequested: (() -> Unit)? = null
    var onQuakeCloseRequested: (() -> Unit)? = null
    var appWidgetHostController: AndroidAppWidgetHostController? = null
    private val desktopLayer = FrameLayout(context)
    private val wallpaper = ImageView(context)
    private val ttfx = NativeTtfxView(context)
    private val ttfxMini = TtfxMiniControlsView(context)
    private val content = WidgetGridLayout(context)
    private val favorites = LinearLayout(context)
    private val edgeLayer = FrameLayout(context)
    private val commandContainer = FrameLayout(context)
    private val commandBar = LinearLayout(context)
    private val commandInput = EditText(context)
    private val commandToggle = TextView(context)
    private val commandResults = LinearLayout(context)
    private val drawer = FrameLayout(context)
    private val drawerApps = RecyclerView(context)
    private val title = TextView(context)
    private val clockHandler = Handler(Looper.getMainLooper())
    private var config = LauncherConfig.parse(ConfigStorage.DEFAULT_CONFIG)
    private var settings = LauncherSettings.parse("{}")
    private var omarchyTheme: OmarchyThemePalette? = null
    private var themeTransitionTarget: OmarchyThemePalette? = null
    private var pendingThemeSettings: LauncherSettings? = null
    private var themeTransitionGeneration = 0
    private var apps: List<InstalledApp> = emptyList()
    private var appSearchIndex = AppSearchIndex(emptyList())
    private var appsBySearchKey: Map<String, InstalledApp> = emptyMap()
    private var plugins: Map<String, Plugin> = emptyMap()
    private var runtimeWidgets: List<org.json.JSONObject> = emptyList()
    private var favoriteKeys: List<String> = emptyList()
    private var desktopIndex = 0
    private var drawerVisible = false
    private var quakeVisible = false
    private var widgetEditing = false
    private var commandCollapsed = false
    private var drawerDragStartX = 0f
    private var drawerDragStartY = 0f
    private var drawerDragging = false
    private var drawerGestureConsumed = false
    private var edgeBoxDragging = false
    private val edgeBoxExpandedOverrides = mutableMapOf<String, Boolean>()
    private val edgeDropIndicators = mutableMapOf<EdgePosition, View>()
    private var orbitalMenu: OrbitalActionMenu? = null
    private val favoritesWriter = Executors.newSingleThreadExecutor()
    private val appAdapter = AppAdapter(
        context = context,
        onClick = {
            if (!drawerGestureConsumed) {
                AppCatalog.launch(context, it)
                showDrawer(false)
            }
        },
        onLongClick = ::showAppMenu,
    )
    private val clockTick = object : Runnable {
        override fun run() {
            updateClocks(this@NativeLauncherView)
            clockHandler.postDelayed(this, 1000)
        }
    }

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onFling(
                first: MotionEvent?,
                second: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (first == null) return false
                val dx = second.x - first.x
                val dy = second.y - first.y
                if (edgeBoxDragging) return true
                if (quakeVisible) {
                    val action = LauncherGesturePolicy.verticalAction(
                        quakeVisible = true,
                        drawerVisible = false,
                        deltaY = dy,
                        startedInLowerHalf = first.y > height * .45f,
                    )
                    if (action == LauncherVerticalAction.CLOSE_QUAKE) {
                        onQuakeCloseRequested?.invoke()
                        return true
                    }
                    return false
                }
                if (abs(dx) > abs(dy) && abs(dx) > 80f) {
                    showDesktop(desktopIndex + if (dx < 0) 1 else -1)
                    return true
                }
                if (abs(dy) > abs(dx) && abs(dy) > 100f) {
                    return when (
                        LauncherGesturePolicy.verticalAction(
                            quakeVisible = quakeVisible,
                            drawerVisible = drawerVisible,
                            deltaY = dy,
                            startedInLowerHalf = first.y > height * .45f,
                        )
                    ) {
                        LauncherVerticalAction.OPEN_DRAWER -> true.also { showDrawer(true) }
                        LauncherVerticalAction.CLOSE_DRAWER -> true.also { showDrawer(false) }
                        LauncherVerticalAction.OPEN_QUAKE -> true.also { onQuakeRequested?.invoke() }
                        LauncherVerticalAction.CLOSE_QUAKE, LauncherVerticalAction.NONE -> false
                    }
                }
                return false
            }
        },
    )
    private val backgroundGestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (!WidgetEditModePolicy.shouldExit(widgetEditing, 2)) return false
                setWidgetEditing(false)
                return true
            }

            override fun onLongPress(event: MotionEvent) {
                if (!widgetEditing) showDesktopMenu()
            }
        },
    )

    init {
        clipChildren = false
        clipToPadding = false
        addView(desktopLayer, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        wallpaper.scaleType = ImageView.ScaleType.CENTER_CROP
        desktopLayer.addView(wallpaper, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        desktopLayer.addView(ttfx, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        content.setPadding(dp(18), dp(52), dp(18), dp(110))
        content.setOnDragListener { _, event -> handleWidgetDrag(event) }
        content.setOnTouchListener { _, event ->
            if (CommandBarFocusPolicy.shouldDismiss(commandInput.hasFocus(), event.actionMasked == MotionEvent.ACTION_DOWN)) {
                dismissCommandInput()
            }
            backgroundGestures.onTouchEvent(event)
        }
        desktopLayer.addView(content, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        title.setTextColor(Color.WHITE)
        title.textSize = 12f
        title.gravity = Gravity.CENTER
        title.setPadding(dp(12), dp(8), dp(12), dp(8))
        val titleParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        titleParams.topMargin = dp(32)
        desktopLayer.addView(title, titleParams)

        favorites.orientation = LinearLayout.HORIZONTAL
        favorites.gravity = Gravity.CENTER
        favorites.background = rounded(0xD9141B22.toInt(), dp(22).toFloat(), 0x5566E0FF)
        favorites.setPadding(dp(10), dp(7), dp(10), dp(7))
        val favoriteParams = LayoutParams(WRAP_CONTENT, dp(66), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        favoriteParams.bottomMargin = dp(22)
        desktopLayer.addView(favorites, favoriteParams)
        edgeLayer.setOnDragListener { _, event -> handleEdgeBoxDrag(event) }
        desktopLayer.addView(edgeLayer, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        ttfxMini.apply {
            onPreview = { ttfx.submit(it) }
            onCommit = { (context as? MainActivity)?.saveDesktopTtfx(desktopIndex, it) }
        }
        desktopLayer.addView(
            ttfxMini,
            LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(10)
                bottomMargin = dp(94)
            },
        )
        buildCommandBar()
        commandResults.apply {
            orientation = LinearLayout.VERTICAL
            visibility = GONE
            elevation = dp(18).toFloat()
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        desktopLayer.addView(commandResults)
        desktopLayer.addView(commandContainer)

        buildDrawer()
        addView(drawer, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        drawer.visibility = GONE
        drawer.alpha = 0f

        setOnLongClickListener {
            showDesktopMenu()
            true
        }
        isLongClickable = true
        clockHandler.post(clockTick)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!widgetEditing) {
            if (!quakeVisible) updateDrawerDrag(event)
            gestures.onTouchEvent(event)
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            post { drawerGestureConsumed = false }
        }
        return handled
    }

    private fun updateDrawerDrag(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawerGestureConsumed = false
                drawerDragStartX = event.x
                drawerDragStartY = event.y
                drawerDragging = drawerVisible || event.y > height * .45f
            }
            MotionEvent.ACTION_MOVE -> {
                if (!drawerDragging) return
                val dx = event.x - drawerDragStartX
                val dy = event.y - drawerDragStartY
                if (abs(dy) <= abs(dx) + dp(12)) return
                if (!DrawerTapPolicy.mayLaunchApp(dy, ViewConfiguration.get(context).scaledTouchSlop.toFloat())) {
                    drawerGestureConsumed = true
                    drawerApps.stopScroll()
                }
                val openingDistance = if (drawerVisible) height - dy.coerceAtLeast(0f) else (-dy * 2.5f).coerceAtLeast(0f)
                if (openingDistance <= 0f) return
                if (drawer.visibility != VISIBLE) drawer.visibility = VISIBLE
                val progress = (openingDistance / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                drawer.translationY = height * (1f - progress)
                drawer.alpha = progress
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!drawerDragging) return
                val dy = event.y - drawerDragStartY
                val progress = if (drawerVisible) 1f - (dy / height.coerceAtLeast(1))
                    else (-dy * 2.5f / height.coerceAtLeast(1))
                showDrawer(progress > if (drawerVisible) .85f else .15f)
                drawerDragging = false
            }
        }
    }

    fun submitConfig(value: LauncherConfig) {
        config = value
        favoriteKeys = value.favorites
        desktopIndex = desktopIndex.coerceIn(0, value.desktops.lastIndex.coerceAtLeast(0))
        appAdapter.submit(apps, favoriteKeys)
        renderFavorites()
        renderEdgeBoxes()
        renderDesktop()
    }

    fun submitSettings(value: LauncherSettings) {
        val previousTheme = omarchyTheme
        val nextTheme = OmarchyThemePalette.fromSettings(value.raw)
        if (themeTransitionTarget == nextTheme) {
            pendingThemeSettings = value
            return
        }
        if (OmarchyThemeTransitionPolicy.shouldAnimate(previousTheme, nextTheme, width, height)) {
            startThemeTransition(value, nextTheme)
            return
        }
        applySettingsNow(value, nextTheme)
    }

    private fun applySettingsNow(value: LauncherSettings, theme: OmarchyThemePalette?) {
        settings = value
        omarchyTheme = theme
        ttfx.submitTheme(omarchyTheme)
        applyThemeChrome()
        applyFavoriteBarSettings()
        applyCommandBarSettings(forceFromSetting = true)
        renderFavorites()
        renderEdgeBoxes()
        renderDesktop()
    }

    fun submitApps(value: List<InstalledApp>) {
        apps = value
        val searchable = value.map { app ->
            SearchableApp(
                key = "${app.packageName}/${app.activityName}",
                label = app.label,
                packageName = app.packageName,
            )
        }
        appSearchIndex = AppSearchIndex(searchable)
        appsBySearchKey = value.associateBy { "${it.packageName}/${it.activityName}" }
        appAdapter.submit(value, favoriteKeys)
        renderFavorites()
        renderEdgeBoxes()
        renderDesktop()
    }

    fun submitPlugins(value: List<Plugin>) {
        plugins = value.associateBy(Plugin::id)
        renderEdgeBoxes()
        renderDesktop()
    }

    fun submitRuntimeWidgets(value: List<org.json.JSONObject>) {
        runtimeWidgets = value
        renderDesktop()
    }

    fun refreshAudioCapture() = ttfx.refreshAudioCapture()

    fun previewTtfx(desktop: Int, value: TtfxConfig) {
        if (desktop == desktopIndex) ttfx.submit(value)
    }

    fun activeDesktopIndex(): Int = desktopIndex

    fun setQuakeVisible(visible: Boolean) {
        quakeVisible = visible
        if (visible) showDrawer(false)
    }

    fun showConfigError(message: String) {
        Toast.makeText(context, "Config inválida: $message", Toast.LENGTH_LONG).show()
    }

    fun showPeerUri(uri: Uri) {
        Toast.makeText(context, "Omarchy: $uri", Toast.LENGTH_LONG).show()
    }

    private fun setWidgetEditing(enabled: Boolean) {
        if (widgetEditing == enabled) return
        widgetEditing = enabled
        renderDesktop()
        if (enabled) {
            edgeLayer.visibility = GONE
            ttfxMini.visibility = GONE
            favorites.visibility = GONE
            commandContainer.visibility = GONE
        } else {
            edgeLayer.visibility = VISIBLE
            ttfxMini.submit(config.desktops[desktopIndex].ttfx)
            commandContainer.visibility = VISIBLE
            applyFavoriteBarSettings()
            applyCommandBarSettings()
        }
    }

    private fun showDesktop(index: Int) {
        if (drawerVisible || config.desktops.isEmpty()) return
        val next = index.coerceIn(0, config.desktops.lastIndex)
        if (next == desktopIndex) return
        val previous = desktopIndex
        val entryDirection = DesktopTransitionPolicy.entryDirection(previous, next)
        content.animate().cancel()
        title.animate().cancel()
        content.animate()
            .translationX(-entryDirection * width * .22f)
            .alpha(0f)
            .setDuration(95)
            .withEndAction {
                desktopIndex = next
                renderDesktop()
                content.translationX = entryDirection * width * .22f
                title.translationX = entryDirection * width * .12f
                content.animate().translationX(0f).alpha(1f).setDuration(190).start()
                title.animate().translationX(0f).alpha(1f).setDuration(190).start()
            }
            .start()
        title.animate().translationX(-entryDirection * width * .12f).alpha(0f).setDuration(95).start()
    }

    private fun renderDesktop() {
        if (config.desktops.isEmpty()) return
        val desktop = config.desktops[desktopIndex]
        desktopLayer.setBackgroundColor(
            themeColor("background", parseColor(desktop.background, 0xFF0B0F14.toInt())),
        )
        if (desktop.backgroundImage.isBlank()) {
            wallpaper.visibility = GONE
            wallpaper.setImageDrawable(null)
        } else {
            val uri = Uri.parse(desktop.backgroundImage).let { parsed ->
                if (parsed.scheme == null) Uri.fromFile(java.io.File(desktop.backgroundImage)) else parsed
            }
            wallpaper.setImageURI(uri)
            wallpaper.visibility = VISIBLE
        }
        ttfx.submit(desktop.ttfx)
        ttfxMini.submit(desktop.ttfx)
        val desktopTitle = DesktopTitlePolicy.text(desktop.name, desktopIndex, config.desktops.size)
        title.text = desktopTitle.orEmpty()
        title.visibility = if (desktopTitle == null) GONE else VISIBLE
        content.configureGrid(desktop.gridColumns, desktop.gridRows)
        content.removeAllViews()
        desktop.widgets.forEachIndexed { index, node -> content.addWidget(renderEditableWidget(node, index), node) }
        runtimeWidgets.forEach { raw ->
            content.addCentered(renderWidget(WidgetNode(raw.optString("type", "container"), raw)))
        }
        if (desktop.widgets.isEmpty() && runtimeWidgets.isEmpty()) {
            content.addCentered(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(clockView("HH:mm"))
                addView(label("Desliza hacia arriba para ver tus apps", 13f, 0xAA9FB3C8.toInt()))
            })
        }
    }

    private fun renderWidget(node: WidgetNode): View = when (node.type) {
        "clock" -> {
            val format = node.raw.optString("format", "HH:mm")
            val size = node.raw.optDouble("fontSize", node.raw.optDouble("size", 58.0)).toFloat()
            val color = themeColor("accent", parseColor(node.raw.optString("color"), 0xFFBDEFFF.toInt()))
            if (ClockStylePolicy.isParticle(node.raw.optString("style"))) {
                ParticleClockView(
                    context = context,
                    format = format,
                    color = color,
                    configuredTextSize = size,
                    samplingDensity = node.raw.optDouble("density", 3.0).toFloat().coerceIn(1.5f, 8f),
                    particleSize = node.raw.optDouble("particleSize", 1.6).toFloat().coerceIn(.4f, 5f),
                    wobble = node.raw.optDouble("wobble", 1.0).toFloat().coerceIn(0f, 5f),
                )
            } else {
                clockView(format, size, color)
            }
        }
        "text" -> label(
            node.raw.optString("text", node.raw.optString("value", "")),
            node.raw.optDouble("fontSize", node.raw.optDouble("size", 18.0)).toFloat(),
            parseColor(node.raw.optString("color"), Color.WHITE),
        )
        "container" -> renderContainer(node.raw)
        "tiling_layout" -> renderTiling(node.raw)
        "spacer" -> Space(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(node.raw.optInt("width", 0)),
                dp(node.raw.optInt("height", node.raw.optInt("size", 8))),
            )
        }
        "battery" -> {
            val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            label(
                "Batería ${manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%",
                16f,
                themeColor("accent", 0xFF66E0FF.toInt()),
            )
        }
        "apps_grid" -> appStrip(apps.take(node.raw.optInt("limit", 8)))
        "plugin_widget" -> renderPlugin(node.raw.optString("pluginId"))
        "system_widget" -> renderSystemWidget(node.raw)
        "qml" -> QmlViewRenderer(context).render(node.raw.optString("source"))
        else -> label(node.raw.optString("text", node.type), 14f, 0xFF9FB3C8.toInt())
    }

    private fun renderEditableWidget(node: WidgetNode, index: Int): View {
        val wrapper = FrameLayout(context)
        val child = renderWidget(node)
        wrapper.addView(child, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        val startDrag = View.OnLongClickListener {
            wrapper.startDragAndDrop(
                ClipData.newPlainText("ohm-widget", index.toString()),
                View.DragShadowBuilder(wrapper),
                WidgetDrag(index),
                0,
            )
        }
        if (widgetEditing) {
            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
            var downRawX = 0f
            var downRawY = 0f
            var dragging = false
            val overlay = View(context).apply {
                contentDescription = "Mover widget"
                isClickable = true
                background = rounded(
                    alphaColor(themeColor("lighter_background", 0xFF151D26.toInt()), 0x22),
                    dp(8).toFloat(),
                    themeColor("accent", 0xFF66E0FF.toInt()),
                )
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = event.rawX
                            downRawY = event.rawY
                            dragging = false
                            wrapper.bringToFront()
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val deltaX = event.rawX - downRawX
                            val deltaY = event.rawY - downRawY
                            if (!dragging && kotlin.math.hypot(deltaX.toDouble(), deltaY.toDouble()) >= touchSlop) {
                                dragging = true
                            }
                            if (dragging) {
                                wrapper.translationX = deltaX.coerceIn(
                                    -wrapper.left.toFloat(),
                                    (content.width - wrapper.right).toFloat(),
                                )
                                wrapper.translationY = deltaY.coerceIn(
                                    -wrapper.top.toFloat(),
                                    (content.height - wrapper.bottom).toFloat(),
                                )
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (dragging) {
                                val cell = content.movedCell(wrapper, wrapper.translationX, wrapper.translationY)
                                wrapper.translationX = 0f
                                wrapper.translationY = 0f
                                (context as? MainActivity)?.moveDesktopWidget(
                                    desktopIndex,
                                    index,
                                    cell.column,
                                    cell.row,
                                )
                            } else {
                                (context as? MainActivity)?.showWidgetMenu(desktopIndex, index)
                            }
                            dragging = false
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            wrapper.translationX = 0f
                            wrapper.translationY = 0f
                            dragging = false
                            true
                        }
                        else -> true
                    }
                }
            }
            wrapper.addView(overlay, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addResizeHandle(wrapper, node, index, ResizeCorner.TOP_LEFT, Gravity.TOP or Gravity.START)
            addResizeHandle(wrapper, node, index, ResizeCorner.TOP_RIGHT, Gravity.TOP or Gravity.END)
            addResizeHandle(wrapper, node, index, ResizeCorner.BOTTOM_LEFT, Gravity.BOTTOM or Gravity.START)
            addResizeHandle(wrapper, node, index, ResizeCorner.BOTTOM_RIGHT, Gravity.BOTTOM or Gravity.END)
        } else {
            wrapper.setOnLongClickListener(startDrag)
            child.setOnLongClickListener(startDrag)
        }
        if (settings.showTapBoxes && !widgetEditing) {
            wrapper.background = rounded(Color.TRANSPARENT, dp(8).toFloat(), 0x8866E0FF.toInt())
        }
        return wrapper
    }

    private fun addResizeHandle(
        wrapper: FrameLayout,
        node: WidgetNode,
        index: Int,
        corner: ResizeCorner,
        gravity: Int,
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var preview = WidgetGridRect(node.x, node.y, node.width, node.height)
        val original = preview
        val label = when (corner) {
            ResizeCorner.TOP_LEFT -> "Redimensionar arriba izquierda"
            ResizeCorner.TOP_RIGHT -> "Redimensionar arriba derecha"
            ResizeCorner.BOTTOM_LEFT -> "Redimensionar abajo izquierda"
            ResizeCorner.BOTTOM_RIGHT -> "Redimensionar abajo derecha"
        }
        val handle = View(context).apply {
            contentDescription = label
            isClickable = true
            background = rounded(
                themeColor("accent", 0xFF66E0FF.toInt()),
                dp(14).toFloat(),
                themeColor("bright_foreground", Color.WHITE),
            )
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        preview = original
                        wrapper.bringToFront()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val cellWidth = ((content.width - content.paddingLeft - content.paddingRight).toFloat() /
                            content.columns).coerceAtLeast(1f)
                        val cellHeight = ((content.height - content.paddingTop - content.paddingBottom).toFloat() /
                            content.rows).coerceAtLeast(1f)
                        preview = WidgetResizeGeometry.resize(
                            original,
                            corner,
                            ((event.rawX - downRawX) / cellWidth).roundToInt(),
                            ((event.rawY - downRawY) / cellHeight).roundToInt(),
                            content.columns,
                            content.rows,
                        )
                        content.previewWidget(wrapper, preview)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (preview != original) {
                            (context as? MainActivity)?.resizeDesktopWidget(desktopIndex, index, preview)
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        content.previewWidget(wrapper, original)
                        preview = original
                        true
                    }
                    else -> true
                }
            }
        }
        wrapper.addView(handle, LayoutParams(dp(28), dp(28), gravity))
    }

    private fun handleWidgetDrag(event: DragEvent): Boolean {
        val drag = event.localState as? WidgetDrag ?: return false
        if (event.action != DragEvent.ACTION_DROP) return true
        val cell = content.cellAt(event.x, event.y)
        val activity = context as? MainActivity ?: return true
        val widget = config.desktops.getOrNull(desktopIndex)?.widgets?.getOrNull(drag.widgetIndex)
        if (widget?.x == cell.column && widget.y == cell.row) activity.showWidgetMenu(desktopIndex, drag.widgetIndex)
        else activity.moveDesktopWidget(desktopIndex, drag.widgetIndex, cell.column, cell.row)
        return true
    }

    private fun renderPlugin(id: String): View {
        val plugin = plugins[id]
            ?: return label("Plugin · $id", 14f, 0xFFFFB86C.toInt())
        val entry = plugin.entryFileForKind("bar-widget")
            ?: return label("Plugin error · $id", 12f, 0xFFFF6B7A.toInt())
        return runCatching {
            if (entry.extension.equals("json", ignoreCase = true)) {
                val raw = org.json.JSONObject(entry.readText())
                renderWidget(WidgetNode(raw.optString("type", "container"), raw))
            } else {
                QmlViewRenderer(context).render(entry.readText(), plugin.folder)
            }
        }
            .getOrElse { label("Plugin error · $id", 12f, 0xFFFF6B7A.toInt()) }
    }

    private fun renderSystemWidget(raw: org.json.JSONObject): View {
        val appWidgetId = raw.optInt("appWidgetId", -1)
        val width = raw.optInt("minWidth", dp(220)).coerceAtLeast(dp(80))
        val height = raw.optInt("minHeight", dp(120)).coerceAtLeast(dp(60))
        return appWidgetHostController?.createHostView(
            context,
            appWidgetId,
            mapOf("width" to width, "height" to height),
        ) ?: label("Widget Android · ${raw.optString("label", raw.optString("provider"))}", 13f, 0xFFFFB86C.toInt())
    }

    private fun renderContainer(raw: org.json.JSONObject): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = rounded(
            parseColor(raw.optString("color"), 0x22151D26),
            dp(raw.optInt("borderRadius", 0)).toFloat(),
            parseColor(raw.optString("borderColor"), Color.TRANSPARENT),
        )
        val padding = raw.opt("padding")
        if (padding is org.json.JSONObject) {
            setPadding(
                dp(padding.optInt("left", 0)),
                dp(padding.optInt("top", 0)),
                dp(padding.optInt("right", 0)),
                dp(padding.optInt("bottom", 0)),
            )
        } else {
            val all = dp((padding as? Number)?.toInt() ?: 0)
            setPadding(all, all, all, all)
        }
        addChildren(this, raw.optJSONArray("children"), 0)
    }

    private fun renderTiling(raw: org.json.JSONObject): View = LinearLayout(context).apply {
        orientation = if (raw.optString("orientation") == "row") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addChildren(this, raw.optJSONArray("children"), raw.optInt("spacing", 0))
    }

    private fun addChildren(parent: LinearLayout, children: org.json.JSONArray?, spacing: Int) {
        if (children == null) return
        for (index in 0 until children.length()) {
            val raw = children.optJSONObject(index) ?: continue
            val child = renderWidget(WidgetNode(raw.optString("type", "container"), raw))
            val params = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            if (index > 0) {
                if (parent.orientation == LinearLayout.HORIZONTAL) params.leftMargin = dp(spacing)
                else params.topMargin = dp(spacing)
            }
            parent.addView(child, params)
        }
    }

    private fun clockView(
        format: String,
        size: Float = 58f,
        color: Int = 0xFFBDEFFF.toInt(),
    ): TextView = label("", size, color).also { view ->
        view.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        view.tag = format
        view.text = formatClock(format)
    }


    private fun updateClocks(group: ViewGroup) {
        for (index in 0 until group.childCount) {
            when (val child = group.getChildAt(index)) {
                is TextView -> (child.tag as? String)?.let { child.text = formatClock(it) }
                is ViewGroup -> updateClocks(child)
            }
        }
    }

    private fun formatClock(pattern: String): String = runCatching {
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    }.getOrElse { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }

    private fun renderFavorites() {
        favorites.removeAllViews()
        FavoritesConfigEditor.resolve(favoriteKeys, apps).take(7).forEach { app ->
            val icon = ImageView(context).apply {
                setImageDrawable(context.packageManager.getActivityIcon(android.content.ComponentName(app.packageName, app.activityName)))
                setPadding(dp(7), dp(7), dp(7), dp(7))
                contentDescription = app.label
                setOnClickListener { AppCatalog.launch(context, app) }
                setOnLongClickListener { showAppMenu(app, this) }
            }
            favorites.addView(icon, LinearLayout.LayoutParams(dp(50), dp(50)))
        }
        favorites.visibility = if (settings.favoritesBarVisible && favorites.childCount > 0) VISIBLE else GONE
    }

    private fun applyFavoriteBarSettings() {
        val vertical = settings.effectiveFavoritesBarMode in setOf(FavoritesBarMode.VERTICAL, FavoritesBarMode.LIST)
        favorites.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        favorites.background = rounded(
            alphaColor(themeColor("dark_background", 0xFF141B22.toInt()), 0xD9),
            dp(settings.barRadius.toInt()).toFloat(),
            alphaColor(themeColor("accent", 0xFF66E0FF.toInt()), 0x55),
        )
        favorites.layoutParams = LayoutParams(
            if (vertical) dp(66) else WRAP_CONTENT,
            if (vertical) WRAP_CONTENT else dp(66),
            when (settings.favoritesBarPosition) {
                LauncherEdge.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                LauncherEdge.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                LauncherEdge.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
                LauncherEdge.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
            },
        ).apply {
            val margin = dp(22)
            setMargins(margin, margin, margin, margin)
        }
    }

    private fun renderEdgeBoxes() {
        edgeLayer.removeAllViews()
        EdgePosition.entries.forEach { edge ->
            val boxes = config.edgeBoxes.filter { it.visible && it.edge == edge }
            if (boxes.isEmpty()) return@forEach
            val horizontalEdge = edge == EdgePosition.TOP || edge == EdgePosition.BOTTOM
            val group = LinearLayout(context).apply {
                orientation = if (horizontalEdge) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                clipChildren = false
            }
            boxes.forEachIndexed { index, box ->
                val params = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                if (index > 0) {
                    if (horizontalEdge) params.leftMargin = dp((settings.boxSpacing * 8).toInt())
                    else params.topMargin = dp((settings.boxSpacing * 8).toInt())
                }
                group.addView(renderEdgeBox(box), params)
            }
            edgeLayer.addView(
                group,
                LayoutParams(WRAP_CONTENT, WRAP_CONTENT, when (edge) {
                    EdgePosition.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    EdgePosition.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    EdgePosition.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
                    EdgePosition.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
                }).apply {
                    val margin = dp(12)
                    setMargins(margin, dp(64), margin, dp(38))
                },
            )
        }
    }

    private fun renderEdgeBox(box: EdgeBoxConfig): View = LinearLayout(context).apply {
        val vertical = box.direction in setOf(EdgeDirection.VERTICAL, EdgeDirection.LIST)
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(7), dp(5), dp(7), dp(5))
        background = rounded(
            alphaColor(themeColor("dark_background", 0xFF141B22.toInt()), 0xD9),
            dp(settings.boxRadius.toInt()).toFloat(),
            themeColor("accent", parseColor(box.color, 0xFF66E0FF.toInt())),
        )
        if (box.showTitle) {
            addView(label(box.name, 10f, themeColor("accent", parseColor(box.color, 0xFF66E0FF.toInt()))))
        }
        val expanded = edgeBoxExpandedOverrides[box.id] ?: !box.compact
        val visibleItems = if (expanded) box.items else box.items.getOrNull(box.compactItem)?.let(::listOf).orEmpty()
        visibleItems.forEach { item ->
            val fixedIcon = item.type == EdgeItemType.APP
            addView(
                renderEdgeItem(item),
                LinearLayout.LayoutParams(
                    if (fixedIcon) dp(48) else WRAP_CONTENT,
                    if (fixedIcon) dp(48) else WRAP_CONTENT,
                ),
            )
        }
        if (box.showExpandButton && box.items.size > 1) {
            addView(label(if (expanded) "−" else "+", 16f, themeColor("accent", 0xFF66E0FF.toInt())).apply {
                contentDescription = if (expanded) "Compactar ${box.name}" else "Expandir ${box.name}"
                background = rounded(
                    alphaColor(themeColor("lighter_background", 0xFF151D26.toInt()), 0x99),
                    dp(12).toFloat(),
                    alphaColor(themeColor("accent", 0xFF66E0FF.toInt()), 0x66),
                )
                setOnClickListener {
                    edgeBoxExpandedOverrides[box.id] = !expanded
                    renderEdgeBoxes()
                }
            })
        }
        installEdgeBoxGesture(this, box)
    }

    private fun installEdgeBoxGesture(source: View, box: EdgeBoxConfig) {
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false
        var settingsOpened = false
        fun dropTarget(rawX: Float, rawY: Float): EdgePosition? {
            val location = IntArray(2)
            edgeLayer.getLocationOnScreen(location)
            return EdgeDropTarget.target(
                edgeLayer.width,
                edgeLayer.height,
                rawX - location[0],
                rawY - location[1],
                dp(120).toFloat(),
            )
        }
        val openSettings = Runnable {
            if (!dragging) {
                settingsOpened = true
                (context as? MainActivity)?.showEdgeBoxMenu(box.id)
            }
        }
        val listener = OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    settingsOpened = false
                    clockHandler.removeCallbacks(openSettings)
                    clockHandler.postDelayed(openSettings, EdgeBoxInteractionState.SETTINGS_MILLIS)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val displacement = kotlin.math.hypot(
                        (event.rawX - downRawX).toDouble(),
                        (event.rawY - downRawY).toDouble(),
                    ).toFloat()
                    if (displacement > EdgeBoxInteractionState.STILLNESS_SLOP_PX && !dragging) {
                        clockHandler.removeCallbacks(openSettings)
                        clockHandler.postDelayed(openSettings, EdgeBoxInteractionState.SETTINGS_MILLIS)
                    }
                    if (!dragging && displacement >= EdgeBoxInteractionState.BOX_DRAG_SLOP_PX) {
                        clockHandler.removeCallbacks(openSettings)
                        dragging = true
                        edgeBoxDragging = true
                        source.animate().alpha(.25f).scaleX(.92f).scaleY(.92f).setDuration(90).start()
                        showEdgeDropTargets(dropTarget(event.rawX, event.rawY))
                    }
                    if (dragging) showEdgeDropTargets(dropTarget(event.rawX, event.rawY))
                    dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clockHandler.removeCallbacks(openSettings)
                    if (dragging) {
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            dropTarget(event.rawX, event.rawY)?.let { target ->
                                (context as? MainActivity)?.moveEdgeBox(box.id, target)
                            }
                        }
                        clearEdgeDropTargets()
                        source.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start()
                        edgeBoxDragging = false
                    }
                    dragging || settingsOpened
                }
                else -> dragging
            }
        }
        fun attach(view: View) {
            view.setOnTouchListener(listener)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) attach(view.getChildAt(index))
            }
        }
        source.isClickable = true
        attach(source)
    }

    private fun handleEdgeBoxDrag(event: DragEvent): Boolean {
        val drag = event.localState as? EdgeBoxDrag ?: return false
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                showEdgeDropTargets(null)
                true
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                showEdgeDropTargets(
                    EdgeDropTarget.target(
                        edgeLayer.width,
                        edgeLayer.height,
                        event.x,
                        event.y,
                        dp(120).toFloat(),
                    ),
                )
                true
            }
            DragEvent.ACTION_DROP -> {
                val target = EdgeDropTarget.target(
                    edgeLayer.width,
                    edgeLayer.height,
                    event.x,
                    event.y,
                    dp(120).toFloat(),
                )
                if (target != null) (context as? MainActivity)?.moveEdgeBox(drag.id, target)
                target != null
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                clearEdgeDropTargets()
                drag.source.animate().alpha(1f).setDuration(120).start()
                true
            }
            else -> true
        }
    }

    private fun showEdgeDropTargets(active: EdgePosition?) {
        if (edgeDropIndicators.isEmpty()) {
            EdgePosition.entries.forEach { edge ->
                val horizontal = edge == EdgePosition.TOP || edge == EdgePosition.BOTTOM
                val indicator = View(context).apply {
                    contentDescription = "Destino ${edge.jsonName}"
                    background = rounded(
                        alphaColor(themeColor("accent", 0xFF66E0FF.toInt()), 0x88),
                        dp(18).toFloat(),
                        themeColor("bright_foreground", Color.WHITE),
                    )
                    elevation = dp(20).toFloat()
                }
                edgeDropIndicators[edge] = indicator
                edgeLayer.addView(
                    indicator,
                    LayoutParams(
                        if (horizontal) dp(150) else dp(24),
                        if (horizontal) dp(24) else dp(150),
                        when (edge) {
                            EdgePosition.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                            EdgePosition.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                            EdgePosition.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
                            EdgePosition.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
                        },
                    ).apply {
                        val margin = dp(22)
                        setMargins(margin, dp(72), margin, dp(46))
                    },
                )
            }
        }
        edgeDropIndicators.forEach { (edge, view) ->
            val selected = edge == active
            view.contentDescription = "Destino ${edge.jsonName}${if (selected) " activo" else ""}"
            view.animate()
                .alpha(if (selected) 1f else .38f)
                .scaleX(if (selected) 1.35f else 1f)
                .scaleY(if (selected) 1.35f else 1f)
                .setDuration(90)
                .start()
        }
    }

    private fun clearEdgeDropTargets() {
        edgeDropIndicators.values.forEach(edgeLayer::removeView)
        edgeDropIndicators.clear()
    }

    private fun renderEdgeItem(item: EdgeItemConfig): View = when (item.type) {
        EdgeItemType.APP -> {
            val app = apps.firstOrNull {
                it.packageName == item.packageName && (item.activity.isBlank() || it.activityName == item.activity)
            }
            if (app == null) label(item.label.ifBlank { item.packageName }, 9f, 0xFF9FB3C8.toInt())
            else ImageView(context).apply {
                setImageDrawable(runCatching {
                    context.packageManager.getActivityIcon(android.content.ComponentName(app.packageName, app.activityName))
                }.getOrNull())
                contentDescription = app.label
                setPadding(dp(5), dp(5), dp(5), dp(5))
                setOnClickListener { AppCatalog.launch(context, app) }
            }
        }
        EdgeItemType.SYSTEM_WIDGET -> renderSystemWidget(item.raw)
        EdgeItemType.PLUGIN -> renderPlugin(item.pluginId)
        EdgeItemType.UNKNOWN -> label(item.label.ifBlank { item.raw.optString("type") }, 9f, 0xFF9FB3C8.toInt())
    }

    private fun showAppMenu(app: InstalledApp, anchor: View): Boolean {
        val key = "${app.packageName}/${app.activityName}"
        val menu = android.widget.PopupMenu(context, anchor)
        val label = if (favoriteKeys.contains(key)) R.string.favorite_remove else R.string.favorite_add
        menu.menu.add(label).setOnMenuItemClickListener {
            favoriteKeys = FavoritesConfigEditor.toggle(favoriteKeys, key)
            appAdapter.submit(apps, favoriteKeys)
            renderFavorites()
            val snapshot = favoriteKeys
            favoritesWriter.execute {
                runCatching { ConfigStorage.writeActiveFavorites(snapshot) }
                    .onFailure { error -> post { showConfigError(error.message.orEmpty()) } }
            }
            true
        }
        menu.show()
        return true
    }

    private fun appStrip(items: List<InstalledApp>): View {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER }
        items.forEach { app ->
            val icon = ImageView(context).apply {
                setImageDrawable(context.packageManager.getActivityIcon(android.content.ComponentName(app.packageName, app.activityName)))
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener { AppCatalog.launch(context, app) }
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)))
        }
        return row
    }

    private fun buildCommandBar() {
        commandBar.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(48), dp(6))
            background = rounded(0xE6151D26.toInt(), dp(18).toFloat(), 0x5566E0FF)
        }
        commandInput.apply {
            hint = "Buscar app"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF74869A.toInt())
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            background = null
            addTextChangedListener(SimpleTextWatcher(::updateCommandSearch))
            setOnEditorActionListener { _, _, _ ->
                val query = text.toString().trim()
                when {
                    query.equals("terminal", ignoreCase = true) -> onQuakeRequested?.invoke()
                    query.equals("plugins", ignoreCase = true) -> (context as? MainActivity)?.showPluginManager()
                    else -> firstCommandApp(query)?.let { AppCatalog.launch(context, it) }
                }
                if (query.isNotEmpty()) text.clear()
                true
            }
        }
        commandBar.addView(commandInput, LinearLayout.LayoutParams(0, dp(44), 1f))
        commandBar.addView(commandButton("Terminal") { onQuakeRequested?.invoke() })
        commandBar.addView(commandButton("Plugins") { (context as? MainActivity)?.showPluginManager() })
        commandToggle.apply {
            gravity = Gravity.CENTER
            setTextColor(0xFF66E0FF.toInt())
            textSize = 18f
            background = rounded(0xFF151D26.toInt(), dp(18).toFloat(), 0x8866E0FF.toInt())
            setOnClickListener {
                commandCollapsed = !commandCollapsed
                applyCommandBarSettings()
            }
        }
        commandContainer.addView(commandBar, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        commandContainer.addView(commandToggle, LayoutParams(dp(42), dp(42), Gravity.END or Gravity.CENTER_VERTICAL))
        applyCommandBarSettings(forceFromSetting = true)
    }

    private fun firstCommandApp(query: String): InstalledApp? = appSearchIndex.search(query, 1)
        .firstOrNull()
        ?.let { appsBySearchKey[it.key] }

    private fun updateCommandSearch(rawQuery: String) {
        val query = rawQuery.trim()
        commandResults.removeAllViews()
        if (query.isEmpty()) {
            commandResults.visibility = GONE
            return
        }
        val accent = themeColor("accent", 0xFF66E0FF.toInt())
        val foreground = themeColor("foreground", Color.WHITE)
        val actions = buildList<Pair<String, () -> Unit>> {
            if ("terminal".startsWith(query, ignoreCase = true)) add("Terminal" to { onQuakeRequested?.invoke() })
            if ("plugins".startsWith(query, ignoreCase = true)) add("Plugins" to { (context as? MainActivity)?.showPluginManager() })
        }
        actions.forEach { (name, action) ->
            commandResults.addView(label("⌘  $name", 13f, accent).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    action()
                    commandInput.text.clear()
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
        }
        appSearchIndex.search(query, 6 - actions.size).forEach { result ->
            val app = appsBySearchKey[result.key] ?: return@forEach
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    AppCatalog.launch(context, app)
                    commandInput.text.clear()
                }
            }
            row.addView(ImageView(context).apply {
                setImageDrawable(runCatching {
                    context.packageManager.getActivityIcon(android.content.ComponentName(app.packageName, app.activityName))
                }.getOrNull())
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
            row.addView(label(app.label, 13f, foreground).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            commandResults.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        }
        if (commandResults.childCount == 0) {
            commandResults.addView(label("Sin resultados", 12f, themeColor("muted", 0xFF74869A.toInt())))
        }
        commandResults.background = rounded(
            alphaColor(themeColor("dark_background", 0xFF141B22.toInt()), 0xF2),
            dp(18).toFloat(),
            alphaColor(accent, 0x66),
        )
        positionCommandResults()
        commandResults.visibility = VISIBLE
    }

    private fun dismissCommandInput() {
        commandInput.clearFocus()
        isFocusableInTouchMode = true
        requestFocus()
        commandResults.visibility = GONE
        val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(windowToken, 0)
        (context as? MainActivity)?.window?.let { window ->
            WindowInsetsControllerCompat(window, this).hide(WindowInsetsCompat.Type.ime())
        }
    }

    private fun positionCommandResults() {
        val edge = settings.bottomBarPosition
        val vertical = edge == LauncherEdge.LEFT || edge == LauncherEdge.RIGHT
        commandResults.layoutParams = LayoutParams(
            if (vertical) dp(300) else MATCH_PARENT,
            WRAP_CONTENT,
            when (edge) {
                LauncherEdge.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                LauncherEdge.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                LauncherEdge.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
                LauncherEdge.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
            },
        ).apply {
            val side = dp(14)
            setMargins(
                if (edge == LauncherEdge.LEFT) dp(210) else side,
                if (edge == LauncherEdge.TOP) dp(126) else side,
                if (edge == LauncherEdge.RIGHT) dp(210) else side,
                if (edge == LauncherEdge.BOTTOM) dp(96) else side,
            )
        }
    }

    private fun applyCommandBarSettings(forceFromSetting: Boolean = false) {
        if (forceFromSetting) commandCollapsed = !settings.bottomBarVisible
        commandBar.visibility = if (commandCollapsed) GONE else VISIBLE
        commandToggle.text = if (commandCollapsed) "+" else "−"
        val vertical = settings.bottomBarPosition == LauncherEdge.LEFT || settings.bottomBarPosition == LauncherEdge.RIGHT
        commandBar.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        commandInput.layoutParams = if (vertical) {
            LinearLayout.LayoutParams(dp(150), dp(48))
        } else {
            LinearLayout.LayoutParams(0, dp(44), 1f)
        }
        commandContainer.layoutParams = LayoutParams(
            if (commandCollapsed) dp(42) else if (vertical) dp(190) else MATCH_PARENT,
            if (commandCollapsed) dp(42) else if (vertical) WRAP_CONTENT else dp(58),
            when (settings.bottomBarPosition) {
                LauncherEdge.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                LauncherEdge.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                LauncherEdge.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
                LauncherEdge.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
            },
        ).apply {
            val margin = dp(14)
            setMargins(margin, dp(64), margin, dp(28))
        }
        positionCommandResults()
    }

    private fun applyThemeChrome() {
        val accent = themeColor("accent", 0xFF66E0FF.toInt())
        val foreground = themeColor("foreground", Color.WHITE)
        val muted = themeColor("muted", 0xFF74869A.toInt())
        val surface = themeColor("lighter_background", 0xFF151D26.toInt())
        val dark = themeColor("dark_background", 0xFF0B0F14.toInt())
        title.setTextColor(foreground)
        commandBar.background = rounded(alphaColor(surface, 0xE6), dp(18).toFloat(), alphaColor(accent, 0x55))
        commandInput.setTextColor(foreground)
        commandInput.setHintTextColor(muted)
        commandToggle.setTextColor(accent)
        commandToggle.background = rounded(surface, dp(18).toFloat(), alphaColor(accent, 0x88))
        for (index in 1 until commandBar.childCount) {
            (commandBar.getChildAt(index) as? TextView)?.apply {
                setTextColor(foreground)
                background = rounded(alphaColor(surface, 0x44), dp(12).toFloat(), alphaColor(accent, 0x44))
            }
        }
        drawer.setBackgroundColor(alphaColor(dark, 0xF2))
    }

    private fun commandButton(title: String, action: () -> Unit): TextView = label(title, 11f, Color.WHITE).apply {
        background = rounded(0x44151D26, dp(12).toFloat(), 0x4466E0FF)
        setOnClickListener { action() }
    }

    private fun buildDrawer() {
        drawer.setBackgroundColor(0xF20B0F14.toInt())
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(48), dp(16), dp(20))
        }
        val search = EditText(context).apply {
            hint = "Buscar aplicaciones"
            setHintTextColor(0xFF74869A.toInt())
            setTextColor(Color.WHITE)
            setSingleLine(true)
            background = rounded(0xFF151D26.toInt(), dp(20).toFloat(), 0x4466E0FF)
            setPadding(dp(18), 0, dp(18), 0)
            addTextChangedListener(SimpleTextWatcher { query ->
                val filtered = if (query.isBlank()) {
                    apps
                } else {
                    appSearchIndex.search(query, apps.size).mapNotNull { appsBySearchKey[it.key] }
                }
                appAdapter.submit(
                    filtered,
                    favoriteKeys,
                )
            })
        }
        column.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, dp(50)))
        drawerApps.layoutManager = GridLayoutManager(context, 4)
        drawerApps.adapter = appAdapter
        val appsParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        appsParams.topMargin = dp(14)
        column.addView(drawerApps, appsParams)
        drawer.addView(column, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun showDrawer(show: Boolean) {
        drawerVisible = show
        if (show) {
            drawer.visibility = VISIBLE
            drawer.animate().alpha(1f).translationY(0f).setDuration(180).start()
        } else {
            drawer.animate().alpha(0f).translationY(height.toFloat()).setDuration(160).withEndAction {
                drawer.visibility = GONE
                drawer.translationY = 0f
            }.start()
        }
    }

    private fun showDesktopMenu() {
        if (orbitalMenu != null) return
        val activity = context as? MainActivity ?: return
        val desktopActions = mutableListOf(
            OrbitalAction("Agregar ←") { activity.addDesktop(desktopIndex, desktopIndex) },
            OrbitalAction("Agregar →") { activity.addDesktop(desktopIndex + 1, desktopIndex) },
            OrbitalAction("Ajustar") { activity.showDesktopSettings(desktopIndex) },
        ).apply {
            if (config.desktops.size > 1) add(OrbitalAction("Eliminar") { activity.deleteDesktop(desktopIndex) })
        }
        val actions = listOf(
            OrbitalAction("Escritorios") { showOrbital(desktopActions) },
            OrbitalAction("Personalizar") {
                showOrbital(
                    listOf(
                        OrbitalAction(if (widgetEditing) "Salir de edición" else "Editar widgets") { setWidgetEditing(!widgetEditing) },
                        OrbitalAction("Escritorio") { activity.showDesktopSettings(desktopIndex) },
                        OrbitalAction("Launcher") { activity.showLauncherSettings() },
                    ),
                )
            },
            OrbitalAction("Añadir") {
                showOrbital(
                    listOf(
                        OrbitalAction("Widget Android") { activity.showSystemWidgetPicker(desktopIndex) },
                        OrbitalAction("Plugin") { activity.showPluginPicker(desktopIndex) },
                    ),
                )
            },
            OrbitalAction("Plugins") { activity.showPluginManager() },
            OrbitalAction("Omarchy") {
                showOrbital(
                    listOf(
                        OrbitalAction(OmarchyMenuAction.BLUETOOTH.label) { activity.scanOmarchyBluetooth() },
                        OrbitalAction(OmarchyMenuAction.SHOW_QR.label) { activity.showOmarchyQr() },
                        OrbitalAction(OmarchyMenuAction.READ_QR.label) { activity.readOmarchyQr() },
                    ),
                )
            },
            OrbitalAction("Sistema") {
                showOrbital(
                    listOf(
                        OrbitalAction("Launcher HOME") { activity.requestDefaultLauncher() },
                        OrbitalAction("Gestos") { activity.openAccessibilitySettings() },
                        OrbitalAction("Almacenamiento") { activity.requestPublicStorageAccess() },
                    ),
                )
            },
        )
        showOrbital(actions)
    }

    private fun showOrbital(actions: List<OrbitalAction>) {
        if (orbitalMenu != null) return
        orbitalMenu = OrbitalActionMenu(
            context = context,
            actions = actions,
            accent = themeColor("accent", 0xFF66E0FF.toInt()),
            onDismissed = { orbitalMenu = null },
        ).also { addView(it, LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
    }

    private fun label(text: String, size: Float, color: Int) = TextView(context).apply {
        this.text = text
        textSize = (size * settings.textScale).toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(5), dp(8), dp(5))
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
        setStroke(dp(1), stroke)
    }

    private fun themeColor(role: String, fallback: Int): Int =
        omarchyTheme?.color(role)?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: fallback

    private fun alphaColor(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun startThemeTransition(value: LauncherSettings, target: OmarchyThemePalette?) {
        clearThemeTransition()
        val generation = ++themeTransitionGeneration
        themeTransitionTarget = target
        pendingThemeSettings = value
        animate()
            .alpha(THEME_TRANSITION_MID_ALPHA)
            .setDuration(THEME_TRANSITION_OUT_MILLIS)
            .withEndAction {
                if (generation != themeTransitionGeneration) return@withEndAction
                val pending = pendingThemeSettings ?: value
                applySettingsNow(pending, target)
                animate()
                    .alpha(1f)
                    .setDuration(THEME_TRANSITION_IN_MILLIS)
                    .withEndAction {
                        if (generation == themeTransitionGeneration) {
                            themeTransitionTarget = null
                            pendingThemeSettings = null
                        }
                    }
                    .start()
            }
            .start()
    }

    private fun clearThemeTransition() {
        themeTransitionGeneration++
        animate().cancel()
        alpha = 1f
        themeTransitionTarget = null
        pendingThemeSettings = null
    }

    private fun parseColor(value: String, fallback: Int): Int =
        runCatching { Color.parseColor(value) }.getOrDefault(fallback)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDetachedFromWindow() {
        clearThemeTransition()
        clockHandler.removeCallbacks(clockTick)
        favoritesWriter.shutdownNow()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val THEME_TRANSITION_OUT_MILLIS = 170L
        const val THEME_TRANSITION_IN_MILLIS = 330L
        const val THEME_TRANSITION_MID_ALPHA = 0.18f
    }
}

private class AppAdapter(
    private val context: Context,
    private val onClick: (InstalledApp) -> Unit,
    private val onLongClick: (InstalledApp, View) -> Boolean,
) : RecyclerView.Adapter<AppAdapter.Holder>() {
    private var apps: List<InstalledApp> = emptyList()
    private var favorites: Set<String> = emptySet()

    fun submit(value: List<InstalledApp>, favoriteKeys: List<String>) {
        apps = value
        favorites = favoriteKeys.toSet()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(4, 10, 4, 10)
        }
        val icon = ImageView(context)
        val label = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
        }
        column.addView(icon, LinearLayout.LayoutParams(58.dp(context), 58.dp(context)))
        column.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Holder(column, icon, label)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = apps[position]
        holder.label.text = app.label
        holder.itemView.isSelected = favorites.contains("${app.packageName}/${app.activityName}")
        holder.icon.setImageDrawable(
            runCatching {
                context.packageManager.getActivityIcon(android.content.ComponentName(app.packageName, app.activityName))
            }.getOrNull(),
        )
        holder.itemView.setOnClickListener { onClick(app) }
        holder.itemView.setOnLongClickListener { onLongClick(app, holder.itemView) }
    }

    override fun getItemCount(): Int = apps.size

    class Holder(view: View, val icon: ImageView, val label: TextView) : RecyclerView.ViewHolder(view)
}

private class SimpleTextWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(source: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(source: CharSequence?, start: Int, before: Int, count: Int) = changed(source?.toString().orEmpty())
    override fun afterTextChanged(editable: android.text.Editable?) = Unit
}

private fun Int.dp(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()
