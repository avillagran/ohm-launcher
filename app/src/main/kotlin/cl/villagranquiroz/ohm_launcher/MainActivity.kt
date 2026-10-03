package cl.villagranquiroz.ohm_launcher

import android.Manifest
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val distributionPolicy = DistributionPolicy(BuildConfig.PLAY_STORE_DISTRIBUTION)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wallpaperPickerSession: FluxSession? = null
    private var wallpaperTemporarySession: FluxSession? = null
    private var localWallpaperPicker = false
    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val session=wallpaperPickerSession; wallpaperPickerSession=null
        val localChoice = localWallpaperPicker; localWallpaperPicker = false
        if(uri==null){wallpaperTemporarySession?.close();wallpaperTemporarySession=null}
        if(uri!=null && session!=null && wallpaperAllowed(session)) {
            mainHandler.postDelayed({if(wallpaperTemporarySession===session){session.close();wallpaperTemporarySession=null}},35_000)
            wallpaperIo.execute {
            runCatching { wallpaperSync.choose(session, checkNotNull(contentResolver.openInputStream(uri))) }
                .onFailure { runOnUiThread {
                    Toast.makeText(this,"No se pudo sincronizar el fondo; se conserva el anterior",Toast.LENGTH_LONG).show()
                    if(wallpaperTemporarySession===session){session.close();wallpaperTemporarySession=null}
                } }
            }
        } else if (uri != null && localChoice && !isDestroyed) {
            wallpaperIo.execute {
                runCatching {
                    val bytes = checkNotNull(contentResolver.openInputStream(uri)).use(FluxWallpaper::read)
                    val (mime, width, height) = FluxWallpaper.inspect(bytes)
                    val meta = FluxWallpaper.Meta(UUID.randomUUID().toString().replace("-", ""), "local",
                        "0".repeat(64), "local", FluxWallpaper.digest(bytes), mime, bytes.size, width, height)
                    val file = FluxWallpaper.store(filesDir, meta, bytes) { !isDestroyed }
                    persistWallpaper(file.absolutePath) { !isDestroyed }
                }.onFailure { runOnUiThread {
                    if (!isDestroyed) root.showConfigError(getString(R.string.background_selector_apply_failed))
                } }
            }
        } else if(wallpaperTemporarySession===session){session?.close();wallpaperTemporarySession=null}
    }
    private val wallpaperSync: FluxWallpaperSync<FluxSession> by lazy { FluxWallpaperSync<FluxSession>(filesDir, {it.id}, {it.wallpaperOrigin}, {session,body -> session.sendWallpaper(body)},
        { session -> wallpaperStates[session] }, ::wallpaperAllowed,
        { file, authority -> persistWallpaper(file.absolutePath, authority) },
        { message -> runOnUiThread { if(!isDestroyed) Toast.makeText(this,message,Toast.LENGTH_LONG).show(); wallpaperTemporarySession?.close();wallpaperTemporarySession=null } },
        {session,meta,bytes -> session.sendWallpaperOriginal(meta,bytes)},
        {session,meta -> wallpaperStates[session]?.let {it.current==meta.theme && it.revision==meta.revision}==true}) }
    private fun wallpaperAllowed(session:FluxSession):Boolean = !isDestroyed && !fluxStopping &&
        !distributionPolicy.playStore && fluxThemeSyncEnabled && session.supportsWallpaper &&
        session.wallpaperIsLive() && fluxLivePeers[session.id]===session

    private val wallpaperStates=ConcurrentHashMap<FluxSession,FluxWallpaperState>()
    private var openingBackgroundGallery: FluxSession? = null
    private val fluxBackgroundGallery: FluxBackgroundGallery<FluxSession> by lazy { FluxBackgroundGallery<FluxSession>(
        allowed = ::wallpaperAllowed,
        send = { session, body, authority -> session.sendBackgroundGallery(body, authority) },
        onCatalog = { session, catalog -> runOnUiThread {
            if (openingBackgroundGallery === session && wallpaperAllowed(session) &&
                fluxBackgroundGallery.isCurrent(session, catalog)) {
                openingBackgroundGallery = null
                showFluxBackgroundCatalog(session, catalog)
            }
        } },
        onSelected = { session, success, authority -> runOnUiThread {
            if (!success && authority() && wallpaperAllowed(session))
                root.showConfigError(getString(R.string.background_selector_apply_failed))
        } },
        onError = { session, authority -> runOnUiThread {
            if (openingBackgroundGallery === session && authority() && wallpaperAllowed(session)) {
                openingBackgroundGallery = null
                Toast.makeText(this, R.string.background_selector_unavailable, Toast.LENGTH_LONG).show()
                showBackgroundCards(OmarchyThemeCatalog("", emptyList()), { null }, {}) {
                    chooseCustomBackground(session)
                }
            }
        } },
    ) }
    private fun receiveWallpaper(session:FluxSession,body:JSONObject) {
        val frameKind = body.optString("kind")
        if (frameKind in setOf("state", "begin", "end"))
            android.util.Log.i("OhmFlux", "Wallpaper frame=$frameKind allowed=${wallpaperAllowed(session)}")
        wallpaperIo.execute {
            if(!wallpaperAllowed(session))return@execute
            if (fluxBackgroundGallery.receive(session, body)) return@execute
            if(body.optString("kind")=="state") {
                val revision=body.optString("revision");val theme=body.optString("theme")
                if(body.length()!=3 || !Regex("[0-9a-f]{64}").matches(revision) ||
                    !Regex("[a-z0-9][a-z0-9._-]{0,63}").matches(theme) || theme.contains(".."))return@execute
                wallpaperStates.keys.removeIf {it!==session && it.id==session.id}
                wallpaperStates[session]=FluxWallpaperState(theme,revision)
                wallpaperSync.retry(session)
                runOnUiThread {if(!isDestroyed)root.refreshFluxMenu()}
            } else wallpaperSync.receive(session,body)
        }
    }
    private fun chooseFluxWallpaper(session:FluxSession,closeAfter:Boolean) {
        if(!wallpaperAllowed(session) || wallpaperStates[session]==null)return
        localWallpaperPicker = false
        wallpaperPickerSession=session
        if(closeAfter)wallpaperTemporarySession=session
        try {wallpaperPicker.launch(arrayOf("image/jpeg","image/png","image/webp"))}
        catch(e:Exception){wallpaperPickerSession=null;wallpaperTemporarySession?.close();wallpaperTemporarySession=null;throw e}
    }

    /** Wallpaper IO owns persistence; UI only renders the committed document. */
    private fun persistWallpaper(path: String, authority: () -> Boolean) {
        val next = checkNotNull(storage.update(configRoot, authority) { document ->
            OmarchyLocalBackgroundPreview.apply(LauncherConfig.parse(document.toString()), path).raw
        }) { "Wallpaper selection is no longer current" }
        val work = java.util.concurrent.FutureTask {
            check(!isDestroyed && authority())
            currentConfig = next
            root.submitConfig(next, preserveFavorites = true)
            io.execute { if (authority()) syncSystemWallpaper(next, settingsStore.read()) }
        }
        runOnUiThread(work)
        try { work.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        catch (e: Exception) { work.cancel(false); throw e }
        File(path).nameWithoutExtension.takeIf { Regex("[0-9a-f]{64}").matches(it) }?.let {
            android.util.Log.i("OhmFlux", "Wallpaper committed sha256=$it")
        }
    }

    private val compactNavigationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            applyCompactSystemNavigation(currentSettings.omarchyBarMode)
        }
    }
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
    private var pendingAudioPermission: ((Boolean) -> Unit)? = null
    private var watcher: FileObserver? = null
    private val pluginWatchers = mutableListOf<FileObserver>()
    private var apiServer: LocalApiServer? = null
    private var lanAdvertiser: OmarchyLanAdvertiser? = null
    private val connectionState = OmarchyConnectionState()
    private val peerClient = OmarchyPeerClient()
    private var fluxDiscovery: FluxDiscovery? = null
    @Volatile private var fluxResponder: FluxLanResponder? = null
    @Volatile private var fluxResponderLease: FluxResponderLease? = null
    @Volatile private var fluxSessionEpoch: Any = Any()
    private var fluxHomeOwner = false
    @Volatile private var fluxScreenMirror: FluxScreenMirror? = null
    private var fluxScreenSession: FluxSession? = null
    private var fluxTouchpadView: FluxTouchpadView? = null
    private var fluxTouchpadSession: FluxSession? = null
    private var fluxInputToken: Long? = null
    private var fluxInputSendAction: ((FluxRemoteInputProtocol.Action) -> Unit)? = null
    private var fluxInputCloseSender: (() -> Unit)? = null
    private var closingFluxTouchpad = false
    private var pendingFluxCredential: Pair<String, FluxSession>? = null
    private data class PendingInputApproval(val session: FluxSession, val key: String, val dialog: AlertDialog)
    private var pendingInputApproval: PendingInputApproval? = null
    private var fluxApprovalCredential: Pair<FluxSession, String>? = null
    private var fluxInputApprovalOwner: Pair<FluxSession, String>? = null
    @Volatile private var fluxForeground = false
    @Volatile private var omarchyScreenConsentPending = false
    private val fluxLivePeers = java.util.concurrent.ConcurrentHashMap<String, FluxSession>()
    @Volatile private var fluxStopping = false
    private val pendingFluxPairs = java.util.concurrent.ConcurrentHashMap<String, FluxSession>()
    private val incomingFluxPairDialogs = mutableMapOf<FluxSession, AlertDialog>() // main thread only
    private val incomingFluxShareDialogs = mutableMapOf<FluxSession, AlertDialog>() // main thread only
    private class IncomingFluxOffer(val session: FluxSession, val file: FluxIncomingFile,
                                    val receiver: FluxIncomingFileReceiver, val token: String) {
        @Volatile var staged: File? = null
        @Volatile var save: FluxIncomingFileSave? = null
        var accepted = false // UI thread only
    }
    private val incomingFluxOffers = ConcurrentHashMap<FluxSession, IncomingFluxOffer>()
    private val incomingFluxOfferDialogs = mutableMapOf<FluxSession, AlertDialog>() // main thread only
    private val incomingFluxSavePickers = linkedMapOf<String, ActivityResultLauncher<String>>()
    private val incomingFluxPickerKeys = "fluxIncomingPickerKeys"

    private val outgoingFluxPairDialogs = FluxPairDialogRegistry<FluxSession, AlertDialog> { it.dismiss() } // main thread only
    private val fluxThemeCatalogs = FluxThemeCatalogCache<FluxSession>()
    // Direct edition only. Revocation is prompt, but a rename already in progress can finish;
    // FluxThemeFlow then reconciles the newest live catalog once the filesystem resumes.
    private val fluxThemeSyncEnabled = true
    private class PendingFluxTheme(val session: FluxSession, val closure: FluxThemePickerClosure<FluxSession>) {
        @Volatile var active = true
        var timer: Runnable? = null
        var acknowledged = false
        var requestId: String? = null
    }
    private val pendingFluxThemes = mutableMapOf<String, PendingFluxTheme>() // main thread only
    private val fluxThemeFlow = FluxThemeFlow<FluxSession>(
        allowed = { session -> fluxThemeSyncEnabled && !isDestroyed && !fluxStopping && !distributionPolicy.playStore &&
            fluxLivePeers[session.id] === session && session.paired && session.isOpen() },
        schedule = { work -> io.execute {
            runCatching(work).onFailure { android.util.Log.w("OhmFlux", "Theme sync failed", it) }
        } },
        apply = { palette, authority ->
            settingsStore.prepareOmarchyTheme(withDesktopTextColor(palette.toJson())).commit(authority)
        },
        onCommitted = { _, _, authority ->
            // Resolve effective settings at UI delivery, after any intervening theme or
            // unrelated settings write. The authority is also rechecked on that thread.
            SettingsUiDelivery({ runOnUiThread(it) }, settingsStore::read).post(
                { !isDestroyed && authority() },
            ) { updated ->
                currentSettings = updated
                root.submitSettings(updated)
                applySystemTheme(updated)
                io.execute { if (authority()) syncSystemWallpaper(currentConfig, settingsStore.read()) }
            }
        },
        select = { session, id -> session.requestThemeSelection(id, userApproved = true) },
        onApplied = { session, requestId, success -> runOnUiThread {
            val pending = pendingFluxThemes[session.id]
            if (pending?.session === session && pending.acknowledged && pending.requestId == requestId) {
                pendingFluxThemes.remove(session.id)
                pending.active = false
                pending.timer?.let(mainHandler::removeCallbacks)
                if (!success && !isDestroyed) showFluxThemeError(R.string.flux_theme_failed)
                pending.closure.finish()
            }
        } },
        onSuperseded = { session, requestId -> runOnUiThread {
            val pending = pendingFluxThemes[session.id]
            if (pending?.session === session && pending.acknowledged && pending.requestId == requestId) {
                pendingFluxThemes.remove(session.id)
                pending.active = false
                pending.timer?.let(mainHandler::removeCallbacks)
                showFluxThemeError(R.string.flux_theme_superseded)
                pending.closure.finish()
            }
        } },
    )
    internal var onFluxThemeSelectionResult: ((FluxSession, FluxThemeSelection.Result) -> Unit)? =
        { session, result -> finishFluxThemeSelection(session, result) }
    private fun onFluxThemeSelected(session: FluxSession, result: FluxThemeSelection.Result) {
        runOnUiThread {
            if (!isDestroyed && !distributionPolicy.playStore &&
                fluxLivePeers[session.id] === session && session.paired && session.isOpen()) {
                onFluxThemeSelectionResult?.invoke(session, result)
            }
        }
    }
    private val apiSessionToken: String = ByteArray(32).also(SecureRandom()::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }
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
    private val fluxFileRecovery = FluxFilePickerRecovery(
        editionAllowed = { !isDestroyed && !fluxStopping && !distributionPolicy.playStore },
        sessions = { fluxLivePeers.values.toList() },
        peerId = { session: FluxSession -> session.id },
        certificateDer = { session -> session.certificate.encoded },
        paired = { session -> session.paired },
        open = { session -> session.isOpen() },
        live = { session -> fluxLivePeers[session.id] === session },
    )
    private val fluxFileBridge = FluxFilePickerBridge<FluxSession, Uri>(
        fluxFileRecovery, { it.id }, { it.certificate.encoded }, { it.close() },
        { session, uri, finished -> session.submitFile(contentResolver, uri, finished) },
        { sent ->
            sent.exceptionOrNull()?.let { android.util.Log.w("OhmFlux", "File transfer failed", it) }
            runOnUiThread {
                if (!isDestroyed) Toast.makeText(this,
                    getString(if (sent.isSuccess) R.string.flux_file_written else R.string.flux_file_failed),
                    Toast.LENGTH_LONG).show()
            }
        },
        { reason ->
            android.util.Log.w("OhmFlux", "File picker recovery ended: $reason")
            if (reason != FluxFilePickerRecovery.Failure.CANCELLED) runOnUiThread {
                if (!isDestroyed) root.showConfigError(getString(R.string.flux_file_failed))
            }
        },
        revoke = { it.cancelTransfers() },
    )
    // Keep this legacy registration slot so other auto-generated result keys remain stable on recreation.
    private val legacyFluxDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { _: Uri? -> }
    private val fluxDocumentPickers = linkedMapOf<String, ActivityResultLauncher<Array<String>>>()

    private fun registerFluxDocumentPicker(launch: FluxFilePickerBridge.Launch): ActivityResultLauncher<Array<String>> {
        val picker = activityResultRegistry.register(launch.key, ActivityResultContracts.OpenDocument()) { uri ->
            fluxFileBridge.returned(launch, uri)
        }
        fluxDocumentPickers[launch.key] = picker
        return picker
    }

    private val screenConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        omarchyScreenConsentPending = false
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
    private val fluxScreenConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val mirror = fluxScreenMirror ?: return@registerForActivityResult
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null || !mirror.start(result.resultCode, data)) {
            mirror.close()
            if (!isDestroyed) root.showConfigError(getString(R.string.flux_screen_failed))
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
        audioPermissionRequested = false
        pendingAudioPermission?.invoke(granted)
        pendingAudioPermission = null
        if (granted) root.refreshAudioCapture()
    }
    private val bluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) scanOmarchyBluetooth()
    }
    // Keep the prior launcher registration slots stable across Activity recreation.
    private val legacyIncomingFluxSavePicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")) { _: Uri? -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fluxHomeOwner = savedInstanceState?.getBoolean("fluxHomeOwner", false) == true ||
            intent?.hasCategory(Intent.CATEGORY_HOME) == true
        // All prior keys, including completed launches, retain their request-code ownership.
        // A restored Activity has no old session; its old callbacks discard results, not reuse the URI.
        savedInstanceState?.getStringArrayList(FLUX_PICKER_KEYS)?.forEach { key ->
            if (key !in fluxDocumentPickers) {
                fluxDocumentPickers[key] = activityResultRegistry.register(
                    key, ActivityResultContracts.OpenDocument()) { _: Uri? -> }
            }
        }
        savedInstanceState?.getStringArrayList(incomingFluxPickerKeys)?.forEach { key ->
            if (key !in incomingFluxSavePickers) incomingFluxSavePickers[key] = activityResultRegistry.register(
                key, ActivityResultContracts.CreateDocument("application/octet-stream")) { _: Uri? -> }
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(LauncherSystemBarPolicy.navigationBarColor()),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        root = NativeLauncherView(this)
        setContentView(root)
        ContextCompat.registerReceiver(
            this,
            compactNavigationReceiver,
            IntentFilter(OmarchyNavigationService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fluxTouchpadView != null) { closeFluxTouchpad(); return }
                if (root.handleBackPressed()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        runCatching {
            val installer = EmbeddedToolsInstaller(filesDir, assets::open)
            if (distributionPolicy.playStore) installer.removeInstalledTools()
            else installer.install(Build.SUPPORTED_ABIS.toList())
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
        android.util.Log.i("OhmFlux", "Wallpaper config root=${configRoot.absolutePath}")
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
        quakeTerminal.submitTheme()
        applyCompactSystemNavigation(currentSettings.omarchyBarMode)
        currentConfig = runCatching { storage.read(configRoot) }.getOrElse { currentConfig }
        root.submitConfig(currentConfig)
        io.execute { syncSystemWallpaper(currentConfig, currentSettings) }
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
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            fluxHomeOwner = true
            requestFluxResponderOwnership()
        }
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        fluxForeground = true
        requestFluxResponderOwnership()
        disableHomeChooserStub()
        applyCompactSystemNavigation(currentSettings.omarchyBarMode)
        loadApps()
    }

    private fun requestFluxResponderOwnership() {
        if (isDestroyed || fluxStopping || distributionPolicy.playStore) return
        FluxResponderOwners.registry.request(this, fluxHomeOwner,
            ::startOwnedFluxResponder, ::revokeFluxResponderOwnership)
    }

    private fun ownsFluxResponder(lease: FluxResponderLease): Boolean =
        !isDestroyed && !fluxStopping && !distributionPolicy.playStore &&
            fluxResponderLease === lease && FluxResponderOwners.registry.isCurrent(this, lease)

    private fun startOwnedFluxResponder(lease: FluxResponderLease) {
        fluxResponderLease = lease
        io.execute {
            runCatching {
                if (!ownsFluxResponder(lease)) return@execute
                val responder = FluxLanResponder(applicationContext,
                    onConnected = { session ->
                        if (!ownsFluxResponder(lease)) session.close()
                        else runCatching { registerFluxSession(session, lease) }.onFailure { session.close() }
                    },
                    onPairRequest = { session, timestamp -> runOnUiThread {
                        if (ownsFluxResponder(lease)) showIncomingFluxPair(session, timestamp, lease) else session.close()
                    } },
                    onDisconnected = { session -> if (ownsFluxResponder(lease)) onFluxDisconnected(session) else session.close() },
                    onUnpair = { session -> if (ownsFluxResponder(lease)) onFluxRemoteUnpair(session, lease) else session.close() },
                    onScreen = { session, reply -> runOnUiThread {
                        if (ownsFluxResponder(lease) && fluxScreenSession === session) fluxScreenMirror?.onReply(reply)
                    } },
                    onThemeCatalog = { session, catalog -> runOnUiThread {
                        if (ownsFluxResponder(lease)) receiveFluxThemeCatalog(session, catalog)
                    } },
                    onMedia = { session, body, token -> if (ownsFluxResponder(lease)) onFluxMedia(session, body, token) },
                    onShare = { session, share -> if (ownsFluxResponder(lease)) onFluxIncomingShare(session, share) },
                    onFileOffer = { session, file -> if (ownsFluxResponder(lease)) onFluxIncomingFileOffer(session, file) },
                    onInputState = { session, enabled -> if (ownsFluxResponder(lease)) onFluxInputState(session, enabled) },
                    onThemeSelected = { session, result -> if (ownsFluxResponder(lease)) onFluxThemeSelected(session, result) },
                )
                if (!lease.attach(responder)) { responder.close(); return@execute }
                val admitted = synchronized(fluxLivePeers) {
                    if (!ownsFluxResponder(lease)) false else { fluxResponder = responder; true }
                }
                if (!admitted) { responder.close(); return@execute }
                responder.start()
                if (!ownsFluxResponder(lease)) responder.close()
            }.onFailure { error ->
                android.util.Log.w("OhmFlux", "Flux LAN responder unavailable", error)
                runOnUiThread { FluxResponderOwners.registry.failed(this, lease) }
            }
        }
    }

    private fun revokeFluxResponderOwnership(lease: FluxResponderLease) {
        if (fluxResponderLease !== lease) return
        val sessions = synchronized(fluxLivePeers) {
            fluxResponderLease = null
            fluxResponder = null // The revoked lease closes its attached responder outside the registry monitor.
            fluxSessionEpoch = Any()
            (fluxLivePeers.values + pendingFluxPairs.values).toSet().also {
                fluxLivePeers.clear()
                pendingFluxPairs.clear()
            }
        }
        sessions.forEach { session ->
            session.cancelTransfers()
            session.close()
            fluxFileBridge.revoked(session.id)
            revokeIncomingFluxOffer(session)
            cancelPendingFluxTheme(session)
        }
        cancelPendingInputApproval()
        fluxApprovalCredential?.let { revokeInputApproval(it.first, it.second) }
        pendingFluxCredential = null
        closeFluxTouchpad()
        fluxScreenMirror?.close()
        outgoingFluxPairDialogs.clear()
        incomingFluxPairDialogs.values.toList().forEach { it.dismiss() }
        incomingFluxPairDialogs.clear()
        incomingFluxShareDialogs.values.toList().forEach { it.dismiss() }
        incomingFluxShareDialogs.clear()
        if (fluxMediaViewSession != null) root.closeFluxMediaMenu()
        fluxMediaSelection.clear()
        fluxThemeCatalogs.clear()
        if (!isDestroyed) root.refreshFluxMenu()
    }

    override fun onStop() {
        fluxForeground = false
        cancelPendingInputApproval()
        fluxApprovalCredential?.let { revokeInputApproval(it.first, it.second) }
        pendingFluxCredential = null
        closeFluxTouchpad()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyCompactSystemNavigation(currentSettings.omarchyBarMode)
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("fluxHomeOwner", fluxHomeOwner)
        outState.putStringArrayList(FLUX_PICKER_KEYS, ArrayList(fluxDocumentPickers.keys))
        outState.putStringArrayList(incomingFluxPickerKeys, ArrayList(incomingFluxSavePickers.keys))

        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        openingBackgroundGallery = null
        wallpaperTemporarySession?.close();wallpaperTemporarySession=null
        wallpaperStates.clear()
        fluxForeground = false
        synchronized(fluxLivePeers) { fluxStopping = true }
        cancelPendingInputApproval()
        fluxApprovalCredential?.let { revokeInputApproval(it.first, it.second) }
        closeFluxTouchpad()
        pendingFluxCredential = null
        fluxFileBridge.destroy()
        incomingFluxOffers.keys.toList().forEach(::revokeIncomingFluxOffer)
        incomingFluxSavePickers.values.forEach { it.unregister() }
        incomingFluxSavePickers.clear()
        fluxDocumentPickers.values.forEach { it.unregister() }
        fluxDocumentPickers.clear()
        runCatching { unregisterReceiver(compactNavigationReceiver) }
        watcher?.stopWatching()
        pluginWatchers.forEach(FileObserver::stopWatching)
        pluginWatchers.clear()
        mainHandler.removeCallbacks(peerProbe)
        mainHandler.removeCallbacks(pluginReload)
        fluxDiscovery?.close()
        fluxDiscovery = null
        fluxScreenMirror?.close()
        fluxScreenMirror = null
        fluxScreenSession = null
        FluxResponderOwners.registry.release(this)
        fluxResponder?.close()
        fluxResponder = null
        val closingFlux = synchronized(fluxLivePeers) {
            (fluxLivePeers.values + pendingFluxPairs.values).toSet().also {
                fluxLivePeers.clear()
                pendingFluxPairs.clear()
            }
        }
        closingFlux.forEach { fluxBackgroundGallery.revoke(it); it.cancelTransfers(); it.close() }
        pendingFluxThemes.values.toList().forEach { pending ->
            pending.active = false
            pending.timer?.let(mainHandler::removeCallbacks)
            fluxThemeFlow.revoke(pending.session.id, pending.session)
        }
        pendingFluxThemes.clear()
        incomingFluxPairDialogs.values.toList().forEach { it.dismiss() }
        incomingFluxPairDialogs.clear()
        incomingFluxShareDialogs.values.toList().forEach { it.dismiss() }
        incomingFluxShareDialogs.clear()
        incomingFluxOfferDialogs.values.toList().forEach { it.dismiss() }
        incomingFluxOfferDialogs.clear()
        outgoingFluxPairDialogs.clear()
        fluxThemeCatalogs.clear()
        onFluxThemeSelectionResult = null
        fluxMediaSelection.clear()
        fluxMediaControlDialog?.dismiss()
        fluxMediaControlDialog = null
        fluxMediaViewSession = null
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
        if (!distributionPolicy.allowAccessibilityControl && !distributionPolicy.allowCompactRecentsNavigation) return
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun showRemoteControlPermissionDialog() {
        themedDialog()
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
            .fallbackUri(preferredLanIp(), apiSessionToken)
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
        themedDialog()
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
                    themedDialog()
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
        themedDialog()
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
        themedDialog()
            .setTitle(R.string.add_box_title)
            .setView(name)
            .setPositiveButton(R.string.choose_edge) { _, _ ->
                val value = name.text.toString()
                val edges = EdgePosition.entries
                themedDialog()
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
        themedDialog()
            .setTitle(R.string.remove_application)
            .setMessage(getString(R.string.remove_application_confirm, label.ifBlank { getString(R.string.menu_app_generic) }))
            .setPositiveButton(R.string.remove) { _, _ ->
                editDesktopConfig { DesktopConfigEditor.removeEdgeBoxItem(it, boxId, itemIndex) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun confirmRemoveEdgeBox(id: String, name: String) {
        themedDialog()
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
                    SettingsUiDelivery({ runOnUiThread(it) }, settingsStore::read).post(
                        { !isDestroyed },
                    ) { latest ->
                        currentSettings = latest
                        root.submitSettings(latest)
                    }
                }
                .onFailure { runOnUiThread { root.showConfigError(it.message.orEmpty()) } }
        }
    }

    fun setOmarchyBarMode(enabled: Boolean) {
        val previous = currentSettings
        val updated = previous.copy(omarchyBarMode = enabled)
        currentSettings = updated
        root.submitSettings(updated)
        applyCompactSystemNavigation(enabled)
        io.execute {
            runCatching { settingsStore.write(updated) }
                .onSuccess {
                    SettingsUiDelivery({ runOnUiThread(it) }, settingsStore::read).post(
                        { !isDestroyed },
                    ) { latest ->
                        currentSettings = latest
                        root.submitSettings(latest)
                        applySystemTheme(latest)
                    }
                }
                .onFailure {
                    runOnUiThread {
                        currentSettings = previous
                        root.submitSettings(previous)
                        applyCompactSystemNavigation(previous.omarchyBarMode)
                        root.showConfigError(it.message.orEmpty())
                    }
                }
        }
    }

    private fun applyCompactSystemNavigation(compact: Boolean) {
        val compactAllowed = distributionPolicy.allowCompactSystemNavigation &&
            CompactNavigationPolicy.shouldHideSystemNavigation(
                OmarchyBarModePolicy.ALWAYS_OMARCHY_MODE,
                compactNavigationServiceConnected(),
            )
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
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (compactAllowed) {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        // Accessibility only controls the right-side shortcut; Omarchy layout
        // stays unchanged whether the service is connected or not.
        root.refreshGestureServiceState()
    }

    private fun performCompactNavigation(action: String) {
        if (!distributionPolicy.allowCompactRecentsNavigation || action != "recents") return
        if (distributionPolicy.playStore) {
            val service = OmarchyNavigationService.instance
            if (service == null) {
                showCompactNavigationDisclosure()
                return
            }
            service.openRecents()
            return
        }
        if (!distributionPolicy.allowAccessibilityControl) return
        val service = OhmGestureAccessibilityService.instance
        if (service == null) {
            openAccessibilitySettings()
            return
        }
        service.remoteKey(action)
    }

    private fun compactNavigationServiceConnected(): Boolean =
        if (distributionPolicy.playStore) {
            OmarchyNavigationService.instance != null
        }
        else OhmGestureAccessibilityService.instance != null


    fun requestAccessibilityForOmarchyBar() {
        if (!compactNavigationServiceConnected()) showCompactNavigationDisclosure()
    }

    private fun showCompactNavigationDisclosure() {
        themedDialog()
            .setTitle(R.string.omarchy_navigation_disclosure_title)
            .setMessage(R.string.omarchy_navigation_disclosure_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_continue) { _, _ -> openAccessibilitySettings() }
            .show()
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
                        restartApiServer()
                        SettingsUiDelivery({ runOnUiThread(it) }, settingsStore::read).post(
                            { !isDestroyed },
                        ) { latest ->
                            currentSettings = latest
                            root.submitSettings(latest)
                            applySystemTheme(latest)
                            if (!latest.quakeTerminal) showQuake(false)
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
            onAudioEnabledRequested = ::ensureAudioPermission,
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

    private fun themedDialog(): OmarchyDialogBuilder =
        OmarchyDialogBuilder(this) { currentSettings.settingsPanelOpacity }

    fun showWidgetTextColorPicker() {
        val palette = OmarchyThemePalette.fromSettings(currentSettings.raw)
        val roles = palette?.colors?.keys?.sorted().orEmpty()
        val labels = arrayOf(getString(R.string.menu_theme_background_reset)) +
            roles.map { role -> "$role  ·  ${palette?.color(role)}" }
        themedDialog()
            .setTitle(R.string.menu_theme_background_color)
            .setSingleChoiceItems(labels, roles.indexOf(currentSettings.widgetTextColorRole) + 1) { dialog, selected ->
                saveWidgetTextColorRole(roles.getOrNull(selected - 1))
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveWidgetTextColorRole(role: String?) {
        val validated = OmarchyWidgetTextColorPolicy.normalize(
            role, OmarchyThemePalette.fromSettings(currentSettings.raw),
        )
        val previous = currentSettings
        val updated = LauncherSettings.parse(previous.copy(widgetTextColorRole = validated).toJson())
        currentSettings = updated
        root.submitSettings(updated, animateTheme = false)
        quakeTerminal.submitTheme()
        io.execute {
            runCatching {
                settingsStore.updateRaw { document ->
                    document.remove("themeBackgroundColor")
                    document.put("widgetTextColorRole", validated ?: JSONObject.NULL)
                }
            }.onFailure { error ->
                runOnUiThread {
                    currentSettings = previous
                    root.submitSettings(previous, animateTheme = false)
                    root.showConfigError(error.message.orEmpty())
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
            root.showConfigError(getString(R.string.no_plugins))
            return
        }
        themedDialog()
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
        themedDialog()
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
            themedDialog()
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
        themedDialog()
            .setTitle(plugin.manifest?.name ?: plugin.id)
            .setItems(actions) { _, action ->
                when {
                    enabled && action == 0 -> appendPluginWidget(plugin, root.activeDesktopIndex())
                    action == if (enabled) 1 else 0 -> {
                        if (enabled) pluginRepository.disable(plugin.id) else pluginRepository.enable(plugin.id)
                        reloadPlugins()
                        Toast.makeText(this, if (enabled) R.string.plugin_disabled else R.string.plugin_enabled, Toast.LENGTH_SHORT).show()
                    }
                    else -> themedDialog()
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
                    themedDialog()
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
        themedDialog()
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
        if (!connectionState.isConnected || screenCapture.isRunning() ||
            omarchyScreenConsentPending || fluxScreenMirror != null) return false
        screenFrames.clear()
        omarchyScreenConsentPending = true
        runOnUiThread {
            if (isDestroyed) { omarchyScreenConsentPending = false; return@runOnUiThread }
            runCatching { screenConsent.launch(screenCapture.createConsentIntent()) }.onFailure {
                omarchyScreenConsentPending = false
            }
        }
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
            sessionToken = apiSessionToken,
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
        SettingsUiDelivery({ runOnUiThread(it) }, settingsStore::read).post(
            { !isDestroyed },
        ) { latest ->
            currentSettings = latest
            root.submitSettings(latest)
            applySystemTheme(latest)
        }
        io.execute { syncSystemWallpaper(currentConfig, settingsStore.read()) }
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
        quakeTerminal.submitTheme()
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
                quakeTerminal.submitTheme()
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

    private fun ensureAudioPermission(onResult: (Boolean) -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            root.refreshAudioCapture()
            onResult(true)
            return
        }
        if (!audioPermissionRequested) {
            audioPermissionRequested = true
            pendingAudioPermission = onResult
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
        val seedStateDirectory = filesDir.resolve("builtin-plugin-seed-state").apply { mkdirs() }
        BUILT_IN_PLUGINS.forEach { (id, files) ->
            val destination = pluginRepository.pluginsDirectory.resolve(id)
            runCatching {
                destination.mkdirs()
                val stateFile = seedStateDirectory.resolve("$id.json")
                val previousHashes = stateFile.takeIf(File::isFile)?.let { JSONObject(it.readText()) } ?: JSONObject()
                val currentHashes = JSONObject()
                files.forEach { name ->
                    val bundledBytes = assets.open("plugins/$id/$name").use { it.readBytes() }
                    val target = destination.resolve(name)
                    val existingHash = target.takeIf(File::isFile)?.let { BuiltInPluginSeedPolicy.sha256(it.readBytes()) }
                    val previousHash = previousHashes.optString(name).takeIf(String::isNotBlank)
                    val bundledHash = BuiltInPluginSeedPolicy.sha256(bundledBytes)
                    val legacyHashes = LEGACY_BUILT_IN_ASSET_HASHES["$id/$name"].orEmpty()
                    if (BuiltInPluginSeedPolicy.shouldReplace(existingHash, previousHash, bundledHash, legacyHashes)) {
                        target.parentFile?.mkdirs()
                        val temporary = target.resolveSibling(".${target.name}.seed.tmp")
                        try {
                            temporary.writeBytes(bundledBytes)
                            if (!temporary.renameTo(target)) temporary.copyTo(target, overwrite = true)
                        } finally {
                            temporary.delete()
                        }
                    }
                    currentHashes.put(name, bundledHash)
                }
                val temporaryState = stateFile.resolveSibling("${stateFile.name}.tmp")
                try {
                    temporaryState.writeText(currentHashes.toString())
                    if (!temporaryState.renameTo(stateFile)) temporaryState.copyTo(stateFile, overwrite = true)
                } finally {
                    temporaryState.delete()
                }
            }
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
            if (isDestroyed) return@execute
            SettingsUiDelivery({ runOnUiThread(it) }, settingsStore::read).post(
                { !isDestroyed },
            ) { settings ->
                currentSettings = settings
                root.submitSettings(settings)
                applySystemTheme(settings)
            }
        }
    }

    @Suppress("DEPRECATION") // String-path FileObserver keeps minSdk 24 support.
    private fun watchPlugins(plugins: List<Plugin>) {
        pluginWatchers.forEach(FileObserver::stopWatching)
        pluginWatchers.clear()
        val directories = (listOf(pluginRepository.pluginsDirectory) + plugins.map(Plugin::folder)).distinct()
        directories.filter(File::isDirectory).forEach { directory ->
            pluginWatchers += object : FileObserver(directory.absolutePath, CLOSE_WRITE or MOVED_TO or MOVED_FROM or CREATE or DELETE) {
                override fun onEvent(event: Int, path: String?) {
                    mainHandler.removeCallbacks(pluginReload)
                    mainHandler.postDelayed(pluginReload, PLUGIN_RELOAD_DEBOUNCE_MS)
                }
            }.also(FileObserver::startWatching)
        }
    }

    @Suppress("DEPRECATION") // String-path FileObserver keeps minSdk 24 support.
    private fun startWatcher() {
        watcher?.stopWatching()
        watcher = object : FileObserver(configRoot.absolutePath, CLOSE_WRITE or MOVED_TO or CREATE) {
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
        if (!distributionPolicy.allowOmarchyPeerConnection) return
        val uri = intent?.data ?: return
        if (uri.scheme != "omarchy") return
        val peer = OmarchyPeerUri.parse(uri.toString())
        if (peer == null || (distributionPolicy.playStore && peer.token.isEmpty())) {
            root.showConfigError(getString(R.string.invalid_omarchy_link))
            return
        }
        connectPeer(peer, persist = true)
        root.showPeerUri(uri)
    }

    private fun restorePeer() {
        if (!distributionPolicy.allowOmarchyPeerConnection) return
        val peer = currentSettings.omarchyPeer ?: return
        if (!OmarchyPeerRestorePolicy.canRestore(peer, distributionPolicy.playStore)) {
            persistPeer(null)
            return
        }
        connectPeer(peer, persist = false)
    }

    private fun connectPeer(peer: OmarchyPeer, persist: Boolean) {
        if (!distributionPolicy.allowOmarchyPeerConnection) return
        connectionState.connect(peer, replace = true)
        if (distributionPolicy.allowClipboardSync) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ClipboardMonitorService::class.java)
                    .putExtra("peerIp", peer.host)
                    .putExtra("peerPort", peer.port)
                    .putExtra("peerToken", peer.token),
            )
        }
        mainHandler.removeCallbacks(peerProbe)
        mainHandler.postDelayed(peerProbe, PEER_PROBE_INTERVAL_MS)
        io.execute {
            if (persist) persistPeer(peer)
            peerClient.notify(
                peer,
                preferredLanIp(),
                apiServer?.boundPort ?: API_PORT,
                Build.MODEL.ifBlank { "OhmLauncher" },
                apiSessionToken,
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

    /** The Omarchy Link row sends through Link only; Flux has its own direct rows. */
    fun shareTextWithPeer() {
        val input = EditText(this).apply {
            hint = getString(R.string.peer_share_hint)
            minLines = 2
            maxLines = 5
            setPadding(32, 24, 32, 24)
        }
        themedDialog()
            .setTitle(R.string.peer_share_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.peer_share_choose) { _, _ ->
                val text = input.text.toString()
                if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > 64 * 1024) {
                    root.showConfigError(getString(R.string.peer_share_invalid))
                } else {
                    sendLinkText(text)
                }
            }
            .show()
    }

    private fun sendLinkText(text: String) {
        val link = currentSettings.omarchyPeer?.takeIf { it.token.isNotBlank() }
        val dispatched = OmarchyLinkTextPolicy.send(link,
            PeerActionPolicy.supports(PeerTransport.OMARCHY_LINK, PeerAction.SEND_TEXT, distributionPolicy.playStore)) { peer ->
            io.execute {
                val sent = peerClient.sendText(peer, text)
                runOnUiThread {
                    if (!isDestroyed) Toast.makeText(this, getString(if (sent) R.string.peer_share_sent else R.string.peer_share_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (!dispatched) {
            root.showConfigError(getString(R.string.peer_share_failed))
        }
    }

    fun openFluxDesktop() {
        if (!distributionPolicy.playStore) browseFluxPeers(null)
    }

    private fun showIncomingFluxPair(session: FluxSession, timestamp: Long, lease: FluxResponderLease) {
        if (!ownsFluxResponder(lease) || session.paired || fluxLivePeers[session.id] !== session) {
            session.rejectIncomingPair()
            return
        }
        val key = session.key(timestamp)
        val dialog = themedDialog().setTitle(R.string.flux_pair_title)
            .setMessage(getString(R.string.flux_incoming_compare, session.name, key))
            .setNegativeButton(android.R.string.cancel) { _, _ -> session.rejectIncomingPair() }
            .setPositiveButton(R.string.flux_codes_match) { _, _ ->
                io.execute {
                    val accepted = runCatching {
                        check(ownsFluxResponder(lease) && session.isOpen())
                        session.acceptIncomingPair(timestamp) { pin ->
                            FluxSessionAuthorization.withMapped(fluxLivePeers, session.id, session) {
                                check(ownsFluxResponder(lease) && session.isOpen())
                                pin()
                            }
                        }
                    }.isSuccess
                    if (!accepted) runOnUiThread {
                        if (!isDestroyed) root.showConfigError(getString(R.string.flux_pair_failed))
                    }
                }
            }.setOnCancelListener { session.rejectIncomingPair() }.create()
        dialog.setOnDismissListener { incomingFluxPairDialogs.remove(session, dialog) }
        incomingFluxPairDialogs.put(session, dialog)?.dismiss()
        dialog.show()
    }

    /** User-started discovery: no background LAN service, companion app, or HTTP token. */
    private fun browseFluxPeers(text: String?) {
        if (distributionPolicy.playStore) return
        fluxDiscovery?.close()
        val peers = linkedMapOf<String, FluxDiscovery.Endpoint>()
        val localFluxId = runCatching { FluxIdentity(applicationContext).deviceId }.getOrNull()
        val live = fluxLivePeers.values.filter { it.paired && it.isOpen() }.sortedBy { it.name }
        val labels = mutableListOf<String>().apply {
            addAll(live.map { "${it.name} (${getString(R.string.flux_connected)})" })
        }
        val dialog = themedDialog()
            .setTitle(R.string.flux_discover_title)
            .setItems(if (labels.isEmpty()) arrayOf(getString(R.string.flux_searching)) else labels.toTypedArray()) { _, _ -> }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val discovery = FluxDiscovery(getSystemService(NsdManager::class.java))
        fluxDiscovery = discovery
        dialog.setOnDismissListener {
            discovery.close()
            if (fluxDiscovery === discovery) fluxDiscovery = null
        }
        dialog.show()
        dialog.listView.setOnItemClickListener { _, _, position, _ ->
            val existing = live.getOrNull(position)
            if (existing != null) {
                dialog.dismiss()
                if (existing.paired) {
                    showFluxActions(existing, text, closeAfter = false)
                } else root.showConfigError(getString(R.string.flux_link_failed, "offline"))
                return@setOnItemClickListener
            }
            val endpoint = peers.values.elementAtOrNull(position - live.size) ?: return@setOnItemClickListener
            dialog.dismiss()
            connectFlux(endpoint, text)
        }
        try {
            discovery.start({ endpoint ->
                runOnUiThread {
                    if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                    if (endpoint.id == localFluxId || live.any { it.id == endpoint.id }) return@runOnUiThread
                    peers[endpoint.id] = endpoint
                    labels.clear()
                    labels.addAll(live.map { "${it.name} (${getString(R.string.flux_connected)})" })
                    labels.addAll(peers.values.map { "${it.name} (${it.address.hostAddress})" })
                    dialog.listView.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
                }
            }, {
                runOnUiThread { if (!isDestroyed && dialog.isShowing) root.showConfigError(getString(R.string.flux_discovery_failed)) }
            })
        } catch (error: Exception) {
            dialog.dismiss()
            root.showConfigError(getString(R.string.flux_discovery_failed))
        }
        mainHandler.postDelayed({
            if (!isDestroyed && dialog.isShowing && peers.isEmpty() && live.isEmpty()) {
                dialog.setTitle(R.string.flux_no_peers)
                discovery.close()
            }
        }, 8_000)
    }

    private fun onFluxIncomingShare(session: FluxSession, share: FluxIncomingShare) {
        runOnUiThread {
            if (isDestroyed || fluxStopping || distributionPolicy.playStore ||
                fluxLivePeers[session.id] !== session || !session.paired || !session.isOpen()) return@runOnUiThread
            if (incomingFluxShareDialogs[session]?.isShowing == true) return@runOnUiThread
            val value = when (share) {
                is FluxIncomingShare.Text -> share.value
                is FluxIncomingShare.Url -> share.value
            }
            val dialog = themedDialog()
                .setTitle(getString(R.string.flux_incoming_share_title, session.name))
                .setMessage(value)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(if (share is FluxIncomingShare.Url) R.string.flux_incoming_open
                                   else R.string.flux_incoming_copy) { _, _ ->
                    if (isDestroyed || fluxStopping || distributionPolicy.playStore ||
                        fluxLivePeers[session.id] !== session || !session.paired || !session.isOpen()) return@setPositiveButton
                    when (share) {
                        is FluxIncomingShare.Text -> {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Flux", value))
                        }
                        is FluxIncomingShare.Url -> runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)).addCategory(Intent.CATEGORY_BROWSABLE))
                        }.onFailure { root.showConfigError(getString(R.string.flux_incoming_open_failed)) }
                    }
                }.create()
            dialog.setOnDismissListener { incomingFluxShareDialogs.remove(session, dialog) }
            incomingFluxShareDialogs[session] = dialog
            dialog.show()
        }
    }

    private fun revokeIncomingFluxOffer(session: FluxSession, expected: IncomingFluxOffer? = null) {
        val offer = incomingFluxOffers[session] ?: return
        if (expected != null && offer !== expected) return
        if (!incomingFluxOffers.remove(session, offer)) return
        val (save, staged) = synchronized(offer) { offer.save to offer.staged }
        offer.receiver.revoke()
        save?.revoke()
        if (save == null) staged?.let { file ->
            Thread({ file.delete() }, "ohm-flux-incoming-discard").apply { isDaemon = true }.start()
        }
        runOnUiThread { incomingFluxOfferDialogs.remove(session)?.dismiss() }
    }

    private fun onFluxIncomingFileOffer(session: FluxSession, file: FluxIncomingFile) {
        val offer = runCatching {
            synchronized(fluxLivePeers) {
                check(!isDestroyed && !fluxStopping && !distributionPolicy.playStore &&
                    fluxLivePeers[session.id] === session && session.paired && session.isOpen())
                check(incomingFluxOffers[session] == null) { "An incoming file is already pending" }
                val receiver = session.incomingFileReceiver(cacheDir,
                    { fluxLivePeers[session.id] },
                    { !isDestroyed && !fluxStopping && !distributionPolicy.playStore &&
                        session.paired && session.isOpen() })
                val token = receiver.offer(file)
                IncomingFluxOffer(session, file, receiver, token).also { incomingFluxOffers[session] = it }
            }
        }.getOrNull() ?: return
        mainHandler.postDelayed({
            if (incomingFluxOffers[session] === offer && !offer.accepted) revokeIncomingFluxOffer(session, offer)
        }, 20_000)
        runOnUiThread {
            if (incomingFluxOffers[session] !== offer || isDestroyed || !session.paired || !session.isOpen()) {
                revokeIncomingFluxOffer(session, offer)
                return@runOnUiThread
            }
            val dialog = themedDialog()
                .setTitle(R.string.flux_incoming_file_title)
                .setMessage(getString(R.string.flux_incoming_file_prompt, session.name, file.name, file.size))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.flux_incoming_file_accept) { _, _ ->
                    if (incomingFluxOffers[session] !== offer || !session.paired || !session.isOpen()) {
                        revokeIncomingFluxOffer(session, offer)
                        return@setPositiveButton
                    }
                    offer.accepted = true
                    Thread({
                        val staged = runCatching { offer.receiver.accept(offer.token) }
                        staged.onFailure { error ->
                            android.util.Log.w("OhmFlux", "Incoming file transfer failed", error)
                            revokeIncomingFluxOffer(session, offer)
                            runOnUiThread { if (!isDestroyed) root.showConfigError(getString(R.string.flux_incoming_file_failed)) }
                        }
                        staged.getOrNull()?.let { fileOnDisk ->
                            val retained = synchronized(offer) {
                                if (incomingFluxOffers[session] !== offer) false
                                else { offer.staged = fileOnDisk; true }
                            }
                            if (!retained) {
                                fileOnDisk.delete()
                                return@let
                            }
                            runOnUiThread {
                                if (isDestroyed || incomingFluxOffers[session] !== offer ||
                                    !session.paired || !session.isOpen()) {
                                    revokeIncomingFluxOffer(session, offer)
                                } else launchIncomingFluxSave(offer, fileOnDisk)
                            }
                        }
                    }, "ohm-flux-incoming-accept").apply { isDaemon = true }.start()
                }.create()
            dialog.setOnDismissListener {
                incomingFluxOfferDialogs.remove(session, dialog)
                if (!offer.accepted) revokeIncomingFluxOffer(session, offer)
            }
            incomingFluxOfferDialogs[session] = dialog
            dialog.show()
        }
    }

    private fun launchIncomingFluxSave(offer: IncomingFluxOffer, staged: File) {
        val session = offer.session
        val key = "flux-incoming-${UUID.randomUUID()}"
        val picker = activityResultRegistry.register(
            key, ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri == null || isDestroyed || incomingFluxOffers[session] !== offer ||
                !session.paired || !session.isOpen()) {
                revokeIncomingFluxOffer(session, offer)
                return@register
            }
            val save = FluxIncomingFileSave(session, offer.token,
                { fluxLivePeers[session.id] },
                { incomingFluxOffers[session]?.token },
                { !isDestroyed && !fluxStopping && !distributionPolicy.playStore &&
                    session.paired && session.isOpen() },
                { contentResolver.openOutputStream(uri, "wt") })
            val started = synchronized(offer) {
                if (incomingFluxOffers[session] !== offer || !session.paired || !session.isOpen()) false
                else {
                    offer.save = save
                    save.start(staged, offer.file.size) { result ->
                    if (result.isFailure) runCatching {
                        android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
                    }
                    revokeIncomingFluxOffer(session, offer)
                    runOnUiThread {
                        if (!isDestroyed) Toast.makeText(this,
                            getString(if (result.isSuccess) R.string.flux_incoming_file_saved
                                      else R.string.flux_incoming_file_failed), Toast.LENGTH_LONG).show()
                    }
                    }
                }
            }
            if (!started) {
                revokeIncomingFluxOffer(session, offer)
                root.showConfigError(getString(R.string.flux_incoming_file_failed))
            }
        }
        incomingFluxSavePickers[key] = picker
        runCatching { picker.launch(offer.file.name) }.onFailure {
            revokeIncomingFluxOffer(session, offer)
            root.showConfigError(getString(R.string.flux_incoming_file_failed))
        }
    }

    private fun onFluxRemoteUnpair(session: FluxSession, lease: FluxResponderLease? = null) {
        if (lease != null && !ownsFluxResponder(lease)) { session.close(); return }
        runOnUiThread {
            if (lease != null && !ownsFluxResponder(lease)) return@runOnUiThread
            if (FluxInputApprovalState.lost(pendingInputApproval?.session, session)) cancelPendingInputApproval()
            fluxApprovalCredential?.takeIf { it.first === session }?.let { revokeInputApproval(it.first, it.second) }
            if (fluxTouchpadSession === session) closeFluxTouchpad()
            if (pendingFluxCredential?.second === session) pendingFluxCredential = null
        }
        revokeIncomingFluxOffer(session)
        synchronized(fluxLivePeers) {
            FluxSessionAuthorization.revokeIfMapped(fluxLivePeers, session.id, session) {
                fluxFileBridge.revoked(session.id)
                if (lease == null || ownsFluxResponder(lease)) session.removeTrust()
            }
        }
        session.abortMediaWrite() // Never close the raw transport under the peer-map monitor.
        fluxThemeCatalogs.remove(session.id, session)
        fluxThemeFlow.revoke(session.id, session)
        fluxBackgroundGallery.revoke(session)
        runOnUiThread {
            if (lease != null && !ownsFluxResponder(lease)) return@runOnUiThread
            if (openingBackgroundGallery === session) openingBackgroundGallery = null
            cancelPendingFluxTheme(session)
            incomingFluxShareDialogs.remove(session)?.dismiss()
            if (!isDestroyed) root.refreshFluxMenu()
        }
    }

    private fun onFluxDisconnected(session: FluxSession) {
        revokeIncomingFluxOffer(session)
        synchronized(fluxLivePeers) {
            fluxLivePeers.remove(session.id, session)
            pendingFluxPairs.remove(session.id, session)
        }
        fluxFileBridge.disconnected()
        session.close()
        fluxThemeCatalogs.remove(session.id, session)
        fluxThemeFlow.revoke(session.id, session)
        fluxBackgroundGallery.revoke(session)
        runOnUiThread { cancelPendingFluxTheme(session); fluxMediaSelection.remove(session) }
        runOnUiThread {
            if (openingBackgroundGallery === session) openingBackgroundGallery = null
            if (FluxInputApprovalState.lost(pendingInputApproval?.session, session)) cancelPendingInputApproval()
            fluxApprovalCredential?.takeIf { it.first === session }?.let { revokeInputApproval(it.first, it.second) }
            if (fluxTouchpadSession === session) closeFluxTouchpad()
            if (pendingFluxCredential?.second === session) pendingFluxCredential = null
            if (fluxMediaViewSession === session) root.closeFluxMediaMenu()
            incomingFluxPairDialogs.remove(session)?.dismiss()
            incomingFluxShareDialogs.remove(session)?.dismiss()
            outgoingFluxPairDialogs.dismiss(session)
            if (fluxScreenSession === session) fluxScreenMirror?.close()
            if (!isDestroyed) root.refreshFluxMenu()
        }
    }

    private fun showOutgoingFluxPairDialog(session: FluxSession, dialog: AlertDialog) {
        dialog.setOnDismissListener { outgoingFluxPairDialogs.remove(session, dialog) }
        outgoingFluxPairDialogs.replace(session, dialog)
        dialog.show()
    }

    private fun abandonFluxPair(session: FluxSession) {
        synchronized(fluxLivePeers) { pendingFluxPairs.remove(session.id, session) }
        outgoingFluxPairDialogs.dismiss(session)
        session.cancelPair(closeAfter = true)
    }

    private fun watchOutboundFlux(session: FluxSession) {
        runCatching { session.watchPairConnection(
            onUnpair = { onFluxRemoteUnpair(session) },
            onClosed = { onFluxDisconnected(session) },
            onScreen = { reply -> runOnUiThread {
                if (fluxScreenSession === session) fluxScreenMirror?.onReply(reply)
            } },
            onThemeCatalog = { catalog -> runOnUiThread {
                receiveFluxThemeCatalog(session, catalog)
            } },
            onMedia = { body, token -> onFluxMedia(session, body, token) },
            onShare = { share -> onFluxIncomingShare(session, share) },
            onFileOffer = { file -> onFluxIncomingFileOffer(session, file) },
            onInputState = { enabled -> onFluxInputState(session, enabled) },
            onThemeSelected = { result -> onFluxThemeSelected(session, result) },
        ) }.onFailure { onFluxDisconnected(session) }
    }

    private fun connectFlux(endpoint: FluxDiscovery.Endpoint, text: String?) {
        val epoch = fluxSessionEpoch
        val ticket = FluxResponderOwners.registry.beginOutbound()
        if (ticket == null) {
            root.showConfigError(getString(R.string.flux_screen_unavailable))
            return
        }
        io.execute {
            val result = runCatching {
                check(!isDestroyed && !fluxStopping && fluxSessionEpoch === epoch &&
                    FluxScreenStops.currentId() == null) { "Flux session admission changed" }
                val identity = FluxIdentity(applicationContext)
                FluxSession.connect(endpoint, identity)
            }
            runOnUiThread {
                FluxResponderOwners.registry.finishOutbound(ticket)
                if (isDestroyed || fluxStopping || fluxSessionEpoch !== epoch || FluxScreenStops.currentId() != null) {
                    result.getOrNull()?.close()
                    return@runOnUiThread
                }
                result.onFailure { root.showConfigError(getString(R.string.flux_link_failed, it.message ?: "TLS")) }
                result.getOrNull()?.let { session ->
                    if (session.paired) {
                        registerFluxSession(session)
                        watchOutboundFlux(session)
                        showFluxActions(session, text, closeAfter = true)
                    } else {
                        val replaced = synchronized(fluxLivePeers) {
                            check(!fluxStopping && !isDestroyed)
                            pendingFluxPairs.put(session.id, session)?.takeIf { it !== session }
                        }
                        replaced?.let { outgoingFluxPairDialogs.dismiss(it); it.close() }
                        val timestamp = System.currentTimeMillis() / 1000
                        val verification = session.key(timestamp)
                        val pairDialog = themedDialog().setTitle(R.string.flux_pair_title)
                            .setMessage(getString(R.string.flux_pair_compare, session.name, verification))
                            .setNegativeButton(android.R.string.cancel) { _, _ -> abandonFluxPair(session) }
                            .setPositiveButton(R.string.flux_pair_confirm) { _, _ ->
                                io.execute {
                                    val paired = runCatching { session.pair(timestamp) }.getOrDefault(false)
                                    if (paired) watchOutboundFlux(session)
                                    runOnUiThread {
                                        if (!isDestroyed) {
                                            if (paired && pendingFluxPairs[session.id] === session && session.isOpen()) {
                                                val verifyDialog = themedDialog()
                                                    .setTitle(R.string.flux_verify_title)
                                                    .setMessage(getString(R.string.flux_verify_compare, verification))
                                                    .setNegativeButton(android.R.string.cancel) { _, _ -> abandonFluxPair(session) }
                                                    .setPositiveButton(R.string.flux_codes_match) { _, _ ->
                                                        io.execute {
                                                            val pinned = runCatching {
                                                                session.confirmPair { pin ->
                                                                    FluxSessionAuthorization.withPending(
                                                                        fluxLivePeers, pendingFluxPairs, session.id, session) {
                                                                        check(!fluxStopping && !isDestroyed && !distributionPolicy.playStore)
                                                                        pin()
                                                                        pendingFluxPairs.remove(session.id, session)
                                                                        registerFluxSession(session)
                                                                    }
                                                                }
                                                            }.isSuccess
                                                            runOnUiThread {
                                                                if (!isDestroyed && pinned) {
                                                                    showFluxActions(session, text, closeAfter = true)
                                                                }
                                                                else { abandonFluxPair(session); if (!isDestroyed) root.showConfigError(getString(R.string.flux_pair_failed)) }
                                                            }
                                                        }
                                                    }.setOnCancelListener { abandonFluxPair(session) }.create()
                                                showOutgoingFluxPairDialog(session, verifyDialog)
                                            } else { abandonFluxPair(session); root.showConfigError(getString(R.string.flux_pair_failed)) }
                                        } else session.close()
                                    }
                                }
                            }.setOnCancelListener { abandonFluxPair(session) }.create()
                        showOutgoingFluxPairDialog(session, pairDialog)
                    }
                }
            }
        }
    }

    private fun registerFluxSession(session: FluxSession, lease: FluxResponderLease? = null) {
        val epoch = fluxSessionEpoch
        session.bindLiveSession({ !isDestroyed && !distributionPolicy.playStore &&
            fluxSessionEpoch === epoch && (lease == null || ownsFluxResponder(lease)) &&
            fluxLivePeers[session.id] === session }, fluxLivePeers,
            { fluxThemeCatalogs.get(session.id, session)?.themes?.map { it.id }?.toSet() ?: emptySet() })
        val (previous, pending) = synchronized(fluxLivePeers) {
            check(!fluxStopping && !isDestroyed) { "Flux activity is stopping" }
            check(fluxSessionEpoch === epoch && (lease == null || ownsFluxResponder(lease))) {
                "Flux responder ownership changed"
            }
            val pending = pendingFluxPairs.remove(session.id)
            val previous = fluxLivePeers.put(session.id, session)
            previous to pending
        }
        pending?.takeIf { it !== session }?.let { replaced ->
            runOnUiThread { outgoingFluxPairDialogs.dismiss(replaced) }
            replaced.close()
        }
        previous?.takeIf { it !== session }?.let { replaced ->
            revokeIncomingFluxOffer(replaced)
            replaced.disableMedia()
            runOnUiThread { fluxMediaSelection.remove(replaced) }
            fluxThemeCatalogs.remove(session.id, replaced)
            fluxThemeFlow.revoke(session.id, replaced)
            runOnUiThread {
                cancelPendingFluxTheme(replaced)
                if (fluxMediaViewSession === replaced) root.closeFluxMediaMenu()
                incomingFluxPairDialogs.remove(replaced)?.dismiss()
                incomingFluxShareDialogs.remove(replaced)?.dismiss()
                if (fluxScreenSession === replaced) fluxScreenMirror?.close()
            }
            replaced.close()
        }
        session.onWallpaper { body -> receiveWallpaper(session,body) }
        android.util.Log.i("OhmFlux", "Wallpaper session capability=${session.supportsWallpaper}")
        fluxFileBridge.registered()
        runOnUiThread { if (!isDestroyed) root.refreshFluxMenu() }
    }

    internal fun hasLinkedOmarchyPeer(): Boolean =
        currentSettings.omarchyPeer?.token?.isNotBlank() == true

    internal fun fluxMenuEntries(): List<OmarchyMenuEntry> {
        if (distributionPolicy.playStore) return emptyList()
        val captureStop = if (fluxScreenMirror == null) FluxScreenStops.currentId()?.let { id ->
            listOf(OmarchyMenuEntry(NerdGlyph.LINK, getString(R.string.flux_screen_stop),
                action = { FluxScreenStops.stop(id) }))
        }.orEmpty() else emptyList()
        val peers = fluxLivePeers.values.filter { it.paired && it.isOpen() }.sortedWith(compareBy({ it.name }, { it.id }))
        if (peers.isEmpty()) return captureStop + listOf(OmarchyMenuEntry(NerdGlyph.DESKTOP,
            getString(R.string.flux_desktop_menu), action = ::openFluxDesktop))
        val rows = FluxMenuPolicy.placement(peers.map { it.id }, distributionPolicy.playStore)
        return captureStop + if (rows.singleOrNull()?.grouped == false) {
            fluxActionEntries(peers.first { it.id == rows.single().peerId }, null, false)
        } else rows.mapNotNull { row ->
            peers.firstOrNull { it.id == row.peerId }?.let { session ->
                OmarchyMenuEntry(NerdGlyph.DESKTOP, session.name,
                    childrenProvider = {
                        if (fluxLivePeers[session.id] === session && session.paired && session.isOpen())
                            fluxActionEntries(session, null, false) else emptyList()
                    })
            }
        }
    }

    private fun fluxActionEntries(session: FluxSession, initialText: String?, closeAfter: Boolean): List<OmarchyMenuEntry> {
        val screenRunning = fluxScreenSession === session && fluxScreenMirror != null
        val catalogValid = fluxThemeSyncEnabled && session.supportsThemeSelection &&
            fluxThemeCatalogs.get(session.id, session)?.themes?.isNotEmpty() == true
        val wallpaperRows=if(wallpaperAllowed(session) && wallpaperStates[session]!=null)
            listOf(OmarchyMenuEntry(NerdGlyph.LINK,getString(R.string.flux_wallpaper_choose),action={openFluxBackgroundSelector(session)})) else emptyList()
        return wallpaperRows + FluxMenuPolicy.actions(screenRunning, catalogValid, session.supportsMedia,
            session.supportsRemoteInput, session.desktopRemoteInputEnabled,
            session.supportsInputApproval).map { action ->
            val label = when (action) {
                FluxMenuPolicy.Action.SEND_TEXT -> R.string.flux_send
                FluxMenuPolicy.Action.SEND_URL -> R.string.flux_send_url
                FluxMenuPolicy.Action.SEND_CLIPBOARD -> R.string.flux_send_clipboard
                FluxMenuPolicy.Action.PING -> R.string.flux_ping
                FluxMenuPolicy.Action.BATTERY -> R.string.flux_battery
                FluxMenuPolicy.Action.FILE -> R.string.flux_file
                FluxMenuPolicy.Action.NOTIFICATION -> R.string.flux_notification
                FluxMenuPolicy.Action.SCREEN_START -> R.string.flux_screen_start
                FluxMenuPolicy.Action.SCREEN_STOP -> R.string.flux_screen_stop
                FluxMenuPolicy.Action.THEME -> R.string.flux_theme_picker
                FluxMenuPolicy.Action.MEDIA -> R.string.flux_media
                FluxMenuPolicy.Action.REMOTE_INPUT -> R.string.flux_input_title
                FluxMenuPolicy.Action.FORGET -> R.string.flux_forget
            }
            OmarchyMenuEntry(if (action == FluxMenuPolicy.Action.FORGET) NerdGlyph.TRASH else NerdGlyph.LINK,
                getString(label), action = { executeFluxAction(session, action, initialText, closeAfter) })
        }
    }

    private fun showFluxActions(session: FluxSession, initialText: String?, closeAfter: Boolean) {
        if (!fluxAuthorized(session, FluxMenuPolicy.Action.SEND_TEXT)) {
            if (closeAfter) session.close()
            root.showConfigError(getString(R.string.flux_action_failed))
            return
        }
        FluxMenuPolicy.showOrClose(closeAfter, { session.close() }) {
            root.showFluxActionMenu(session.name, fluxActionEntries(session, initialText, closeAfter)) {
                if (closeAfter && wallpaperPickerSession !== session) session.close()
            }
        }
    }

    private fun fluxAuthorized(session: FluxSession, action: FluxMenuPolicy.Action): Boolean =
        FluxMenuPolicy.authorized(session.id, fluxLivePeers[session.id]?.takeIf { it === session }?.id,
            session.paired, session.isOpen(), distributionPolicy.playStore, action,
            fluxThemeCatalogs.get(session.id, session)?.themes?.isNotEmpty() == true,
            fluxScreenSession === session && fluxScreenMirror != null, session.supportsMedia,
            session.supportsRemoteInput, session.desktopRemoteInputEnabled,
            session.supportsInputApproval)

    private fun onFluxInputState(session: FluxSession, status: FluxWire.InputState) {
        runOnUiThread {
            if (isDestroyed || fluxLivePeers[session.id] !== session ||
                session.activeInputRequestId() != status.requestId) return@runOnUiThread
            pendingInputApproval?.takeIf { it.session === session && it.key == status.requestId }?.let { pending ->
                pendingInputApproval = null
                pending.dialog.dismiss()
                if (status.enabled && fluxForeground) startFluxRemoteInput(session, pending.key)
                else if (status.enabled) revokeInputApproval(session, pending.key)
                else root.showConfigError(getString(R.string.flux_input_denied))
            }
            if (!status.enabled) {
                if (fluxTouchpadSession === session) closeFluxTouchpad()
                if (pendingFluxCredential?.second === session) pendingFluxCredential = null
                revokeInputApproval(session, status.requestId)
            }
            root.refreshFluxMenu()
        }
    }

    private fun revokeInputApproval(session: FluxSession, requestId: String) {
        val owner = session to requestId
        if (fluxInputApprovalOwner == owner) fluxInputApprovalOwner = null
        if (fluxApprovalCredential == owner) fluxApprovalCredential = null
        session.cancelInputApproval(requestId)
        io.execute { runCatching { session.requestInputApproval(false, requestId) } }
    }

    private fun cancelPendingInputApproval() {
        val pending = pendingInputApproval ?: return
        pendingInputApproval = null
        pending.dialog.dismiss()
        revokeInputApproval(pending.session, pending.key)
    }

    private fun requestFluxInputApproval(session: FluxSession) {
        if (pendingInputApproval != null || pendingFluxCredential != null) return
        if (!fluxForeground || !fluxAuthorized(session, FluxMenuPolicy.Action.REMOTE_INPUT) ||
            !session.supportsInputApproval) return
        val key = UUID.randomUUID().toString()
        if (runCatching { session.beginInputApproval(key) }.isFailure) {
            root.showConfigError(getString(R.string.flux_input_unavailable))
            return
        }
        val dialog = themedDialog().setTitle(R.string.flux_input_title)
            .setMessage(getString(R.string.flux_input_waiting, session.name))
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelPendingInputApproval() }
            .setOnCancelListener { cancelPendingInputApproval() }.create()
        pendingInputApproval = PendingInputApproval(session, key, dialog)
        dialog.show()
        io.execute {
            val sent = runCatching { session.requestInputApproval(true, key) }.isSuccess
            if (!sent) runOnUiThread {
                if (pendingInputApproval?.key == key) {
                    cancelPendingInputApproval()
                    if (!isDestroyed) root.showConfigError(getString(R.string.flux_input_unavailable))
                }
            }
        }
        mainHandler.postDelayed({
            if (pendingInputApproval?.key == key) {
                cancelPendingInputApproval()
                if (!isDestroyed) root.showConfigError(getString(R.string.flux_input_timeout))
            }
        }, 25_000)
    }

    private fun startFluxRemoteInput(session: FluxSession, requestId: String) {
        if (!fluxForeground || !fluxAuthorized(session, FluxMenuPolicy.Action.REMOTE_INPUT)) {
            root.showConfigError(getString(R.string.flux_input_unavailable))
            revokeInputApproval(session, requestId)
            return
        }
        if (!session.desktopRemoteInputEnabled || session.activeInputRequestId() != requestId) {
            root.showConfigError(getString(R.string.flux_input_unavailable))
            revokeInputApproval(session, requestId)
            return
        }
        if (pendingFluxCredential != null) {
            revokeInputApproval(session, requestId)
            return
        }
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure != true) {
            root.showConfigError(getString(R.string.flux_input_lock_required))
            revokeInputApproval(session, requestId)
            return
        }
        val authenticators = androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (androidx.biometric.BiometricManager.from(this).canAuthenticate(authenticators) !=
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            root.showConfigError(getString(R.string.flux_input_lock_required))
            revokeInputApproval(session, requestId)
            return
        }
        closeFluxTouchpad()
        val key = UUID.randomUUID().toString()
        pendingFluxCredential = key to session
        fluxApprovalCredential = session to requestId
        val prompt = androidx.biometric.BiometricPrompt(this,
            androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    if (pendingFluxCredential != (key to session)) return
                    pendingFluxCredential = null
                    if (isDestroyed || !fluxForeground || !fluxAuthorized(session, FluxMenuPolicy.Action.REMOTE_INPUT) ||
                        !session.desktopRemoteInputEnabled || session.activeInputRequestId() != requestId) {
                        revokeInputApproval(session, requestId)
                        return
                    }
                    val token = runCatching { session.authorizeRemoteInput() }.getOrNull()
                    if (token != null) {
                        showFluxTouchpad(session, token)
                        fluxApprovalCredential = null
                        if (fluxTouchpadView != null && fluxTouchpadSession === session)
                            fluxInputApprovalOwner = session to requestId
                        else revokeInputApproval(session, requestId)
                    } else {
                        revokeInputApproval(session, requestId)
                        root.showConfigError(getString(R.string.flux_input_failed))
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (pendingFluxCredential == (key to session)) {
                        pendingFluxCredential = null
                        revokeInputApproval(session, requestId)
                    }
                }
            })
        runCatching {
            prompt.authenticate(androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.flux_input_title))
                .setAllowedAuthenticators(authenticators).build())
        }.onFailure {
            pendingFluxCredential = null
            revokeInputApproval(session, requestId)
            root.showConfigError(getString(R.string.flux_input_failed))
        }
    }

    private fun showFluxTouchpad(session: FluxSession, token: Long) {
        if (!fluxForeground || !fluxAuthorized(session, FluxMenuPolicy.Action.REMOTE_INPUT) ||
            !session.desktopRemoteInputEnabled || isDestroyed) {
            session.closeRemoteInputView()
            return
        }
        closeFluxTouchpad()
        val labels = FluxTouchpadView.Labels(
            title = getString(R.string.flux_input_title), touchpad = getString(R.string.flux_input_touchpad),
            scroll = getString(R.string.flux_input_scroll), left = getString(R.string.flux_input_left),
            double = getString(R.string.flux_input_double), right = getString(R.string.flux_input_right),
            middle = getString(R.string.flux_input_middle), textHint = getString(R.string.flux_input_text),
            send = getString(R.string.flux_input_send), close = getString(R.string.flux_input_close),
            enter = getString(R.string.flux_input_enter), backspace = getString(R.string.flux_input_backspace),
            tab = getString(R.string.flux_input_tab), escape = getString(R.string.flux_input_escape),
            up = getString(R.string.flux_input_up), down = getString(R.string.flux_input_down),
            arrowLeft = getString(R.string.flux_input_arrow_left),
            arrowRight = getString(R.string.flux_input_arrow_right),
        )
        val palette = OmarchyThemePalette.fromSettings(currentSettings.raw)
            ?: OmarchyThemePalette("Omarchy", OmarchyThemeMode.DARK, emptyMap())
        fluxTouchpadSession = session
        fluxInputToken = token
        val queue = FluxRemoteInputQueue(
            send = { action -> session.sendRemoteInput(token, action) },
            onRevoke = {
                session.closeRemoteInputView()
                runOnUiThread {
                    if (!isDestroyed && fluxTouchpadSession === session && fluxInputToken == token) {
                        closeFluxTouchpad()
                        root.showConfigError(getString(R.string.flux_input_failed))
                    }
                }
            },
            onAbort = {},
        )
        if (!queue.accepted) {
            session.closeRemoteInputView()
            fluxTouchpadSession = null
            fluxInputToken = null
            root.showConfigError(getString(R.string.flux_input_failed))
            return
        }
        fluxInputSendAction = { action ->
            if (!queue.offer(action)) {
                closeFluxTouchpad()
                root.showConfigError(getString(R.string.flux_input_failed))
            }
        }
        fluxInputCloseSender = queue::close
        val view = FluxTouchpadView(this, palette, labels,
            canSend = { fluxForeground && fluxInputToken == token && fluxInputSendAction != null &&
                fluxAuthorized(session, FluxMenuPolicy.Action.REMOTE_INPUT) && session.desktopRemoteInputEnabled },
            sendAction = { action -> fluxInputSendAction?.invoke(action) },
            onClosed = ::closeFluxTouchpad)
        fluxTouchpadView = view
        runCatching {
            root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
            view.requestFocus()
        }.onFailure {
            closeFluxTouchpad()
            root.showConfigError(getString(R.string.flux_input_failed))
        }
    }

    private fun closeFluxTouchpad() {
        if (closingFluxTouchpad) return
        closingFluxTouchpad = true
        try {
            val view = fluxTouchpadView
            val session = fluxTouchpadSession
            view?.close() // Give a held pointer one last chance to release while still authorized.
            fluxTouchpadView = null
            fluxTouchpadSession = null
            fluxInputToken = null
            fluxInputSendAction = null
            val closeSender = fluxInputCloseSender
            fluxInputCloseSender = null
            view?.let { root.removeView(it) }
            if (closeSender == null) session?.closeRemoteInputView() else closeSender()
            fluxInputApprovalOwner?.let { owner ->
                fluxInputApprovalOwner = null
                revokeInputApproval(owner.first, owner.second)
            }
        } finally { closingFluxTouchpad = false }
    }

    private fun executeFluxAction(session: FluxSession, action: FluxMenuPolicy.Action,
                                  initialText: String?, closeAfter: Boolean) {
        if (!fluxAuthorized(session, action)) {
            root.showConfigError(getString(R.string.flux_action_failed))
            return
        }
        when (action) {
                FluxMenuPolicy.Action.FORGET -> {
                themedDialog().setTitle(R.string.flux_forget)
                    .setNegativeButton(android.R.string.cancel) { _, _ -> if (closeAfter) session.close() }
                    .setPositiveButton(R.string.flux_forget) { _, _ ->
                        val revoked = runCatching {
                            check(!fluxStopping && !isDestroyed && !distributionPolicy.playStore)
                            FluxSessionAuthorization.removeMapped(fluxLivePeers, session.id, session) {
                                fluxFileBridge.revoked(session.id)
                                session.removeTrust()
                            }
                        }
                        session.abortMediaWrite()
                        if (revoked.isSuccess) {
                            if (FluxInputApprovalState.lost(pendingInputApproval?.session, session)) cancelPendingInputApproval()
                            fluxApprovalCredential?.takeIf { it.first === session }?.let { revokeInputApproval(it.first, it.second) }
                            if (fluxTouchpadSession === session) closeFluxTouchpad()
                            if (pendingFluxCredential?.second === session) pendingFluxCredential = null
                            revokeIncomingFluxOffer(session)
                            if (fluxMediaViewSession === session) root.closeFluxMediaMenu()
                            incomingFluxShareDialogs.remove(session)?.dismiss()
                            fluxMediaSelection.remove(session)
                            fluxThemeCatalogs.remove(session.id, session)
                            if (fluxScreenSession === session) fluxScreenMirror?.close()
                            root.refreshFluxMenu()
                            io.execute { session.unpair() }
                        } else root.showConfigError(getString(R.string.flux_action_failed))
                    }
                    .setOnCancelListener { if (closeAfter) session.close() }.show()
                }
                FluxMenuPolicy.Action.SEND_TEXT -> if (initialText != null) sendFluxText(session, initialText, closeAfter)
                      else promptFluxText(session, closeAfter, url = false)
                FluxMenuPolicy.Action.SEND_URL -> promptFluxText(session, closeAfter, url = true)
                FluxMenuPolicy.Action.SEND_CLIPBOARD -> {
                    val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
                    if (clip.isNullOrBlank()) {
                        root.showConfigError(getString(R.string.flux_clipboard_empty))
                        if (closeAfter) session.close()
                    } else sendFluxAction(session, closeAfter) { it.sendClipboard(clip) }
                }
                FluxMenuPolicy.Action.PING -> sendFluxAction(session, closeAfter) { it.sendPing() }
                FluxMenuPolicy.Action.BATTERY -> {
                    val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val status = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                    if (level < 0 || scale <= 0) {
                        root.showConfigError(getString(R.string.flux_action_failed))
                        if (closeAfter) session.close()
                    } else sendFluxAction(session, closeAfter) {
                        it.sendBattery((100L * level / scale).toInt().coerceIn(0, 100),
                            status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == android.os.BatteryManager.BATTERY_STATUS_FULL)
                    }
                }
                FluxMenuPolicy.Action.FILE -> {
                    val launch = runCatching { fluxFileBridge.launch(session, closeAfter) }.getOrNull()
                    if (launch == null) {
                        root.showConfigError(getString(R.string.flux_file_busy))
                        fluxFileBridge.reject(session, closeAfter)
                    } else {
                        runCatching { registerFluxDocumentPicker(launch).launch(arrayOf("*/*")) }.onFailure {
                            fluxFileBridge.returned(launch, null)
                            root.showConfigError(getString(R.string.flux_file_failed))
                        }
                    }
                }
                FluxMenuPolicy.Action.NOTIFICATION -> showFluxNotificationPicker(session, closeAfter)
                FluxMenuPolicy.Action.SCREEN_START -> startFluxScreenShare(session)
                FluxMenuPolicy.Action.SCREEN_STOP -> if (fluxScreenSession === session) fluxScreenMirror?.close()
                FluxMenuPolicy.Action.THEME -> showFluxThemePicker(session, closeAfter)
                FluxMenuPolicy.Action.MEDIA -> showFluxMediaConsent(session, closeAfter)
                FluxMenuPolicy.Action.REMOTE_INPUT -> requestFluxInputApproval(session)
            }
    }

    private val fluxMediaSelection = mutableMapOf<FluxSession, String>()
    private var fluxMediaViewSession: FluxSession? = null
    private var fluxMediaControlDialog: AlertDialog? = null

    private fun onFluxMedia(session: FluxSession, body: org.json.JSONObject, token: Long) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            val accepted = runCatching {
                FluxMediaGate.withCurrent(fluxLivePeers, session.id, session,
                    session.paired && session.isOpen() && session.supportsMedia,
                    distributionPolicy.playStore, session.mediaConsent) {
                    check(session.acceptsMedia(token) && session.media.receive(body))
                }
            }.isSuccess
            if (accepted && body.has("playerList")) {
                session.media.players().forEach { player ->
                    sendFluxMedia(session, FluxMediaProtocol.refresh(player.name), token)
                }
            }
            if (accepted && fluxMediaViewSession === session) root.updateFluxMediaEntries(fluxMediaEntries(session))
        }
    }

    private fun showFluxMediaConsent(session: FluxSession, closeAfter: Boolean) {
        if (!fluxAuthorized(session, FluxMenuPolicy.Action.MEDIA)) return
        val entries = listOf(OmarchyMenuEntry(NerdGlyph.LINK, getString(R.string.flux_media_enable), action = {
            val enabled = runCatching {
                FluxMediaGate.withCurrent(fluxLivePeers, session.id, session,
                    session.paired && session.isOpen(), distributionPolicy.playStore) { session.enableMedia() }
            }.isSuccess
            if (enabled) {
                sendFluxMedia(session, FluxMediaProtocol.playerList())
                val token = session.mediaToken()
                mainHandler.postDelayed({
                    if (!isDestroyed && session.acceptsMedia(token)) showFluxMedia(session, closeAfter)
                }, 450)
            } else root.showConfigError(getString(R.string.flux_media_unavailable))
        }))
        if (!root.showFluxActionMenu(getString(R.string.flux_media), entries) {
            session.disableMedia()
            if (closeAfter) session.close()
        } && closeAfter) session.close()
    }

    private fun showFluxMedia(session: FluxSession, closeAfter: Boolean) {
        if (!session.mediaConsent || !fluxAuthorized(session, FluxMenuPolicy.Action.MEDIA)) {
            session.disableMedia()
            return
        }
        fluxMediaViewSession = session
        if (!root.showFluxActionMenu(getString(R.string.flux_media), fluxMediaEntries(session), mediaView = true) {
            fluxMediaControlDialog?.dismiss()
            fluxMediaControlDialog = null
            if (fluxMediaViewSession === session) fluxMediaViewSession = null
            session.disableMedia()
            fluxMediaSelection.remove(session)
            if (closeAfter) session.close()
        }) {
            fluxMediaViewSession = null
            session.disableMedia()
            if (closeAfter) session.close()
        }
    }

    private fun fluxMediaEntries(session: FluxSession): List<OmarchyMenuEntry> {
        val players = session.media.players()
        val selected = players.firstOrNull { it.name == fluxMediaSelection[session] }
            ?: players.firstOrNull { it.playing } ?: players.firstOrNull()
        val nowPlaying = selected?.let {
            val track = it.nowPlaying.ifBlank { it.title.ifBlank { getString(R.string.flux_media_idle) } }
            "${it.name}: ${track.take(100)} (${it.position / 1000}s / ${it.length / 1000}s)"
        } ?: getString(R.string.flux_media_no_players)
        return buildList {
            add(OmarchyMenuEntry(NerdGlyph.DESKTOP, nowPlaying, staysOpen = true))
            add(OmarchyMenuEntry(NerdGlyph.LINK, getString(R.string.flux_media_refresh), action = {
                sendFluxMedia(session, FluxMediaProtocol.playerList())
            }, staysOpen = true))
            players.forEach { player ->
                add(OmarchyMenuEntry(NerdGlyph.DESKTOP, player.name, action = {
                    fluxMediaSelection[session] = player.name
                    root.updateFluxMediaEntries(fluxMediaEntries(session))
                    sendFluxMedia(session, FluxMediaProtocol.refresh(player.name))
                }, staysOpen = true))
            }
            selected?.let { player ->
                fun control(label: Int, command: String) = add(OmarchyMenuEntry(NerdGlyph.LINK,
                    getString(label), action = {
                        sendFluxMedia(session, FluxMediaProtocol.action(player.name, command))
                    }, staysOpen = true))
                if (player.playing && player.canPause) control(R.string.flux_media_pause, "Pause")
                else if (!player.playing && player.canPlay) control(R.string.flux_media_play, "Play")
                if (player.canGoPrevious) control(R.string.flux_media_previous, "Previous")
                if (player.canGoNext) control(R.string.flux_media_next, "Next")
                if (player.canSeek && player.length > 0) add(OmarchyMenuEntry(NerdGlyph.LINK,
                    getString(R.string.flux_media_seek), action = {
                        showFluxMediaSlider(session, player, volume = false)
                    }, staysOpen = true))
                if (player.volume != null) add(OmarchyMenuEntry(NerdGlyph.LINK,
                    getString(R.string.flux_media_volume), action = {
                        showFluxMediaSlider(session, player, volume = true)
                    }, staysOpen = true))
            }
        }
    }

    private fun showFluxMediaSlider(session: FluxSession, player: FluxPlayer, volume: Boolean) {
        if (fluxMediaViewSession !== session || !session.mediaConsent ||
            !fluxAuthorized(session, FluxMenuPolicy.Action.MEDIA)) return
        val latest = session.media.players().firstOrNull { it.name == player.name } ?: return
        if (volume && latest.volume == null || !volume && (!latest.canSeek || latest.length <= 0)) return
        fluxMediaControlDialog?.dismiss()
        val max = if (volume) 100 else 1000
        val initial = if (volume) latest.volume!! else
            ((latest.position.coerceIn(0, latest.length) / latest.length.toDouble()) * max).toInt().coerceIn(0, max)
        val caption = TextView(this)
        val slider = SeekBar(this).apply { this.max = max; progress = initial }
        fun updateCaption(progress: Int) {
            caption.text = if (volume) getString(R.string.flux_media_volume_value, progress)
            else getString(R.string.flux_media_seek_value,
                FluxMediaControls.positionAtProgress(latest.length, progress, max) / 1000,
                latest.length / 1000)
        }
        updateCaption(initial)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = updateCaption(progress)
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(caption)
            addView(slider)
        }
        val dialog = themedDialog().setTitle(if (volume) R.string.flux_media_volume else R.string.flux_media_seek)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (fluxMediaViewSession === session && session.mediaConsent &&
                    fluxAuthorized(session, FluxMenuPolicy.Action.MEDIA)) {
                    val payload = runCatching {
                        if (volume) FluxMediaControls.volume(session.media, player.name, slider.progress)
                        else FluxMediaControls.seek(session.media, player.name,
                            FluxMediaControls.positionAtProgress(latest.length, slider.progress, max))
                    }.getOrNull()
                    if (payload != null) sendFluxMedia(session, payload)
                    else root.showConfigError(getString(R.string.flux_media_unavailable))
                }
            }.create()
        fluxMediaControlDialog = dialog
        dialog.setOnDismissListener { if (fluxMediaControlDialog === dialog) fluxMediaControlDialog = null }
        dialog.show()
    }

    private fun sendFluxMedia(session: FluxSession, body: org.json.JSONObject,
                              token: Long = session.mediaToken()) {
        io.execute {
            val wrote = runCatching { session.sendMedia(body, token) }.isSuccess
            if (wrote && FluxMediaControls.needsRefresh(body)) runCatching {
                session.sendMedia(FluxMediaProtocol.refresh(body.getString("player")), token)
            }
            if (!wrote) runOnUiThread {
                if (!isDestroyed && fluxMediaViewSession === session) {
                    root.showConfigError(getString(R.string.flux_media_unavailable))
                }
            }
        }
    }

    private fun receiveFluxThemeCatalog(session: FluxSession, catalog: FluxThemeCatalog) {
        if (!fluxThemeSyncEnabled || isDestroyed || distributionPolicy.playStore || !session.paired || !session.isOpen() ||
            fluxLivePeers[session.id] !== session) return
        fluxThemeCatalogs.put(session.id, session, catalog)
        fluxThemeFlow.receive(session.id, session, catalog)
        root.refreshFluxMenu()
    }

    private fun cancelPendingFluxTheme(session: FluxSession) {
        val pending = pendingFluxThemes[session.id]?.takeIf { it.session === session } ?: return
        pendingFluxThemes.remove(session.id)
        pending.active = false
        pending.timer?.let(mainHandler::removeCallbacks)
        fluxThemeFlow.timeout(session.id, session)
        session.cancelThemeSelection()
    }

    private fun showFluxThemeError(message: Int) {
        if (!isDestroyed) themedDialog().setTitle(R.string.flux_theme_picker)
            .setMessage(message).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun finishFluxThemeSelection(session: FluxSession, result: FluxThemeSelection.Result) {
        val pending = pendingFluxThemes[session.id]?.takeIf { it.session === session } ?: return
        pending.acknowledged = true
        pending.requestId = result.requestId
        if (!fluxThemeFlow.ack(session.id, session, result)) {
            pendingFluxThemes.remove(session.id)
            pending.active = false
            pending.timer?.let(mainHandler::removeCallbacks)
            showFluxThemeError(R.string.flux_theme_failed)
            pending.closure.finish()
        }
    }

    private fun showFluxThemePicker(session: FluxSession, closeAfter: Boolean) {
        val closure = FluxThemePickerClosure(session, closeAfter) { original: FluxSession -> original.close() }
        val catalog = fluxThemeCatalogs.get(session.id, session)?.takeIf {
            fluxThemeSyncEnabled && fluxLivePeers[session.id] === session && session.paired && session.isOpen() &&
                session.supportsThemeSelection && !distributionPolicy.playStore
        }
        if (catalog == null) {
            showFluxThemeError(R.string.flux_theme_unavailable)
            closure.finish()
            return
        }
        val labels = catalog.themes.map { theme ->
            if (theme.id == catalog.current) getString(R.string.flux_theme_current, theme.label) else theme.label
        }.toTypedArray()
        var selected = catalog.themes.indexOfFirst { it.id == catalog.current }
        themedDialog().setTitle(R.string.flux_theme_picker)
            .setSingleChoiceItems(labels, selected) { _, index -> selected = index }
            .setNegativeButton(android.R.string.cancel) { _, _ -> closure.finish() }
            .setPositiveButton(R.string.flux_theme_apply) { _, _ ->
                selectFluxTheme(session, catalog, catalog.themes.getOrNull(selected)?.id, closeAfter, closure)
            }.setOnCancelListener { closure.finish() }.show()
    }

    /** The visual selector's Apply gesture authorizes this same session-owned request. */
    private fun selectFluxTheme(
        session: FluxSession,
        catalog: FluxThemeCatalog,
        id: String?,
        closeAfter: Boolean,
        closure: FluxThemePickerClosure<FluxSession> = FluxThemePickerClosure(session, closeAfter) {
            original -> original.close()
        },
    ) {
        if (pendingFluxThemes.containsKey(session.id)) {
            showFluxThemeError(R.string.flux_theme_unavailable)
            if (closeAfter) cancelPendingFluxTheme(session)
            closure.finish()
        } else if (id == null || catalog.themes.none { it.id == id } ||
            fluxThemeCatalogs.get(session.id, session) !== catalog ||
            fluxLivePeers[session.id] !== session || !session.paired || !session.isOpen() ||
            !session.supportsThemeSelection || distributionPolicy.playStore || !fluxThemeSyncEnabled) {
            showFluxThemeError(R.string.flux_theme_unavailable)
            closure.finish()
        } else {
            val pending = PendingFluxTheme(session, closure)
            pendingFluxThemes[session.id] = pending
            val timer = Runnable {
                if (pendingFluxThemes[session.id] === pending) {
                    cancelPendingFluxTheme(session)
                    showFluxThemeError(if (pending.acknowledged) R.string.flux_theme_apply_timeout
                        else R.string.flux_theme_timeout)
                    closure.finish()
                }
            }
            pending.timer = timer
            mainHandler.postDelayed(timer, 15_000L)
            io.execute {
                val sent = fluxThemeFlow.request(session.id, session, catalog, id) { pending.active }
                if (!sent) runOnUiThread {
                    if (pendingFluxThemes[session.id] === pending) {
                        cancelPendingFluxTheme(session)
                        showFluxThemeError(R.string.flux_theme_failed)
                        closure.finish()
                    }
                }
            }
        }
    }

    private fun startFluxScreenShare(session: FluxSession) {
        if (distributionPolicy.playStore || fluxScreenMirror != null ||
            !FluxResponderOwners.registry.canStartCapture() ||
            omarchyScreenConsentPending || screenCapture.isRunning() ||
            fluxLivePeers[session.id] !== session || !session.paired || !session.isOpen()) {
            root.showConfigError(getString(R.string.flux_screen_unavailable))
            return
        }
        var mirror: FluxScreenMirror? = null
        mirror = FluxScreenMirror(applicationContext, FluxIdentity(applicationContext), session,
            currentSession = { fluxLivePeers[session.id] }) { reason ->
            if (fluxScreenMirror === mirror) {
                fluxScreenMirror = null
                fluxScreenSession = null
                if (reason != null && !isDestroyed)
                    root.showConfigError(getString(R.string.flux_screen_failed))
            }
            FluxResponderOwners.registry.reconsider()
        }
        val active = checkNotNull(mirror)
        fluxScreenSession = session
        fluxScreenMirror = active
        runCatching { fluxScreenConsent.launch(active.consentIntent()) }.onFailure {
            active.close()
            root.showConfigError(getString(R.string.flux_screen_failed))
        }
    }

    private fun showFluxNotificationPicker(session: FluxSession, closeAfter: Boolean) {
        val listener = OhmNotificationListenerService.instance
        if (listener == null) {
            themedDialog().setTitle(R.string.flux_notification)
                .setMessage(R.string.flux_notification_access)
                .setNegativeButton(android.R.string.cancel) { _, _ -> if (closeAfter) session.close() }
                .setPositiveButton(R.string.flux_open_settings) { _, _ ->
                    if (closeAfter) session.close()
                    openNotificationAccessSettings()
                }.setOnCancelListener { if (closeAfter) session.close() }.show()
            return
        }
        val notifications = runCatching { listener.activeNotifications.orEmpty().asSequence()
            .filter { it.packageName != packageName }
            .filter {
                val flags = it.notification.flags
                flags and (android.app.Notification.FLAG_ONGOING_EVENT or
                    android.app.Notification.FLAG_FOREGROUND_SERVICE or
                    android.app.Notification.FLAG_GROUP_SUMMARY or
                    android.app.Notification.FLAG_LOCAL_ONLY) == 0 &&
                    it.notification.visibility != android.app.Notification.VISIBILITY_SECRET
            }
            .mapNotNull { sbn ->
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty().take(512)
                val text = (extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString().orEmpty().take(2048)
                if (title.isBlank() && text.isBlank()) null else Triple(sbn, title, text)
            }.sortedByDescending { it.first.postTime }.take(25).toList() }.getOrDefault(emptyList())
        if (notifications.isEmpty()) {
            root.showConfigError(getString(R.string.flux_notification_empty))
            if (closeAfter) session.close()
            return
        }
        val labels = notifications.map { (sbn, title, _) -> "${sbn.packageName}: $title" }.toTypedArray()
        themedDialog().setTitle(R.string.flux_notification)
            .setItems(labels) { _, index ->
                val (sbn, title, text) = notifications[index]
                themedDialog().setTitle(R.string.flux_notification_confirm)
                    .setMessage(getString(R.string.flux_notification_preview, session.name,
                        sbn.packageName, title, text))
                    .setNegativeButton(android.R.string.cancel) { _, _ -> if (closeAfter) session.close() }
                    .setPositiveButton(R.string.flux_notification_send) { _, _ ->
                        sendFluxAction(session, closeAfter) {
                            it.sendNotification(sbn.key, sbn.packageName, title, text, sbn.postTime)
                        }
                    }.setOnCancelListener { if (closeAfter) session.close() }.show()
            }.setNegativeButton(android.R.string.cancel) { _, _ -> if (closeAfter) session.close() }
            .setOnCancelListener { if (closeAfter) session.close() }.show()
    }

    private fun promptFluxText(session: FluxSession, closeAfter: Boolean, url: Boolean) {
        val input = EditText(this).apply {
            hint = getString(if (url) R.string.flux_url_hint else R.string.peer_share_hint)
            setPadding(32, 24, 32, 24)
            maxLines = if (url) 2 else 5
        }
        themedDialog().setTitle(if (url) R.string.flux_send_url else R.string.flux_send)
            .setView(input)
            .setNegativeButton(android.R.string.cancel) { _, _ -> if (closeAfter) session.close() }
            .setPositiveButton(if (url) R.string.flux_send_url else R.string.flux_send) { _, _ ->
                val value = input.text.toString().trim()
                if (url && (Uri.parse(value).scheme?.lowercase() !in setOf("http", "https") ||
                            Uri.parse(value).host.isNullOrBlank())) {
                    root.showConfigError(getString(R.string.flux_invalid_url))
                    if (closeAfter) session.close()
                } else if (value.isBlank() || value.toByteArray(Charsets.UTF_8).size > 64 * 1024) {
                    root.showConfigError(getString(R.string.peer_share_invalid))
                    if (closeAfter) session.close()
                } else if (url) sendFluxAction(session, closeAfter) { it.sendUrl(value) }
                else sendFluxText(session, value, closeAfter)
            }.setOnCancelListener { if (closeAfter) session.close() }.show()
    }

    private fun sendFluxAction(session: FluxSession, closeAfter: Boolean, action: (FluxSession) -> Unit) {
        io.execute {
            val wrote = runCatching {
                check(fluxAuthorized(session, FluxMenuPolicy.Action.SEND_TEXT)) { "Flux desktop disconnected" }
                action(session)
            }.isSuccess
            if (closeAfter || !wrote) session.close()
            runOnUiThread {
                if (!isDestroyed) Toast.makeText(this,
                    getString(if (wrote) R.string.flux_action_written else R.string.flux_action_failed),
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sendFluxText(session: FluxSession, text: String, closeAfter: Boolean = true) {
        io.execute {
            val wrote = runCatching {
                check(fluxAuthorized(session, FluxMenuPolicy.Action.SEND_TEXT)) { "Flux desktop disconnected" }
                session.sendText(text)
            }.isSuccess
            if (closeAfter || !wrote) session.close()
            runOnUiThread {
                if (!isDestroyed) Toast.makeText(this,
                    getString(if (wrote) R.string.flux_written else R.string.flux_share_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun showOmarchyThemeSelector() {
        val session = fluxLivePeers.values.firstOrNull {
            fluxThemeSyncEnabled && it.supportsThemeSelection && it.paired && it.isOpen() &&
                !distributionPolicy.playStore && fluxThemeCatalogs.get(it.id, it)?.themes?.isNotEmpty() == true
        }
        val fluxCatalog = session?.let { fluxThemeCatalogs.get(it.id, it) }
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
        val choices = fluxCatalog?.themes?.map { theme ->
            // Keep the existing card/asset for the exact installed ID. Flux
            // supplies the current desktop palette; custom IDs use the same
            // selector's placeholder until a matching preview is available.
            (themes[theme.id] ?: OmarchyThemeChoice(theme.id, theme.label, "")).copy(
                id = theme.id,
                label = theme.label,
                palette = theme.palette,
            )
        } ?: themes.values.toList()
        if (choices.isEmpty()) {
            root.showConfigError(getString(R.string.theme_selector_unavailable))
            return
        }
        val current = fluxCatalog?.current ?: OmarchyThemePalette.fromSettings(currentSettings.raw)?.name
            ?: synced?.current
            ?: bundled.current
        val catalog = OmarchyThemeCatalog(current, choices)
        root.showOmarchyThemeSelector(
            catalog = catalog,
            previewLoader = ::loadSyncedThemePreview,
            onApply = { choice -> applyLocalThemeSelection(choice, session, fluxCatalog) },
        )
    }

    fun showOmarchyBackgroundSelector() {
        fluxLivePeers.values.firstOrNull {
            wallpaperAllowed(it) && wallpaperStates[it] != null
        }?.let { session ->
            openFluxBackgroundSelector(session)
            return
        }
        syncedBackgroundCatalog?.takeIf { it.backgrounds.isNotEmpty() }?.let { catalog ->
            showBackgroundCatalog(catalog, backgroundSyncStore::loadPreview)
            return
        }
        val peer = currentSettings.omarchyPeer ?: run {
            val theme = OmarchyThemePalette.fromSettings(currentSettings.raw)?.name
            val backgrounds = bundledThemeCatalog.themes.filter {
                it.id == theme && it.backgroundPreviewPath?.startsWith("asset://") == true
            }.map { it.copy(previewPath = it.backgroundPreviewPath.orEmpty()) }
            showBackgroundCards(OmarchyThemeCatalog(backgrounds.firstOrNull()?.id.orEmpty(), backgrounds),
                ::loadSyncedThemePreview, { choice ->
                    choice.backgroundPreviewPath?.let(::materializeBundledBackground)?.let { path ->
                        applyAndPersistLocalBackground(path, publishToFlux = false)
                    }
                }) { chooseCustomBackground() }
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
                showBackgroundCards(
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
                    onCustom = { chooseCustomBackground() },
                )
            }
        }
    }

    private fun showBackgroundCatalog(
        catalog: OmarchyBackgroundCatalog,
        previewLoader: (OmarchyBackgroundChoice) -> ByteArray?,
    ) {
        val backgroundsById = catalog.backgrounds.associateBy { it.id }
        showBackgroundCards(
            catalog = OmarchyThemeCatalog(
                current = catalog.current.id,
                themes = catalog.backgrounds.map { background ->
                    OmarchyThemeChoice(background.id, background.label, background.previewPath.orEmpty())
                },
            ),
            previewLoader = { choice -> backgroundsById[choice.id]?.let(previewLoader) },
            onApply = { choice -> applyLocalBackgroundSelection(backgroundsById.getValue(choice.id)) },
            onCustom = { chooseCustomBackground() },
        )
    }

    private fun showFluxBackgroundCatalog(session: FluxSession, catalog: FluxBackgroundGallery.Catalog) {
        val backgrounds = catalog.items.associateBy { it.id }
        showBackgroundCards(
            OmarchyThemeCatalog(catalog.current, catalog.items.map {
                OmarchyThemeChoice(it.id, it.label, "flux-background://${it.id}")
            }),
            previewLoader = { backgrounds[it.id]?.preview },
            onApply = { choice -> io.execute {
                if (!fluxBackgroundGallery.select(session, catalog, choice.id)) runOnUiThread {
                    if (!isDestroyed) root.showConfigError(getString(R.string.background_selector_apply_failed))
                }
            } },
            onCustom = { chooseCustomBackground(session) },
        )
    }

    private fun openFluxBackgroundSelector(session: FluxSession) {
        if (!wallpaperAllowed(session)) return
        openingBackgroundGallery = session
        Toast.makeText(this, R.string.background_selector_loading, Toast.LENGTH_SHORT).show()
        io.execute {
            if (!fluxBackgroundGallery.request(session)) runOnUiThread {
                if (openingBackgroundGallery === session && wallpaperAllowed(session)) {
                    openingBackgroundGallery = null
                    root.showConfigError(getString(R.string.background_selector_unavailable))
                }
            }
        }
    }

    private fun showBackgroundCards(
        catalog: OmarchyThemeCatalog,
        previewLoader: (OmarchyThemeChoice) -> ByteArray?,
        onApply: (OmarchyThemeChoice) -> Unit,
        onCustom: () -> Unit,
    ) {
        var customId = "ohm-custom-background"
        while (catalog.themes.any { it.id == customId }) customId += "_"
        val custom = OmarchyThemeChoice(customId, getString(R.string.background_selector_custom),
            OmarchyThemeSelectorOverlay.CUSTOM_BACKGROUND_PREVIEW)
        root.showOmarchyThemeSelector(
            catalog = OmarchyThemeCatalog(catalog.current.ifEmpty { customId }, catalog.themes + custom),
            previewLoader = { if (it.id == customId) null else previewLoader(it) },
            onApply = { if (it.id == customId) onCustom() else onApply(it) },
        )
    }

    private fun chooseCustomBackground(session: FluxSession? = null) {
        if (session != null) {
            if (!wallpaperAllowed(session) || wallpaperStates[session] == null) {
                root.showConfigError(getString(R.string.background_selector_apply_failed))
                return
            }
            chooseFluxWallpaper(session, closeAfter = false)
            return
        }
        wallpaperPickerSession = null
        localWallpaperPicker = true
        try { wallpaperPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }
        catch (_: Exception) {
            localWallpaperPicker = false
            root.showConfigError(getString(R.string.background_selector_apply_failed))
        }
    }

    private fun applyLocalThemeSelection(
        choice: OmarchyThemeChoice,
        fluxSession: FluxSession? = null,
        fluxCatalog: FluxThemeCatalog? = null,
    ) {
        if (fluxSession != null && fluxCatalog != null) {
            selectFluxTheme(fluxSession, fluxCatalog, choice.id, closeAfter = false)
            return
        }
        val normalized = choice.palette?.let { withDesktopTextColor(it.toJson()) }
        val peer = currentSettings.omarchyPeer
        OmarchyLocalStyleSelection.apply(
            id = choice.id,
            applyLocal = {
                normalized?.let { payload ->
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
                (bundledBackgroundPath ?: instantBackground?.previewPath)
                    ?.let { path ->
                        applyAndPersistLocalBackground(path, publishToFlux = bundledBackgroundPath != null)
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
                choice.previewPath?.let(::applyAndPersistLocalBackground)
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

    private fun applyAndPersistLocalBackground(path: String, publishToFlux: Boolean = false) {
        // A bundled theme selection is an explicit image choice. Legacy catalog
        // previews remain previews; the Flux picker selects the original document.
        val session = if (publishToFlux) fluxLivePeers.values.firstOrNull {
            wallpaperAllowed(it) && wallpaperStates[it] != null
        } else null
        wallpaperIo.execute {
            runCatching {
                if (session != null) wallpaperSync.choose(session, File(path).inputStream())
                else persistWallpaper(path) { !isDestroyed }
            }.onFailure { error ->
                runOnUiThread { if (!isDestroyed) root.showConfigError(error.message.orEmpty()) }
            }
        }
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
        val bytes = assets.open(assetPath).use { it.readBytes() }
        val existing = target.takeIf(File::isFile)?.readBytes()
        if (!OmarchyBundledBackground.shouldReuse(existing, bytes)) {
            target.writeBytes(bytes)
        }
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
            syncSystemWallpaper(updatedConfig, currentSettings)
            runOnUiThread { if (!isDestroyed) root.submitConfig(updatedConfig, preserveFavorites = true) }
            directory.listFiles()
                ?.filter { it.isFile && it != file }
                ?.forEach(File::delete)
        }.onFailure { error ->
            runOnUiThread { root.showConfigError(error.message.orEmpty()) }
        }
    }

    private fun syncSystemWallpaper(config: LauncherConfig, settings: LauncherSettings) {
        // Honor the user preference and the distribution boundary before any
        // system wallpaper preference read/write or WallpaperManager call.
        if (!settings.applyOmarchyThemeToSystem) return
        if (!distributionPolicy.allowPublicSystemThemeIntegration) return
        val backgroundImage = config.desktops.firstOrNull()?.backgroundImage.orEmpty()
        val color = OmarchyThemePalette.fromSettings(settings.raw)?.color("background")
            ?: config.desktops.firstOrNull()?.background
            ?: config.wallpaper
        val key = SystemWallpaperSync.desiredKey(backgroundImage, color)
        val preferences = getSharedPreferences(SYSTEM_WALLPAPER_PREFS, MODE_PRIVATE)
        val storedKey = preferences.getString(SYSTEM_WALLPAPER_KEY, null)
        if (!SystemWallpaperSyncPolicy.shouldSync(settings.applyOmarchyThemeToSystem, key, storedKey)) return
        val imageApplied = backgroundImage.isNotBlank() && SystemWallpaperSync.apply(this, backgroundImage)
        val applied = if (imageApplied) {
            true
        } else {
            SystemWallpaperSync.applyColor(this, color)
        }
        if (applied) {
            val appliedKey = SystemWallpaperSync.appliedKey(backgroundImage, color, imageApplied)
            preferences.edit().putString(SYSTEM_WALLPAPER_KEY, appliedKey).apply()
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
        private val wallpaperIo = Executors.newSingleThreadExecutor { task -> Thread(task,"ohm-wallpaper-io").apply {isDaemon=true} }
        private const val FLUX_PICKER_KEYS = "flux_picker_keys"
        /** Executor de proceso: la activity puede destruirse y recrearse mientras
         *  el proceso sigue vivo (servicios); nunca se apaga en onDestroy. */
        private val io = Executors.newSingleThreadExecutor()
        private const val REQUEST_STORAGE = 4001
        private const val API_PORT = 8753
        private const val PEER_PROBE_INTERVAL_MS = 15_000L
        private const val REMOTE_CONTROL_PROMPT_THROTTLE_MS = 10_000L
        private const val SYSTEM_WALLPAPER_PREFS = "system_wallpaper_sync"
        private const val SYSTEM_WALLPAPER_KEY = "applied_key"
        private const val PLUGIN_RELOAD_DEBOUNCE_MS = 400L
        private val BUILT_IN_PLUGINS = mapOf(
            "io.github.ohm.demo.clock" to listOf("manifest.json", "BarWidget.json", "Panel.json"),
            "io.github.ohm.demo.weather" to listOf("manifest.json", "BarWidget.qml"),
        )
        private val LEGACY_BUILT_IN_ASSET_HASHES = mapOf(
            "io.github.ohm.demo.weather/BarWidget.qml" to setOf(
                "179e093fa88d0f8a7442c5f1dac69bd5acaeea01d543b583e57a287a05711982",
                "f8245722eba2d3536f8caa95b2d72aa25cf9ee9b074f0e215d7e99c8aaa6a57f",
            ),
        )
    }
}
