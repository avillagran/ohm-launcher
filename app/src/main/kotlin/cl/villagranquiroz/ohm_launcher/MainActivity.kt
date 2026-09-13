package cl.villagranquiroz.ohm_launcher

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var root: NativeLauncherView
    private lateinit var storage: ConfigStorage
    private lateinit var configRoot: File
    private lateinit var settingsStore: LauncherSettingsStore
    private lateinit var pluginRepository: PluginRepository
    private lateinit var runtimeWidgetStore: RuntimeWidgetStore
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
    private val screenConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        val started = ::screenCapture.isInitialized && screenCapture.start(result.resultCode, data)
        if (started) {
            connectionState.setScreenSharing(true)
        }
        if (ScreenSharePermissionPolicy.shouldPrompt(started, OhmGestureAccessibilityService.instance != null)) {
            showRemoteControlPermissionDialog()
        }
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        root = NativeLauncherView(this)
        setContentView(root)
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
        settingsStore = LauncherSettingsStore(
            configRoot.resolve(LauncherSettingsStore.FILE_NAME),
            configRoot.resolve(ConfigStorage.CONFIG_NAME),
        )
        currentSettings = settingsStore.read()
        root.submitSettings(currentSettings)
        applySystemTheme(currentSettings)
        pluginRepository = PluginRepository(configRoot)
        runtimeWidgetStore = RuntimeWidgetStore(configRoot.resolve("runtime_widgets.json"))
        seedBuiltInPlugins()
        startApiServer()
        restorePeer()
        reloadConfig()
        startWatcher()
        reloadPlugins()
        reloadRuntimeWidgets()
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
        loadApps()
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
        io.shutdownNow()
        super.onDestroy()
    }

    fun requestPublicStorageAccess() {
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

    fun requestDefaultLauncher() {
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

    fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun showRemoteControlPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permiso de control remoto")
            .setMessage(
                "La pantalla ya se está compartiendo. Para usar clics, gestos y botones desde Omarchy, " +
                    "activa el servicio de accesibilidad de Ohm Launcher.",
            )
            .setPositiveButton("Activar") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Solo visualizar", null)
            .show()
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
            .setTitle("Conectar Omarchy")
            .setView(view)
            .setPositiveButton("Copiar") { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OhmLauncher", uri))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun readOmarchyQr() {
        val camera = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (camera.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "No hay una cámara disponible", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Apunta al QR de Omarchy", Toast.LENGTH_SHORT).show()
        startActivity(camera)
    }

    fun scanOmarchyBluetooth() {
        when (bleScanner.startScan { peers ->
            runOnUiThread {
                if (peers.isEmpty()) {
                    Toast.makeText(this, "No se detectaron equipos Omarchy", Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Equipos Omarchy cercanos")
                        .setItems(peers.map { "${it.name} · ${it.rssi} dBm" }.toTypedArray(), null)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }) {
            OhmBleScanStartResult.STARTED ->
                Toast.makeText(this, "Buscando equipos Omarchy…", Toast.LENGTH_SHORT).show()
            OhmBleScanStartResult.PERMISSION_REQUIRED -> {
                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                bluetoothPermissions.launch(permissions)
            }
            OhmBleScanStartResult.BLUETOOTH_UNAVAILABLE ->
                Toast.makeText(this, "Bluetooth no disponible", Toast.LENGTH_LONG).show()
            OhmBleScanStartResult.ALREADY_SCANNING -> Unit
        }
    }

    fun showWidgetMenu(desktopIndex: Int, widgetIndex: Int) {
        val widgets = currentConfig.desktops.getOrNull(desktopIndex)?.widgets ?: return
        val widget = widgets.getOrNull(widgetIndex) ?: return
        AlertDialog.Builder(this)
            .setTitle(widget.type)
            .setItems(arrayOf("Mover arriba", "Mover abajo", "Más ancho", "Más estrecho", "Eliminar")) { _, action ->
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

    fun deleteDesktop(desktopIndex: Int) {
        editDesktopConfig { DesktopConfigEditor.deleteDesktop(it, desktopIndex) }
    }

    fun moveEdgeBox(id: String, edge: EdgePosition) {
        editDesktopConfig { DesktopConfigEditor.moveEdgeBox(it, id, edge) }
    }

    private fun editDesktopConfig(transform: (String) -> String) {
        io.execute {
            runCatching {
                val file = configRoot.resolve(ConfigStorage.CONFIG_NAME)
                storage.write(configRoot, transform(file.readText()))
            }.onFailure { runOnUiThread { root.showConfigError(it.message.orEmpty()) } }
        }
    }

    fun showEdgeBoxMenu(id: String) {
        val box = currentConfig.edgeBoxes.firstOrNull { it.id == id } ?: return
        val edges = EdgePosition.entries
        val actions = edges.map { "Mover a ${it.jsonName}" } + listOf(
            if (box.compact) "Expandir" else "Compactar",
            if (box.showTitle) "Ocultar título" else "Mostrar título",
            if (box.showExpandButton) "Ocultar botón de expansión" else "Mostrar botón de expansión",
            "Eliminar caja",
        )
        AlertDialog.Builder(this)
            .setTitle(box.name)
            .setItems(actions.toTypedArray()) { _, action ->
                when {
                    action < edges.size -> moveEdgeBox(id, edges[action])
                    action == edges.size -> mutateEdgeBox(id) { it.put("compact", !box.compact) }
                    action == edges.size + 1 -> mutateEdgeBox(id) { it.put("showTitle", !box.showTitle) }
                    action == edges.size + 2 -> mutateEdgeBox(id) { it.put("showExpandButton", !box.showExpandButton) }
                    else -> mutateEdgeBox(id, remove = true) { it }
                }
            }
            .show()
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
        LauncherSettingsDialog.show(this, currentSettings) { updated ->
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
    }

    fun showPluginPicker(desktopIndex: Int) {
        val plugins = pluginRepository.discover().filter { it.isValid && "bar-widget" in it.kinds }
        if (plugins.isEmpty()) {
            root.showConfigError("No hay plugins instalados")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Agregar plugin")
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
            .setTitle("Plugins")
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
            .setPositiveButton("Marketplace") { _, _ -> showMarketplace() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPluginActions(plugin: Plugin, enabled: Boolean) {
        if (!plugin.isValid) {
            AlertDialog.Builder(this)
                .setTitle("Plugin inválido · ${plugin.manifest?.name ?: plugin.id}")
                .setMessage(plugin.validationErrors.joinToString("\n"))
                .setPositiveButton(if (enabled) "Deshabilitar" else "Habilitar") { _, _ ->
                    if (enabled) pluginRepository.disable(plugin.id) else pluginRepository.enable(plugin.id)
                    reloadPlugins()
                }
                .setNeutralButton("Eliminar") { _, _ ->
                    pluginRepository.delete(plugin.id)
                    reloadPlugins()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val toggle = if (enabled) "Deshabilitar" else "Habilitar"
        val actions = if (enabled) arrayOf("Agregar al escritorio", toggle, "Eliminar") else arrayOf(toggle, "Eliminar")
        AlertDialog.Builder(this)
            .setTitle(plugin.manifest?.name ?: plugin.id)
            .setItems(actions) { _, action ->
                when {
                    enabled && action == 0 -> appendPluginWidget(plugin, root.activeDesktopIndex())
                    action == if (enabled) 1 else 0 -> {
                        if (enabled) pluginRepository.disable(plugin.id) else pluginRepository.enable(plugin.id)
                        reloadPlugins()
                        Toast.makeText(this, if (enabled) "Plugin deshabilitado" else "Plugin habilitado", Toast.LENGTH_SHORT).show()
                    }
                    else -> AlertDialog.Builder(this)
                        .setTitle("Eliminar plugin")
                        .setMessage(plugin.id)
                        .setPositiveButton("Eliminar") { _, _ ->
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
                    Toast.makeText(this, "Plugin agregado al escritorio", Toast.LENGTH_SHORT).show()
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
                        .setTitle("Marketplace Omarchy")
                        .setItems(installable.map { it.name }.toTypedArray()) { _, position ->
                            installMarketplace(installable[position])
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }.onFailure {
                    Toast.makeText(this, "Marketplace: ${it.message}", Toast.LENGTH_LONG).show()
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
                    "Plugin instalado: ${entry.name}"
                }.getOrElse { "No se pudo instalar: ${it.message}" }
                is InstallPreparation.Failure -> preparation.message
            }
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }

    fun showSystemWidgetPicker(desktopIndex: Int) {
        val providers = runCatching { appWidgetHost.queryInstalledProviders() }.getOrDefault(emptyList())
        if (providers.isEmpty()) {
            root.showConfigError("No hay widgets Android instalados")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Agregar widget Android")
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
                        root.showConfigError("Proveedor de widget inválido")
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
            omarchyAdapter = omarchy,
            screenFrames = screenFrames,
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
        val updated = settingsStore.updateOmarchyTheme(payload)
        currentSettings = updated
        runOnUiThread {
            root.submitSettings(updated)
            applySystemTheme(updated)
        }
    }

    private fun applySystemTheme(settings: LauncherSettings) {
        val palette = OmarchyThemePalette.fromSettings(settings.raw)
        val darkIcons = palette?.useDarkSystemIcons == true
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
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

    private fun reloadConfig() {
        io.execute {
            runCatching { storage.read(configRoot) }
                .onSuccess { config ->
                    currentConfig = config
                    runOnUiThread {
                        root.submitConfig(config)
                        if (config.desktops.any { it.ttfx.audio }) ensureAudioPermission()
                    }
                }
                .onFailure { error -> runOnUiThread { root.showConfigError(error.message.orEmpty()) } }
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
        io.execute {
            pluginRepository.pluginsDirectory.mkdirs()
            val plugins = pluginRepository.discover()
            runOnUiThread {
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
        io.execute {
            val widgets = runtimeWidgetStore.load()
            runOnUiThread { root.submitRuntimeWidgets(widgets) }
        }
    }

    private fun reloadSettings() {
        io.execute {
            runCatching { settingsStore.read() }.onSuccess { settings ->
                currentSettings = settings
                runOnUiThread {
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
        val uri = intent?.data ?: return
        if (uri.scheme != "omarchy") return
        val peer = OmarchyPeerUri.parse(uri.toString())
        if (peer == null) {
            root.showConfigError("Enlace Omarchy inválido")
            return
        }
        connectPeer(peer, persist = true)
        root.showPeerUri(uri)
    }

    private fun restorePeer() {
        val peer = currentSettings.omarchyPeer ?: return
        connectPeer(peer, persist = false)
    }

    private fun connectPeer(peer: OmarchyPeer, persist: Boolean) {
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
        if (palette == OmarchyThemePalette.fromSettings(currentSettings.raw)) return
        applyOmarchyTheme(palette.toJson())
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
        private const val REQUEST_STORAGE = 4001
        private const val API_PORT = 8753
        private const val PEER_PROBE_INTERVAL_MS = 15_000L
        private const val PLUGIN_RELOAD_DEBOUNCE_MS = 400L
        private val BUILT_IN_PLUGINS = mapOf(
            "io.github.ohm.demo.clock" to listOf("manifest.json", "BarWidget.json", "Panel.json"),
            "io.github.ohm.demo.weather" to listOf("manifest.json", "BarWidget.qml"),
        )
    }
}
