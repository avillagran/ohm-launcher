package cl.villagranquiroz.ohm_launcher

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.net.nsd.NsdManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val distributionPolicy = DistributionPolicy(BuildConfig.PLAY_STORE_DISTRIBUTION)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var root: NativeLauncherView
    private lateinit var storage: ConfigStorage
    private lateinit var configRoot: File
    private lateinit var settingsStore: LauncherSettingsStore
    private lateinit var pluginRepository: PluginRepository
    private lateinit var runtimeWidgetStore: RuntimeWidgetStore
    private lateinit var notificationStore: OmarchyNotificationStore
    private lateinit var screenCapture: ScreenCaptureController
    private lateinit var quakeTerminal: TermuxQuakeTerminalView
    private lateinit var appWidgetHost: AndroidAppWidgetHostController
    private lateinit var bleScanner: OmarchyBleScanner
    private var pendingAppWidget: PendingAppWidget? = null
    private var audioPermissionRequested = false
    private var watcher: FileObserver? = null
    private val pluginWatchers = mutableListOf<FileObserver>()
    private var apiServer: LocalApiServer? = null
    private var lanAdvertiser: OmarchyLanAdvertiser? = null
    private val connectionState = OmarchyConnectionState()
    private val peerClient = OmarchyPeerClient()
    private val screenFrames = LatestScreenFrameStore()
    private val peerProbe = object : Runnable {
        override fun run() {
            val peer = connectionState.peer ?: return
            io.execute {
                when (peerClient.probeStatus(peer)) {
                    false -> runOnUiThread(::disconnectPeer)
                    true -> syncThemeFromPeer(peer)
                    null -> Unit
                }
            }
            mainHandler.postDelayed(this, PEER_PROBE_INTERVAL_MS)
        }
    }
    private val pluginReload = Runnable { reloadPlugins() }
    @Volatile private var currentConfig = LauncherConfig.parse(ConfigStorage.DEFAULT_CONFIG)
    @Volatile private var currentSettings = LauncherSettings.parse("{}")
    @Volatile private var syncedThemeCatalog: OmarchyThemeCatalog? = null
    @Volatile private var pendingThemeSelection: String? = null
    @Volatile private var syncedBackgroundCatalog: OmarchyBackgroundCatalog? = null
    private val pendingBackgroundSelection = OmarchyPendingSelection()
    private lateinit var backgroundSyncStore: OmarchyBackgroundSyncStore
    private val bundledThemeCatalog: OmarchyThemeCatalog by lazy(::loadBundledThemeCatalog)
    private val screenConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        if (::screenCapture.isInitialized) {
            screenCapture.start(result.resultCode, data) { started ->
                connectionState.setScreenSharing(started)
            }
        }
        // No second dialog here: the remote-control (accessibility) prompt
        // appears lazily on the first input attempt, so starting the share
        // takes exactly ONE confirmation.
    }
    private val appWidgetBinding = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = pendingAppWidget ?: return@registerForActivityResult
        pendingAppWidget = null
        when (val completion = appWidgetHost.completeBinding(pending.appWidgetId, result.resultCode == RESULT_OK)) {
            is AppWidgetBindingCompletion.Bound ->
                appendSystemWidget(pending.desktopIndex, pending.provider, completion.appWidgetId)
            else -> Unit
        }
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) root.refreshAudioCapture()
    }
    private val bluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) scanOmarchyBluetooth()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(LauncherSystemBarPolicy.navigationBarColor()),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        root = NativeLauncherView(this)
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (root.handleBackPressed()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        runCatching {
            EmbeddedToolsInstaller(filesDir, assets::open).install(Build.SUPPORTED_ABIS.toList())
        }.onFailure { error ->
            android.util.Log.e("OhmLauncher", "Unable to install embedded tools", error)
        }
        bleScanner = OmarchyBleScanner(this)
        appWidgetHost = AndroidAppWidgetHostController(this)
        lifecycle.addObserver(appWidgetHost)
        root.appWidgetHostController = appWidgetHost
        quakeTerminal = TermuxQuakeTerminalView(this).apply {
            visibility = View.GONE
            onClose = { showQuake(false) }
        }
        ViewCompat.setOnApplyWindowInsetsListener(quakeTerminal) { view, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(0, statusBar, 0, 0)
            val panelHeight = QuakePanelGeometry.height(
                screenHeight = resources.displayMetrics.heightPixels,
                statusBar = statusBar,
                imeHeight = imeHeight,
            )
            if (view.layoutParams?.height != panelHeight && panelHeight > 0) {
                view.layoutParams = view.layoutParams.apply { height = panelHeight }
            }
            insets
        }
        root.onQuakeRequested = { showQuake(true) }
        root.onQuakeCloseRequested = { showQuake(false) }
        root.onOmarchyBarModeChanged = ::setOmarchyBarMode
        root.onCompactNavigationRequested = ::performCompactNavigation
        root.addView(
            quakeTerminal,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * .68f).toInt(),
                Gravity.TOP,
            ),
        )
        screenCapture = ScreenCaptureController(this) { jpeg, width, height ->
            screenFrames.update(jpeg, width, height)
        }
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        storage = ConfigStorage(
            publicRoot = File(Environment.getExternalStorageDirectory(), "OhmLauncher"),
            legacyRoot = File(Environment.getExternalStorageDirectory(), "OmarchyLauncher"),
            privateRoot = (getExternalFilesDir(null) ?: filesDir).resolve("OhmLauncher"),
        )
        configRoot = storage.initialize(canUsePublicStorage())
        syncedThemeCatalog = loadSyncedThemeCatalog()
        backgroundSyncStore = OmarchyBackgroundSyncStore(configRoot)
        syncedBackgroundCatalog = backgroundSyncStore.load()
        settingsStore = LauncherSettingsStore(
            configRoot.resolve(LauncherSettingsStore.FILE_NAME),
            configRoot.resolve(ConfigStorage.CONFIG_NAME),
        )
        currentSettings = settingsStore.read().let { settings ->
            if (distributionPolicy.allowLanIntegration) settings
            else settings.copy(
                apiServerEnabled = false,
                omarchyBarMode = settings.omarchyBarMode && distributionPolicy.allowOmarchyBarMode,
                gestureNavigationEnabled = false,
            )
        }
        root.submitSettings(currentSettings)
        applyCompactSystemNavigation(currentSettings.omarchyBarMode)
        currentConfig = runCatching { storage.read(configRoot) }.getOrElse { currentConfig }
        root.submitConfig(currentConfig)
        applySystemTheme(currentSettings)
        pluginRepository = PluginRepository(configRoot)
        runtimeWidgetStore = RuntimeWidgetStore(configRoot.resolve("runtime_widgets.json"))
        notificationStore = OmarchyNotificationStore(configRoot.resolve("omarchy_notifications.json"))
        seedBuiltInPlugins()
        startApiServer()
        startWatcher()
        restorePeer()
        reloadPlugins()
        reloadRuntimeWidgets()
        root.submitNotifications(notificationStore.load())
        loadApps()
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        disableHomeChooserStub()
        loadApps()
    }

    /** The stub only exists to make the resolver forget the previous default
     *  launcher; once the user is back it must be invisible again. */
    private fun disableHomeChooserStub() {
        runCatching {
            val stub = android.content.ComponentName(this, HomeChooserStubActivity::class.java)
            if (packageManager.getComponentEnabledSetting(stub) !=
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            ) {
                packageManager.setComponentEnabledSetting(
                    stub,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP,
                )
            }
        }
    }

    override fun onDestroy() {
        watcher?.stopWatching()
        pluginWatchers.forEach(FileObserver::stopWatching)
        pluginWatchers.clear()
        mainHandler.removeCallbacks(peerProbe)
        mainHandler.removeCallbacks(pluginReload)
        if (::bleScanner.isInitialized) bleScanner.stopScan()
        if (::screenCapture.isInitialized) screenCapture.stop()
        lanAdvertiser?.stop()
        apiServer?.close()
        super.onDestroy()
    }

    fun requestPublicStorageAccess() {
        if (!distributionPolicy.allowAllFilesAccess) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_STORAGE,
            )
        }
    }

    private fun setTtfxWallpaper(): Boolean = TtfxWallpaper.enable(this)

    fun requestDefaultLauncher() {
        // MIUI ignores both the RoleManager request and the component-reset
        // resolver trick, so on Xiaomi devices open the Default apps page
        // directly: one tap on "Home" and the user picks OhmLauncher.
        val manufacturer = Build.MANUFACTURER.lowercase()
        if (manufacturer == "xiaomi" || manufacturer == "redmi" || manufacturer == "poco") {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                return
            }
        }
        // Elsewhere, briefly enabling a second HOME component forces the
        // system to forget the current default and show the resolver with an
        // "Always" option.
        runCatching {
            val stub = android.content.ComponentName(this, HomeChooserStubActivity::class.java)
            packageManager.setComponentEnabledSetting(
                stub,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                startActivity(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    fun isDefaultLauncher(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            return roles.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
    }

    fun openNotificationAccessSettings() {
        if (!distributionPolicy.allowNotificationAccess) return
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    fun openOmarchyLinkInstallationPage() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/avillagran/omarchy-link")))
        }.onFailure {
            Toast.makeText(this, R.string.browser_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    fun openAccessibilitySettings() {
        if (!distributionPolicy.allowAccessibilityControl) return
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun showRemoteControlPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.remote_control_permission)
            .setMessage(R.string.remote_control_permission_message)
            .setPositiveButton(R.string.enable) { _, _ -> openAccessibilitySettings() }
            .setNegativeButton(R.string.view_only, null)
            .show()
    }

    @Volatile private var lastRemoteControlPromptAt = 0L

    /** Lazy remote-control prompt: fired by the API when a remote input
     *  arrives but the accessibility service is off. Throttled so a swipe
     *  burst never stacks dialogs. */
    private fun onRemoteInputBlocked() {
        val now = System.currentTimeMillis()
        if (now - lastRemoteControlPromptAt < REMOTE_CONTROL_PROMPT_THROTTLE_MS) return
        lastRemoteControlPromptAt = now
        if (isDestroyed) return
        runOnUiThread {
            if (!isDestroyed && OhmGestureAccessibilityService.instance == null) {
                showRemoteControlPermissionDialog()
            }
        }
    }

    fun showOmarchyQr() {
        val uri = OhmDiscoveryConfig(apiServer?.boundPort ?: currentSettings.apiServerPort)
            .fallbackUri(preferredLanIp())
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            uri,
            com.google.zxing.BarcodeFormat.QR_CODE,
            512,
            512,
        )
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
        }
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        }
        val density = resources.displayMetrics.density
        val size = (280 * density).toInt()
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), 0)
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(bitmap)
                contentDescription = uri
            }, LinearLayout.LayoutParams(size, size))
            addView(TextView(this@MainActivity).apply {
                text = uri
                setTextIsSelectable(true)
                gravity = Gravity.CENTER
            })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.connect_omarchy)
            .setView(view)
            .setPositiveButton(R.string.copy) { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OhmLauncher", uri))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun readOmarchyQr() {
        val camera = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (camera.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.point_at_qr, Toast.LENGTH_SHORT).show()
        startActivity(camera)
    }

    fun scanOmarchyBluetooth() {
        when (bleScanner.startScan { peers ->
            runOnUiThread {
                if (peers.isEmpty()) {
                    Toast.makeText(this, R.string.no_nearby_omarchy, Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.nearby_omarchy)
                        .setItems(peers.map { "${it.name} · ${it.rssi} dBm" }.toTypedArray(), null)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }) {
            OhmBleScanStartResult.STARTED ->
                Toast.makeText(this, R.string.searching_omarchy, Toast.LENGTH_SHORT).show()
            OhmBleScanStartResult.PERMISSION_REQUIRED -> {
                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                bluetoothPermissions.launch(permissions)
            }
            OhmBleScanStartResult.BLUETOOTH_UNAVAILABLE ->
                Toast.makeText(this, R.string.bluetooth_unavailable, Toast.LENGTH_LONG).show()
            OhmBleScanStartResult.ALREADY_SCANNING -> Unit
        }
    }

    fun showWidgetMenu(desktopIndex: Int, widgetIndex: Int) {
        val widgets = currentConfig.desktops.getOrNull(desktopIndex)?.widgets ?: return
        val widget = widgets.getOrNull(widgetIndex) ?: return
        AlertDialog.Builder(this)
            .setTitle(widget.type)
            .setItems(arrayOf(
                getString(R.string.move_up),
                getString(R.string.move_down),
                getString(R.string.wider),
                getString(R.string.narrower),
                getString(R.string.delete),
            )) { _, action ->
                when (action) {
                    0 -> reorderDesktopWidget(desktopIndex, widgetIndex, (widgetIndex - 1).coerceAtLeast(0))
                    1 -> reorderDesktopWidget(desktopIndex, widgetIndex, (widgetIndex + 1).coerceAtMost(widgets.lastIndex))
                    2 -> editDesktopConfig { DesktopConfigEditor.resizeWidgetSpan(it, desktopIndex, widgetIndex, 1) }
                    3 -> editDesktopConfig { DesktopConfigEditor.resizeWidgetSpan(it, desktopIndex, widgetIndex, -1) }
                    4 -> {
                        if (widget.type == "system_widget") {
                            widget.raw.optInt("appWidgetId", -1).takeIf { it >= 0 }?.let(appWidgetHost::deleteAppWidgetId)
                        }
                        editDesktopConfig { DesktopConfigEditor.removeWidget(it, desktopIndex, widgetIndex) }
                    }
                }
            }
            .show()
    }

    fun reorderDesktopWidget(desktopIndex: Int, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        editDesktopConfig { DesktopConfigEditor.reorderWidget(it, desktopIndex, fromIndex, toIndex) }
    }

    fun moveDesktopWidget(desktopIndex: Int, widgetIndex: Int, x: Int, y: Int) {
        editDesktopConfig { DesktopConfigEditor.moveWidget(it, desktopIndex, widgetIndex, x, y) }
    }

    internal fun resizeDesktopWidget(desktopIndex: Int, widgetIndex: Int, rect: WidgetGridRect) {
        editDesktopConfig {
            DesktopConfigEditor.setWidgetGeometry(
                it,
                desktopIndex,
                widgetIndex,
                rect.x,
                rect.y,
                rect.width,
                rect.height,
            )
        }
    }

    fun addDesktop(insertIndex: Int, templateIndex: Int) {
        editDesktopConfig { DesktopConfigEditor.insertDesktop(it, insertIndex, templateIndex) }
    }

    fun addOmarchyNotifyWidget(desktopIndex: Int) {
        editDesktopConfig { DesktopConfigEditor.appendOmarchyNotifyWidget(it, desktopIndex) }
    }

    fun showAddEdgeBoxDialog() {
        val name = EditText(this).apply {
            hint = getString(R.string.box_name_hint)
            setText(getString(R.string.default_box_name, currentConfig.edgeBoxes.size + 1))
            setSingleLine(true)
            setPadding(32, 18, 32, 18)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_box_title)
            .setView(name)
            .setPositiveButton(R.string.choose_edge) { _, _ ->
                val value = name.text.toString()
                val edges = EdgePosition.entries
                AlertDialog.Builder(this)
                    .setTitle(R.string.box_position)
                    .setItems(edges.map(::edgeLabel).toTypedArray()) { _, index ->
                        editDesktopConfig { DesktopConfigEditor.appendEdgeBox(it, value, edges[index]) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun deleteDesktop(desktopIndex: Int) {
        editDesktopConfig { DesktopConfigEditor.deleteDesktop(it, desktopIndex) }
    }

    fun moveEdgeBox(id: String, edge: EdgePosition, targetIndex: Int? = null) {
        editDesktopConfig { DesktopConfigEditor.moveEdgeBox(it, id, edge, targetIndex) }
    }

    fun moveEdgeBoxItem(sourceBoxId: String, itemKey: String, targetBoxId: String, targetIndex: Int) {
        editDesktopConfig {
            DesktopConfigEditor.moveEdgeBoxItemByKey(it, sourceBoxId, itemKey, targetBoxId, targetIndex)
        }
    }

    fun confirmRemoveEdgeBoxItem(boxId: String, itemIndex: Int, label: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_application)
            .setMessage(getString(R.string.remove_application_confirm, label.ifBlank { getString(R.string.menu_app_generic) }))
            .setPositiveButton(R.string.remove) { _, _ ->
                editDesktopConfig { DesktopConfigEditor.removeEdgeBoxItem(it, boxId, itemIndex) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun confirmRemoveEdgeBox(id: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_box)
            .setMessage(getString(R.string.remove_box_confirm, name))
            .setPositiveButton(R.string.delete) { _, _ -> mutateEdgeBox(id, remove = true) { it } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun moveLauncherBar(bar: LauncherBarKind, edge: EdgePosition) {
        val launcherEdge = LauncherEdge.fromWireValue(edge.jsonName) ?: return
        val updated = LauncherBarPlacement.move(currentSettings, bar, launcherEdge)
        io.execute {
            runCatching { settingsStore.write(updated) }
                .onSuccess {
                    currentSettings = updated
                    runOnUiThread { root.submitSettings(updated) }
                }
                .onFailure { runOnUiThread { root.showConfigError(it.message.orEmpty()) } }
        }
    }

    fun setOmarchyBarMode(enabled: Boolean) {
        if (currentSettings.omarchyBarMode == enabled) return
        val updated = currentSettings.copy(omarchyBarMode = enabled)
        io.execute {
            runCatching { settingsStore.write(updated) }
                .onSuccess {
                    currentSettings = updated
                    runOnUiThread {
                        root.submitSettings(updated)
                        applyCompactSystemNavigation(enabled)
                    }
                }
                .onFailure { runOnUiThread { root.showConfigError(it.message.orEmpty()) } }
        }
    }

    private fun applyCompactSystemNavigation(compact: Boolean) {
        val compactAllowed = compact && distributionPolicy.allowCompactSystemNavigation
        val navigationBackground = systemNavigationBackground(currentSettings)
        @Suppress("DEPRECATION")
        window.navigationBarColor = navigationBackground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (compactAllowed) hide(WindowInsetsCompat.Type.navigationBars())
            else show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun performCompactNavigation(action: String) {
        if (!distributionPolicy.allowAccessibilityControl) return
        val service = OhmGestureAccessibilityService.instance
        if (service == null) {
            openAccessibilitySettings()
            return
        }
        service.remoteKey(action)
    }

    private fun editDesktopConfig(transform: (String) -> String) {
        val updatedSource = runCatching { transform(currentConfig.raw.toString()) }
            .getOrElse {
                root.showConfigError(it.message.orEmpty())
                return
            }
        val updatedConfig = runCatching { LauncherConfig.parse(updatedSource) }
            .getOrElse {
                root.showConfigError(it.message.orEmpty())
                return
            }
        currentConfig = updatedConfig
        root.submitConfig(updatedConfig, preserveFavorites = true)
        io.execute {
            runCatching { storage.write(configRoot, updatedSource) }
                .onFailure { runOnUiThread { root.showConfigError(it.message.orEmpty()) } }
        }
    }

    fun showEdgeBoxMenu(id: String) {
        root.showEdgeBoxSettingsMenu(id)
    }

    fun showEdgeBoxAppPicker(id: String) {
        val box = currentConfig.edgeBoxes.firstOrNull { it.id == id } ?: return
        io.execute {
            val installed = runCatching { AppCatalog.query(this) }.getOrDefault(emptyList())
            val current = box.items
                .filter { it.type == EdgeItemType.APP }
                .map { "${it.packageName}/${it.activity}" }
                .toSet()
            val available = installed.filterNot { "${it.packageName}/${it.activityName}" in current }
            runOnUiThread {
                if (available.isEmpty()) {
                    Toast.makeText(this, R.string.no_more_apps, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                root.showEdgeBoxAppPicker(available) { selected ->
                    editDesktopConfig { DesktopConfigEditor.appendEdgeBoxApps(it, box.id, selected) }
                }
            }
        }
    }

    fun toggleEdgeBoxCompact(id: String) = mutateEdgeBox(id) { box ->
        box.put("compact", !box.optBoolean("compact", false))
    }

    fun toggleEdgeBoxTitle(id: String) = mutateEdgeBox(id) { box ->
        box.put("showTitle", !box.optBoolean("showTitle", true))
    }

    fun toggleEdgeBoxExpandButton(id: String) = mutateEdgeBox(id) { box ->
        val visible = when {
            box.has("showExpandButton") -> box.optBoolean("showExpandButton", true)
            box.has("showToggle") -> box.optBoolean("showToggle", true)
            else -> true
        }
        box.put("showExpandButton", !visible)
    }

    private fun mutateEdgeBox(id: String, remove: Boolean = false, transform: (JSONObject) -> JSONObject) {
        io.execute {
            runCatching {
                val file = configRoot.resolve(ConfigStorage.CONFIG_NAME)
                val root = JSONObject(file.readText())
                val boxes = root.optJSONArray("edgeBoxes") ?: return@runCatching
                for (index in 0 until boxes.length()) {
                    val box = boxes.optJSONObject(index) ?: continue
                    if (box.optString("id") != id) continue
                    if (remove) boxes.remove(index) else transform(box)
                    break
                }
                storage.write(configRoot, root.toString(2))
            }.onFailure { runOnUiThread { root.showConfigError(it.message.orEmpty()) } }
        }
    }

    fun showLauncherSettings() {
        LauncherSettingsDialog.show(
            context = this,
            current = currentSettings,
            allowLanIntegration = distributionPolicy.allowLanIntegration,
            onPreview = { preview ->
                root.submitSettings(preview)
                applySystemTheme(preview)
            },
        ) { updated ->
            io.execute {
                runCatching { settingsStore.write(updated) }
                    .onSuccess {
                        currentSettings = updated
                        restartApiServer()
                        runOnUiThread {
                            root.submitSettings(updated)
                            applySystemTheme(updated)
                            if (!updated.quakeTerminal) showQuake(false)
                        }
                    }
                    .onFailure { runOnUiThread { root.showConfigError(it.message.orEmpty()) } }
            }
        }
    }

    fun showDesktopSettings(index: Int) {
        val desktop = currentConfig.desktops.getOrNull(index) ?: return
        TtfxSettingsDialog.show(
            context = this,
            current = desktop.ttfx,
            panelOpacity = currentSettings.settingsPanelOpacity,
            onPreview = { root.previewTtfx(index, it) },
        ) { settings ->
            io.execute {
                runCatching {
                    val file = configRoot.resolve(ConfigStorage.CONFIG_NAME)
                    val updated = DesktopConfigEditor.updateTtfx(file.readText(), index, settings)
                    storage.write(configRoot, updated)
                }.onFailure { error ->
                    runOnUiThread { root.showConfigError(error.message.orEmpty()) }
                }
            }
        }
    }

    fun saveDesktopTtfx(index: Int, settings: TtfxConfig) {
        editDesktopConfig { DesktopConfigEditor.updateTtfx(it, index, settings) }
        if (settings.audio) ensureAudioPermission()
    }

    fun showPluginPicker(desktopIndex: Int) {
        val plugins = pluginRepository.discover().filter { it.isValid && "bar-widget" in it.kinds }
        if (plugins.isEmpty()) {
            root.showConfigError(getString(R.string.no_plugins))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_plugin)
            .setItems(plugins.map { it.manifest?.name ?: it.id }.toTypedArray()) { _, position ->
                appendPluginWidget(plugins[position], desktopIndex)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showPluginManager() {
        val active = pluginRepository.discover()
        val disabled = pluginRepository.discoverDisabled()
        val entries = active.map { it to true } + disabled.map { it to false }
        AlertDialog.Builder(this)
            .setTitle(R.string.plugins)
            .setItems(entries.map { (plugin, enabled) ->
                val state = when {
                    !plugin.isValid -> "⚠"
                    enabled -> "✓"
                    else -> "○"
                }
                "$state ${plugin.manifest?.name ?: plugin.id}"
            }.toTypedArray()) { _, position ->
                val (plugin, enabled) = entries[position]
                showPluginActions(plugin, enabled)
            }
            .setPositiveButton(R.string.marketplace) { _, _ -> showMarketplace() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPluginActions(plugin: Plugin, enabled: Boolean) {
        if (!plugin.isValid) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.invalid_plugin, plugin.manifest?.name ?: plugin.id))
                .setMessage(plugin.validationErrors.joinToString("\n"))
                .setPositiveButton(if (enabled) R.string.disable_plugin else R.string.enable_plugin) { _, _ ->
                    if (enabled) pluginRepository.disable(plugin.id) else pluginRepository.enable(plugin.id)
                    reloadPlugins()
                }
                .setNeutralButton(R.string.delete) { _, _ ->
                    pluginRepository.delete(plugin.id)
                    reloadPlugins()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val toggle = getString(if (enabled) R.string.disable_plugin else R.string.enable_plugin)
        val actions = if (enabled) arrayOf(getString(R.string.add_to_desktop), toggle, getString(R.string.delete))
        else arrayOf(toggle, getString(R.string.delete))
        AlertDialog.Builder(this)
            .setTitle(plugin.manifest?.name ?: plugin.id)
            .setItems(actions) { _, action ->
                when {
                    enabled && action == 0 -> appendPluginWidget(plugin, root.activeDesktopIndex())
                    action == if (enabled) 1 else 0 -> {
                        if (enabled) pluginRepository.disable(plugin.id) else pluginRepository.enable(plugin.id)
                        reloadPlugins()
                        Toast.makeText(this, if (enabled) R.string.plugin_disabled else R.string.plugin_enabled, Toast.LENGTH_SHORT).show()
                    }
                    else -> AlertDialog.Builder(this)
                        .setTitle(R.string.delete_plugin)
                        .setMessage(plugin.id)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            pluginRepository.delete(plugin.id)
                            reloadPlugins()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun appendPluginWidget(plugin: Plugin, desktopIndex: Int) {
        io.execute {
            runCatching {
                check(plugin.isValid && "bar-widget" in plugin.kinds) { "El plugin no expone bar-widget" }
                val node = JSONObject()
                    .put("type", "plugin_widget")
                    .put("pluginId", plugin.id)
                    .put("kind", "bar-widget")
                    .put("x", 5)
                    .put("y", 4)
                    .put("w", 4)
                    .put("h", 1)
                val file = configRoot.resolve(ConfigStorage.CONFIG_NAME)
                storage.write(configRoot, DesktopConfigEditor.appendWidget(file.readText(), desktopIndex, node))
            }.onSuccess {
                reloadConfig()
                runOnUiThread {
                    Toast.makeText(this, R.string.plugin_added, Toast.LENGTH_SHORT).show()
                }
            }.onFailure { runOnUiThread { root.showConfigError(it.message.orEmpty()) } }
        }
    }

    private fun showMarketplace() {
        io.execute {
            val result = runCatching { MarketplaceCatalog(UrlConnectionHttpFetcher()).fetch() }
            runOnUiThread {
                result.onSuccess { entries ->
                    val installable = entries.filter { !it.isSuite && it.repoUrl.isNotBlank() }
                    AlertDialog.Builder(this)
                        .setTitle(R.string.marketplace_title)
                        .setItems(installable.map { it.name }.toTypedArray()) { _, position ->
                            installMarketplace(installable[position])
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }.onFailure {
                    Toast.makeText(this, getString(R.string.marketplace_error, it.message.orEmpty()), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installMarketplace(entry: MarketplaceEntry) {
        io.execute {
            val preparation = PluginInstallPreparer(UrlConnectionHttpFetcher()).prepare(entry.repoUrl, entry.id)
            val message = when (preparation) {
                is InstallPreparation.Success -> runCatching {
                    pluginRepository.installPrepared(preparation.plugin)
                    reloadPlugins()
                    getString(R.string.plugin_installed, entry.name)
                }.getOrElse { getString(R.string.plugin_install_failed, it.message.orEmpty()) }
                is InstallPreparation.Failure -> preparation.message
            }
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }

    fun showSystemWidgetPicker(desktopIndex: Int) {
        val providers = runCatching { appWidgetHost.queryInstalledProviders() }.getOrDefault(emptyList())
        if (providers.isEmpty()) {
            root.showConfigError(getString(R.string.no_android_widgets))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_android_widget)
            .setItems(providers.map(AppWidgetProviderDto::label).toTypedArray()) { _, position ->
                val provider = providers[position]
                val launch = appWidgetHost.requestBinding(provider.provider)
                when (val request = launch.request) {
                    is AppWidgetBindingRequest.Bound ->
                        appendSystemWidget(desktopIndex, provider, request.appWidgetId)
                    is AppWidgetBindingRequest.PermissionRequired -> {
                        pendingAppWidget = PendingAppWidget(desktopIndex, provider, request.appWidgetId)
                        appWidgetBinding.launch(checkNotNull(launch.permissionIntent))
                    }
                    is AppWidgetBindingRequest.InvalidProvider ->
                        root.showConfigError(getString(R.string.invalid_widget_provider))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun appendSystemWidget(desktopIndex: Int, provider: AppWidgetProviderDto, appWidgetId: Int) {
        io.execute {
            runCatching {
                val node = JSONObject()
                    .put("type", "system_widget")
                    .put("appWidgetId", appWidgetId)
                    .put("provider", provider.provider)
                    .put("package", provider.packageName)
                    .put("label", provider.label)
                    .put("minWidth", provider.minWidth)
                    .put("minHeight", provider.minHeight)
                    .put("x", 5)
                    .put("y", 4)
                    .put("w", 4)
                    .put("h", 2)
                val file = configRoot.resolve(ConfigStorage.CONFIG_NAME)
                storage.write(configRoot, DesktopConfigEditor.appendWidget(file.readText(), desktopIndex, node))
            }.onFailure {
                appWidgetHost.deleteAppWidgetId(appWidgetId)
                runOnUiThread { root.showConfigError(it.message.orEmpty()) }
            }
        }
    }

    fun startScreenShare(): Boolean {
        if (!connectionState.isConnected || screenCapture.isRunning()) return false
        screenFrames.clear()
        runOnUiThread { screenConsent.launch(screenCapture.createConsentIntent()) }
        return true
    }

    fun stopScreenShare() {
        screenCapture.stop()
        screenFrames.clear()
        connectionState.setScreenSharing(false)
    }

    private fun startApiServer() {
        if (!distributionPolicy.allowLanIntegration) return
        val settingsFile = configRoot.resolve("settings.json")
        if (!currentSettings.apiServerEnabled) return
        val port = currentSettings.apiServerPort.coerceIn(1, 65535)
        val binStore = BinStore(File(filesDir, "bin"))
        val shell = ShellExecutor(
            binDir = File(filesDir, "bin").absolutePath,
            homeDir = filesDir.absolutePath,
            termuxRunner = AndroidTermuxRunner(this),
        )
        val aiClient = OpenAiChatClient(
            baseUrl = currentSettings.aiBaseUrl,
            apiKey = currentSettings.aiApiKey,
            model = currentSettings.aiModel,
            systemPrompt = currentSettings.aiSystemPrompt,
        )
        val omarchy = AndroidOmarchyApiAdapter(
            context = this,
            files = OmarchyFileRepository(
                Environment.getExternalStorageDirectory(),
                configRoot.resolve("shared"),
            ),
            settingsFile = settingsFile,
            lanIp = ::preferredLanIp,
            apiPort = port,
            onScreenStart = ::startScreenShare,
            onScreenStop = ::stopScreenShare,
            onThemeGet = settingsStore::themeSnapshot,
            onThemePut = ::applyOmarchyTheme,
            onInputAccessibilityBlocked = ::onRemoteInputBlocked,
        )
        apiServer = LocalApiServer(
            port = port,
            lanMode = true,
            onCommand = CommandHandler { command, args ->
                shell.run(command, args, useTermux = currentSettings.shellPreferTermux)
            },
            onInjectWidget = WidgetHandler { source, format ->
                runtimeWidgetStore.append(source, format)
                runOnUiThread { root.submitRuntimeWidgets(runtimeWidgetStore.load()) }
            },
            onChat = aiClient.takeIf(OpenAiChatClient::configured)?.let { client ->
                ChatHandler(client::chat)
            },
            onInstallBin = InstallBinHandler(binStore::install),
            onInstallBinRaw = InstallBinRawHandler(binStore::install),
            onListBins = ListBinsHandler(binStore::list),
            onUninstallBin = UninstallBinHandler(binStore::remove),
            onQuake = QuakeHandler(::showQuake),
            onSetWallpaper = SetWallpaperHandler(::setTtfxWallpaper),
            omarchyAdapter = omarchy,
            screenFrames = screenFrames,
            notificationChannel = object : OmarchyNotificationChannel {
                override fun receive(payload: JSONObject): OmarchyNotification {
                    val notification = notificationStore.receive(payload)
                    runOnUiThread { root.submitNotifications(notificationStore.load()) }
                    return notification
                }

                override fun load(): List<OmarchyNotification> = notificationStore.load()
            },
            onThemeCatalogPut = ::persistSyncedThemeCatalog,
            onThemeSelectionGet = ::themeSelectionSnapshot,
            onThemeSelectionAck = ::acknowledgeThemeSelection,
            onBackgroundCatalogPut = ::persistSyncedBackgroundCatalog,
            onBackgroundSelectionGet = ::backgroundSelectionSnapshot,
            onBackgroundSelectionAck = ::acknowledgeBackgroundSelection,
        ).also(LocalApiServer::start)
        apiServer?.takeIf(LocalApiServer::isRunning)?.let { server ->
            val registrar = AndroidNsdRegistrar(getSystemService(NsdManager::class.java))
            lanAdvertiser = OmarchyLanAdvertiser(
                OhmDiscoveryConfig(server.boundPort),
                registrar,
            ).also(OmarchyLanAdvertiser::start)
        }
    }

    private fun restartApiServer() {
        lanAdvertiser?.stop()
        lanAdvertiser = null
        apiServer?.close()
        apiServer = null
        startApiServer()
    }

    private fun applyOmarchyTheme(payload: JSONObject) {
        val normalized = withDesktopTextColor(payload)
        val updated = settingsStore.updateOmarchyTheme(normalized)
        currentSettings = updated
        runOnUiThread {
            root.submitSettings(updated)
            applySystemTheme(updated)
        }
        val palette = OmarchyThemePalette.parse(normalized)
        val peer = updated.omarchyPeer
        palette.background?.let { background ->
            val pushed = resolvePushedBackground(background)
            when {
                pushed != null -> io.execute { applySyncedBackground(pushed, palette.desktopBackground) }
                peer != null -> io.execute { syncBackgroundFromPeer(peer, background, palette.desktopBackground) }
            }
        }
    }

    private fun withDesktopTextColor(payload: JSONObject): JSONObject {
        val normalized = JSONObject(payload.toString())
        val palette = OmarchyThemePalette.parse(normalized)
        val desktopText = OmarchyDesktopTextPolicy.preferredColor(palette.colors)
        if (desktopText != null) normalized.getJSONObject("colors").put("desktop_text", desktopText)
        return normalized
    }

    private fun sampleBackground(file: File): IntArray = runCatching {
        val extension = file.extension.lowercase()
        val bitmap = if (extension in setOf("mp4", "webm", "mkv", "m4v", "mov", "avi")) {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(file.absolutePath)
                    frameAtTime
                } finally {
                    release()
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching IntArray(0)
        try {
            val step = maxOf(1, kotlin.math.sqrt((bitmap.width.toLong() * bitmap.height / 4096.0)).toInt())
            buildList {
                for (y in 0 until bitmap.height step step) {
                    for (x in 0 until bitmap.width step step) add(bitmap.getPixel(x, y))
                }
            }.toIntArray()
        } finally {
            bitmap.recycle()
        }
    }.getOrDefault(IntArray(0))

    private fun applySystemTheme(settings: LauncherSettings) {
        val palette = OmarchyThemePalette.fromSettings(settings.raw)
        val darkIcons = palette?.useDarkSystemIcons == true
        val navigationBackground = systemNavigationBackground(settings)
        enableEdgeToEdge(
            statusBarStyle = if (darkIcons) {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            } else {
                SystemBarStyle.dark(Color.TRANSPARENT)
            },
            navigationBarStyle = if (darkIcons) {
                SystemBarStyle.light(navigationBackground, navigationBackground)
            } else {
                SystemBarStyle.dark(navigationBackground)
            },
        )
        @Suppress("DEPRECATION")
        window.navigationBarColor = navigationBackground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun systemNavigationBackground(settings: LauncherSettings): Int {
        val palette = OmarchyThemePalette.fromSettings(settings.raw)
        return runCatching {
            Color.parseColor(palette?.color("dark_background") ?: "#1A1B26")
        }.getOrDefault(LauncherSystemBarPolicy.navigationBarColor())
    }

    private fun showQuake(open: Boolean) {
        if (open && !currentSettings.quakeTerminal) return
        runOnUiThread {
            if (open) {
                root.setQuakeVisible(true)
                quakeTerminal.animate().cancel()
                val panelHeight = quakeTerminal.height.takeIf { it > 0 }
                    ?: (resources.displayMetrics.heightPixels * .68f).toInt()
                quakeTerminal.translationY = -panelHeight.toFloat()
                quakeTerminal.alpha = 0f
                quakeTerminal.visibility = View.VISIBLE
                quakeTerminal.startSession()
                quakeTerminal.focusInputAndShowKeyboard()
                quakeTerminal.bringToFront()
                quakeTerminal.post {
                    quakeTerminal.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(190)
                        .start()
                }
            } else if (quakeTerminal.visibility == View.VISIBLE) {
                quakeTerminal.animate().cancel()
                quakeTerminal.hideKeyboard()
                WindowInsetsControllerCompat(window, root).hide(WindowInsetsCompat.Type.ime())
                quakeTerminal.animate()
                    .translationY(-quakeTerminal.height.toFloat())
                    .alpha(0f)
                    .setDuration(170)
                    .withEndAction {
                        quakeTerminal.visibility = View.GONE
                        quakeTerminal.translationY = 0f
                        quakeTerminal.alpha = 1f
                        root.setQuakeVisible(false)
                    }
                    .start()
            } else {
                root.setQuakeVisible(false)
            }
        }
    }

    private fun canUsePublicStorage(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        else -> true
    }

    private fun edgeLabel(edge: EdgePosition): String = getString(
        when (edge) {
            EdgePosition.TOP -> R.string.edge_top
            EdgePosition.BOTTOM -> R.string.edge_bottom
            EdgePosition.LEFT -> R.string.edge_left
            EdgePosition.RIGHT -> R.string.edge_right
        },
    )

    private fun reloadConfig() {
        if (isDestroyed) return
        io.execute {
            runCatching { storage.read(configRoot) }
                .onSuccess { config ->
                    currentConfig = config
                    runOnUiThread {
                        if (!isDestroyed) root.submitConfig(config)
                    }
                }
                .onFailure { error -> runOnUiThread { if (!isDestroyed) root.showConfigError(error.message.orEmpty()) } }
        }
    }

    private fun loadApps() {
        io.execute {
            val apps = runCatching { AppCatalog.query(this) }.getOrDefault(emptyList())
            runOnUiThread { root.submitApps(apps) }
        }
    }

    private fun ensureAudioPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            root.refreshAudioCapture()
            return
        }
        if (!audioPermissionRequested) {
            audioPermissionRequested = true
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun reloadPlugins() {
        if (isDestroyed) return
        io.execute {
            pluginRepository.pluginsDirectory.mkdirs()
            val plugins = pluginRepository.discover()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                root.submitPlugins(plugins)
                watchPlugins(plugins)
            }
        }
    }

    private fun seedBuiltInPlugins() {
        BUILT_IN_PLUGINS.forEach { (id, files) ->
            val destination = pluginRepository.pluginsDirectory.resolve(id)
            if (destination.exists()) return@forEach
            val temporary = pluginRepository.pluginsDirectory.resolve(".$id.tmp")
            runCatching {
                temporary.deleteRecursively()
                temporary.mkdirs()
                files.forEach { name ->
                    assets.open("plugins/$id/$name").use { input ->
                        temporary.resolve(name).outputStream().use(input::copyTo)
                    }
                }
                pluginRepository.pluginsDirectory.mkdirs()
                check(
                    temporary.renameTo(destination) ||
                        temporary.copyRecursively(destination).also { temporary.deleteRecursively() },
                )
            }.onFailure { temporary.deleteRecursively() }
        }
    }

    private fun reloadRuntimeWidgets() {
        if (isDestroyed) return
        io.execute {
            val widgets = runtimeWidgetStore.load()
            runOnUiThread { if (!isDestroyed) root.submitRuntimeWidgets(widgets) }
        }
    }

    private fun reloadSettings() {
        if (isDestroyed) return
        io.execute {
            runCatching { settingsStore.read() }.onSuccess { settings ->
                currentSettings = settings
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    root.submitSettings(settings)
                    applySystemTheme(settings)
                }
            }
        }
    }

    private fun watchPlugins(plugins: List<Plugin>) {
        pluginWatchers.forEach(FileObserver::stopWatching)
        pluginWatchers.clear()
        val directories = (listOf(pluginRepository.pluginsDirectory) + plugins.map(Plugin::folder)).distinct()
        directories.filter(File::isDirectory).forEach { directory ->
            pluginWatchers += object : FileObserver(directory, CLOSE_WRITE or MOVED_TO or MOVED_FROM or CREATE or DELETE) {
                override fun onEvent(event: Int, path: String?) {
                    mainHandler.removeCallbacks(pluginReload)
                    mainHandler.postDelayed(pluginReload, PLUGIN_RELOAD_DEBOUNCE_MS)
                }
            }.also(FileObserver::startWatching)
        }
    }

    private fun startWatcher() {
        watcher?.stopWatching()
        watcher = object : FileObserver(configRoot, CLOSE_WRITE or MOVED_TO or CREATE) {
            override fun onEvent(event: Int, path: String?) {
                when (path) {
                    ConfigStorage.CONFIG_NAME,
                    ConfigStorage.FAVORITES_NAME -> reloadConfig()
                    "runtime_widgets.json" -> reloadRuntimeWidgets()
                    LauncherSettingsStore.FILE_NAME -> reloadSettings()
                }
            }
        }.also { it.startWatching() }
    }

    private fun handleDeepLink(intent: Intent?) {
        if (!distributionPolicy.allowLanIntegration) return
        val uri = intent?.data ?: return
        if (uri.scheme != "omarchy") return
        val peer = OmarchyPeerUri.parse(uri.toString())
        if (peer == null) {
            root.showConfigError(getString(R.string.invalid_omarchy_link))
            return
        }
        connectPeer(peer, persist = true)
        root.showPeerUri(uri)
    }

    private fun restorePeer() {
        if (!distributionPolicy.allowLanIntegration) return
        val peer = currentSettings.omarchyPeer ?: return
        connectPeer(peer, persist = false)
    }

    private fun connectPeer(peer: OmarchyPeer, persist: Boolean) {
        if (!distributionPolicy.allowLanIntegration) return
        connectionState.connect(peer, replace = true)
        ContextCompat.startForegroundService(
            this,
            Intent(this, ClipboardMonitorService::class.java)
                .putExtra("peerIp", peer.host)
                .putExtra("peerPort", peer.port),
        )
        mainHandler.removeCallbacks(peerProbe)
        mainHandler.postDelayed(peerProbe, PEER_PROBE_INTERVAL_MS)
        io.execute {
            if (persist) persistPeer(peer)
            peerClient.notify(
                peer,
                preferredLanIp(),
                apiServer?.boundPort ?: API_PORT,
                Build.MODEL.ifBlank { "OhmLauncher" },
            )
            syncThemeFromPeer(peer)
        }
    }

    private fun syncThemeFromPeer(peer: OmarchyPeer) {
        val palette = peerClient.fetchTheme(peer) ?: return
        if (palette != OmarchyThemePalette.fromSettings(currentSettings.raw)) {
            applyOmarchyTheme(palette.toJson())
        } else {
            palette.background?.let { syncBackgroundFromPeer(peer, it, palette.desktopBackground) }
        }
    }

    fun syncStyleFromOmarchy() {
        val peer = currentSettings.omarchyPeer ?: run {
            root.showConfigError(getString(R.string.menu_style_not_connected))
            return
        }
        io.execute { syncThemeFromPeer(peer) }
    }

    fun showOmarchyThemeSelector() {
        val bundled = bundledThemeCatalog
        val synced = syncedThemeCatalog
        val themes = linkedMapOf<String, OmarchyThemeChoice>()
        bundled.themes.forEach { themes[it.id] = it }
        synced?.themes?.forEach { syncedChoice ->
            themes[syncedChoice.id] = syncedChoice.copy(
                backgroundPreviewPath = syncedChoice.backgroundPreviewPath
                    ?: themes[syncedChoice.id]?.backgroundPreviewPath,
            )
        }
        if (themes.isEmpty()) {
            root.showConfigError(getString(R.string.theme_selector_unavailable))
            return
        }
        val current = OmarchyThemePalette.fromSettings(currentSettings.raw)?.name
            ?: synced?.current
            ?: bundled.current
        val catalog = OmarchyThemeCatalog(current, themes.values.toList())
        root.showOmarchyThemeSelector(
            catalog = catalog,
            previewLoader = ::loadSyncedThemePreview,
            onApply = ::applyLocalThemeSelection,
        )
    }

    fun showOmarchyBackgroundSelector() {
        syncedBackgroundCatalog?.takeIf { it.backgrounds.isNotEmpty() }?.let { catalog ->
            showBackgroundCatalog(catalog, backgroundSyncStore::loadPreview)
            return
        }
        val peer = currentSettings.omarchyPeer ?: run {
            root.showConfigError(getString(R.string.menu_style_not_connected))
            return
        }
        io.execute {
            val catalog = peerClient.fetchBackgrounds(peer)
            if (catalog == null || catalog.backgrounds.isEmpty()) {
                runOnUiThread {
                    if (!isDestroyed) root.showConfigError(getString(R.string.background_selector_unavailable))
                }
                return@execute
            }
            val backgroundsById = catalog.backgrounds.associateBy { it.id }
            val localPreviewPaths = java.util.concurrent.ConcurrentHashMap<String, String>()
            val pickerCatalog = OmarchyThemeCatalog(
                current = catalog.current.id,
                themes = catalog.backgrounds.map { background ->
                    OmarchyThemeChoice(background.id, background.label, background.preview)
                },
            )
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                root.showOmarchyThemeSelector(
                    catalog = pickerCatalog,
                    previewLoader = { choice ->
                        backgroundsById[choice.id]
                            ?.takeIf { it.hasPreview }
                            ?.let { background ->
                                peerClient.fetchBackgroundPreview(peer, background)?.also { bytes ->
                                    val directory = cacheDir.resolve("omarchy-background-previews")
                                    if (directory.mkdirs() || directory.isDirectory) {
                                        val file = directory.resolve("${background.id.hashCode().toUInt().toString(16)}.preview")
                                        file.writeBytes(bytes)
                                        localPreviewPaths[background.id] = file.absolutePath
                                    }
                                }
                            }
                    },
                    onApply = { choice ->
                        val background = backgroundsById.getValue(choice.id)
                        applyLocalBackgroundSelection(
                            background.copy(previewPath = localPreviewPaths[choice.id] ?: background.previewPath),
                        )
                    },
                )
            }
        }
    }

    private fun showBackgroundCatalog(
        catalog: OmarchyBackgroundCatalog,
        previewLoader: (OmarchyBackgroundChoice) -> ByteArray?,
    ) {
        val backgroundsById = catalog.backgrounds.associateBy { it.id }
        root.showOmarchyThemeSelector(
            catalog = OmarchyThemeCatalog(
                current = catalog.current.id,
                themes = catalog.backgrounds.map { background ->
                    OmarchyThemeChoice(background.id, background.label, background.previewPath.orEmpty())
                },
            ),
            previewLoader = { choice -> backgroundsById[choice.id]?.let(previewLoader) },
            onApply = { choice -> applyLocalBackgroundSelection(backgroundsById.getValue(choice.id)) },
        )
    }

    private fun applyLocalThemeSelection(choice: OmarchyThemeChoice) {
        val normalized = choice.palette?.let { withDesktopTextColor(it.toJson()) }
        android.util.Log.e("OHM-DEBUG-theme", "selected id=${choice.id} palette=${choice.palette?.name} local=${normalized != null}")
        val peer = currentSettings.omarchyPeer
        OmarchyLocalStyleSelection.apply(
            id = choice.id,
            applyLocal = {
                normalized?.let { payload ->
                    android.util.Log.e("OHM-DEBUG-theme", "submitting local palette=${OmarchyThemePalette.parse(payload).name}")
                    val document = JSONObject(currentSettings.raw.toString())
                        .put("omarchyTheme", OmarchyThemePalette.parse(payload).toJson())
                    val preview = LauncherSettings.parse(document)
                    currentSettings = preview
                    root.submitSettings(preview, animateTheme = true)
                    applySystemTheme(preview)
                }
                val bundledBackgroundPath = choice.backgroundPreviewPath?.let(::materializeBundledBackground)
                val instantBackground = OmarchyThemeBackgroundSelection
                    .resolve(syncedBackgroundCatalog?.backgrounds.orEmpty(), choice.backgroundId)
                android.util.Log.e("OHM-DEBUG-theme", "background choice=${choice.backgroundId} catalog=${syncedBackgroundCatalog?.backgrounds?.size} resolved=${instantBackground?.id}")
                (bundledBackgroundPath ?: instantBackground?.previewPath)
                    ?.let { path ->
                        val backgroundPreview = OmarchyLocalBackgroundPreview.apply(currentConfig, path)
                        currentConfig = backgroundPreview
                        root.submitConfig(backgroundPreview, preserveFavorites = true)
                        if (instantBackground != null) {
                            syncedBackgroundCatalog = syncedBackgroundCatalog?.withCurrent(instantBackground)
                        }
                    }
            },
            publishSelection = { id -> if (peer != null) pendingThemeSelection = id },
            scheduleRemote = { id ->
                io.execute {
                    normalized?.let(::applyOmarchyTheme)
                    if (peer != null) {
                        peerClient.selectTheme(peer, id)?.let { selected ->
                            acknowledgeThemeSelection(JSONObject().put("id", id))
                            applyOmarchyTheme(selected.toJson())
                        }
                    }
                }
            },
        )
    }

    private fun applyLocalBackgroundSelection(choice: OmarchyBackgroundChoice) {
        val peer = currentSettings.omarchyPeer
        OmarchyLocalStyleSelection.apply(
            id = choice.id,
            applyLocal = {
                choice.previewPath?.let { path ->
                    val preview = OmarchyLocalBackgroundPreview.apply(currentConfig, path)
                    currentConfig = preview
                    root.submitConfig(preview, preserveFavorites = true)
                }
                syncedBackgroundCatalog = syncedBackgroundCatalog?.withCurrent(choice)
            },
            publishSelection = pendingBackgroundSelection::select,
            scheduleRemote = { id ->
                if (peer != null) {
                    io.execute {
                        if (peerClient.selectBackground(peer, id)) {
                            acknowledgeBackgroundSelection(JSONObject().put("id", id))
                            syncThemeFromPeer(peer)
                        }
                    }
                }
            },
        )
    }

    private fun persistSyncedBackgroundCatalog(payload: JSONObject) {
        backgroundSyncStore.persist(payload)?.let { syncedBackgroundCatalog = it }
    }

    private fun backgroundSelectionSnapshot(): JSONObject = pendingBackgroundSelection.snapshot()

    private fun acknowledgeBackgroundSelection(payload: JSONObject) {
        pendingBackgroundSelection.acknowledge(payload)
    }

    private fun parseSyncedThemeCatalog(payload: JSONObject): OmarchyThemeCatalog? = runCatching {
        val current = payload.optString("current")
        val array = payload.getJSONArray("themes")
        check(array.length() in 1..256)
        val shared = configRoot.resolve("shared").canonicalFile
        val themes = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.getString("id")
                check(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$").matches(id))
                val rawPreview = item.optString("previewPath")
                val preview = rawPreview.takeIf(String::isNotBlank)?.let(::File)?.canonicalFile
                if (preview != null) check(preview.path.startsWith(shared.path + File.separator) && preview.isFile)
                add(
                    OmarchyThemeChoice(
                        id,
                        item.optString("label", id),
                        preview?.absolutePath.orEmpty(),
                        item.optJSONObject("palette")?.let(OmarchyThemePalette::parse),
                        item.optString("backgroundId").takeIf { it.isNotBlank() }
                            ?.also { check(Regex("^[0-9a-f]{64}$").matches(it)) },
                    ),
                )
            }
        }
        check(themes.distinctBy { it.id }.size == themes.size)
        OmarchyThemeCatalog(current, themes)
    }.getOrNull()

    private fun persistSyncedThemeCatalog(payload: JSONObject) {
        val parsed = parseSyncedThemeCatalog(payload) ?: return
        val file = configRoot.resolve("omarchy_theme_catalog.json")
        val temporary = configRoot.resolve(".omarchy_theme_catalog.tmp")
        temporary.writeText(payload.toString(2))
        check(temporary.renameTo(file) || temporary.copyTo(file, overwrite = true).let { temporary.delete() })
        syncedThemeCatalog = parsed
    }

    private fun loadSyncedThemeCatalog(): OmarchyThemeCatalog? = runCatching {
        val file = configRoot.resolve("omarchy_theme_catalog.json")
        if (!file.isFile) null else parseSyncedThemeCatalog(JSONObject(file.readText()))
    }.getOrNull()

    private fun loadBundledThemeCatalog(): OmarchyThemeCatalog = runCatching {
        val root = JSONObject(assets.open("omarchy/themes/catalog.json").bufferedReader().use { it.readText() })
        val array = root.getJSONArray("themes")
        val themes = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    OmarchyThemeChoice(
                        id = item.getString("id"),
                        label = item.getString("label"),
                        previewPath = "asset://${item.getString("previewAsset")}",
                        palette = OmarchyThemePalette.parse(item.getJSONObject("palette")),
                        backgroundPreviewPath = "asset://${item.getString("backgroundAsset")}",
                    ),
                )
            }
        }
        OmarchyThemeCatalog(root.optString("current", "matte-black"), themes)
    }.getOrElse { OmarchyThemeCatalog("", emptyList()) }

    private fun loadSyncedThemePreview(choice: OmarchyThemeChoice): ByteArray? = runCatching {
        if (choice.previewPath.startsWith("asset://")) {
            return@runCatching assets.open(choice.previewPath.removePrefix("asset://")).use { it.readBytes() }
        }
        val shared = configRoot.resolve("shared").canonicalFile
        val file = File(choice.previewPath).canonicalFile
        check(file.isFile && file.path.startsWith(shared.path + File.separator))
        check(file.length() in 1..(16L * 1024L * 1024L))
        file.readBytes()
    }.getOrNull()

    private fun materializeBundledBackground(path: String): String? = runCatching {
        if (!path.startsWith("asset://")) return@runCatching path
        val assetPath = path.removePrefix("asset://")
        check(assetPath.startsWith("omarchy/backgrounds/"))
        val extension = assetPath.substringAfterLast('.', "jpg")
        val themeId = assetPath.substringAfterLast('/').substringBeforeLast('.')
        check(Regex("^[a-z0-9-]+$").matches(themeId))
        val directory = filesDir.resolve("omarchy-style/bundled").apply { mkdirs() }
        val target = directory.resolve("$themeId.$extension")
        assets.open(assetPath).use { input -> target.outputStream().use(input::copyTo) }
        target.absolutePath
    }.getOrNull()

    private fun themeSelectionSnapshot(): JSONObject {
        val id = pendingThemeSelection
        return if (id == null) JSONObject().put("pending", false)
        else JSONObject().put("pending", true).put("id", id)
    }

    private fun acknowledgeThemeSelection(payload: JSONObject) {
        val id = payload.optString("id")
        if (id.isNotEmpty() && pendingThemeSelection == id) pendingThemeSelection = null
    }

    private fun syncBackgroundFromPeer(
        peer: OmarchyPeer,
        background: OmarchyThemeBackground,
        desktopBackground: OmarchyDesktopBackground?,
    ) {
        val directory = filesDir.resolve("omarchy-style")
        val file = peerClient.fetchThemeBackground(peer, background, directory) ?: return
        applySyncedBackground(file, desktopBackground)
    }

    private fun resolvePushedBackground(background: OmarchyThemeBackground): File? = runCatching {
        val shared = configRoot.resolve("shared").canonicalFile
        val file = File(background.phonePath ?: return null).canonicalFile
        if (!file.isFile || !file.path.startsWith(shared.path + File.separator)) return null
        file.takeIf { sha256(it) == background.sha256 }
    }.getOrNull()

    private fun applySyncedBackground(file: File, desktopBackground: OmarchyDesktopBackground?) {
        val directory = filesDir.resolve("omarchy-style")
        runCatching {
            val configFile = configRoot.resolve(ConfigStorage.CONFIG_NAME)
            val document = JSONObject(configFile.readText())
            val desktops = document.optJSONArray("desktops") ?: return@runCatching
            for (index in 0 until desktops.length()) {
                desktops.optJSONObject(index)?.let { desktop ->
                    OmarchyDesktopBackgroundApplier.apply(desktop, desktopBackground, file.absolutePath)
                }
            }
            storage.write(configRoot, document.toString(2))
            val updatedConfig = storage.read(configRoot)
            currentConfig = updatedConfig
            runOnUiThread { if (!isDestroyed) root.submitConfig(updatedConfig, preserveFavorites = true) }
            directory.listFiles()
                ?.filter { it.isFile && it != file }
                ?.forEach(File::delete)
        }.onFailure { error ->
            runOnUiThread { root.showConfigError(error.message.orEmpty()) }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun disconnectPeer() {
        mainHandler.removeCallbacks(peerProbe)
        stopScreenShare()
        stopService(Intent(this, ClipboardMonitorService::class.java))
        connectionState.disconnect()
        io.execute { persistPeer(null) }
    }

    private fun persistPeer(peer: OmarchyPeer?) {
        runCatching { settingsStore.updatePeer(peer) }
            .onSuccess { currentSettings = it }
    }

    private fun preferredLanIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress
            ?: "127.0.0.1"
    }.getOrDefault("127.0.0.1")

    private fun <T> java.util.Enumeration<T>.toList(): List<T> = buildList {
        while (this@toList.hasMoreElements()) add(this@toList.nextElement())
    }

    private data class PendingAppWidget(
        val desktopIndex: Int,
        val provider: AppWidgetProviderDto,
        val appWidgetId: Int,
    )

    companion object {
        /** Executor de proceso: la activity puede destruirse y recrearse mientras
         *  el proceso sigue vivo (servicios); nunca se apaga en onDestroy. */
        private val io = Executors.newSingleThreadExecutor()
        private const val REQUEST_STORAGE = 4001
        private const val API_PORT = 8753
        private const val PEER_PROBE_INTERVAL_MS = 15_000L
        private const val REMOTE_CONTROL_PROMPT_THROTTLE_MS = 10_000L
        private const val PLUGIN_RELOAD_DEBOUNCE_MS = 400L
        private val BUILT_IN_PLUGINS = mapOf(
            "io.github.ohm.demo.clock" to listOf("manifest.json", "BarWidget.json", "Panel.json"),
            "io.github.ohm.demo.weather" to listOf("manifest.json", "BarWidget.qml"),
        )
    }
}
