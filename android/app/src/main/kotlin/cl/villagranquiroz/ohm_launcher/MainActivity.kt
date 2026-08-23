package cl.villagranquiroz.ohm_launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Bundle
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import android.view.View
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method

class MainActivity : FlutterActivity() {
    private val channelName = "com.ohm/ohm"
    private lateinit var appWidgetHost: AppWidgetHost
    private val appWidgetIds = mutableMapOf<Int, ComponentName>()
    private var methodChannel: MethodChannel? = null
    private var pendingBindWidgetId = -1
    private var pendingBindWidgetComponent: ComponentName? = null
    private var pendingBindProvider: String = ""

    // --- Resultado de comandos Termux (RUN_COMMAND) ---
    private val termuxResultAction = "cl.villagranquiroz.ohm_launcher.TERMUX_RESULT"
    private val termuxResults = mutableMapOf<Int, MethodChannel.Result>()
    private var termuxRequestId = 0
    private val termuxReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != termuxResultAction) return
            val id = intent.getIntExtra("req", -1)
            val result = termuxResults.remove(id) ?: return
            val stdout = intent.getStringExtra("com.termux.RUN_COMMAND_RESULT_BROADCAST_EXTRA_STDOUT") ?: ""
            val stderr = intent.getStringExtra("com.termux.RUN_COMMAND_RESULT_BROADCAST_EXTRA_STDERR") ?: ""
            val exitCode = intent.getIntExtra("com.termux.RUN_COMMAND_RESULT_BROADCAST_EXTRA_EXIT_CODE", -1)
            result.success(mapOf("stdout" to stdout, "stderr" to stderr, "exitCode" to exitCode))
        }
    }

    companion object {
        private const val REQUEST_CODE_BIND_WIDGET = 0xA11CE5
    }

    /** Hides the system navigation bar so the launcher can use its own gestures. */
    private fun applyImmersiveMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { controller ->
                    controller.hide(android.view.WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            }
        } catch (_: Exception) { /* noop */ }
    }

    /** Restores the system navigation bar. */
    private fun disableImmersiveMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(true)
                window.insetsController?.show(android.view.WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        } catch (_: Exception) { /* noop */ }
    }

    /** Intenta abrir las aplicaciones recientes. */
    private fun openRecents() {
        try {
            // Método 1: intento directo con IStatusBarService (funciona en algunas ROMs/raíces).
            val serviceManager = Class.forName("android.app.ServiceManager")
            val getService: Method = serviceManager.getMethod("getService", String::class.java)
            val statusBarService = getService.invoke(null, "statusbar")
            if (statusBarService != null) {
                val iStatusBarStub = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
                val asInterface: Method = iStatusBarStub.getMethod("asInterface", android.os.IBinder::class.java)
                val statusBar = asInterface.invoke(null, statusBarService)
                if (statusBar != null) {
                    val toggleRecentApps: Method? = statusBar.javaClass.getMethod("toggleRecentApps")
                    toggleRecentApps?.invoke(statusBar)
                    return
                }
            }
        } catch (_: Exception) { /* noop */ }

        // Fallback: intentar enviar APP_SWITCH como si fuera el sistema (poco probable en no-root).
        try {
            Runtime.getRuntime().exec("input keyevent KEYCODE_APP_SWITCH")
        } catch (_: Exception) { /* noop */ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleOmarchyLinkIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOmarchyLinkIntent(intent)
    }

    /** Si el intent trae un `omarchy://` (QR escaneado con la cámara del
     *  sistema), lo pasa a Flutter para que conecte con la PC. */
    private fun handleOmarchyLinkIntent(intent: Intent?) {
        val uri = intent?.data?.toString() ?: return
        if (uri.startsWith("omarchy://")) {
            try {
                methodChannel?.invokeMethod("onOmarchyPeerLink", mapOf("uri" to uri))
            } catch (_: Exception) { /* noop */ }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        appWidgetHost = AppWidgetHost(applicationContext, 0x0A0B0C)
        try {
            appWidgetHost.startListening()
        } catch (_: Exception) { /* noop */ }

        flutterEngine.platformViewsController.registry.registerViewFactory(
            "com.ohm/appwidget",
            object : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
                override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
                    val id = ((args as? Map<*, *>)?.get("id") as? Number)?.toInt() ?: -1
                    val manager = applicationContext.getSystemService(AppWidgetManager::class.java)
                    val info = manager.getAppWidgetInfo(id)
                    val hostView: View = if (info != null) {
                        appWidgetHost.createView(context, id, info).apply {
                            setBackgroundColor(0xFF16202A.toInt())
                        }
                    } else {
                        View(context).apply {
                            setBackgroundColor(0xFF16202A.toInt())
                        }
                    }
                    return object : PlatformView {
                        override fun getView(): View = hostView
                        override fun dispose() {
                            try {
                                appWidgetHost.deleteAppWidgetId(id)
                                appWidgetIds.remove(id)
                            } catch (_: Exception) { /* noop */ }
                        }
                    }
                }
            },
        )

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
        setupScreenChannel(flutterEngine)
        try {
            ContextCompat.registerReceiver(
                this,
                termuxReceiver,
                IntentFilter(termuxResultAction),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (_: Exception) { /* noop */ }
        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                    "getInstalledApps" -> result.success(getInstalledApps())
                    "getInstalledAppWidgets" -> result.success(getInstalledAppWidgets())
                    "getAppIcon" -> {
                        val pkg = call.argument<String>("package") ?: ""
                        val activity = call.argument<String>("activity") ?: ""
                        result.success(getAppIcon(pkg, activity) ?: ByteArray(0))
                    }
                    "getBatteryLevel" -> result.success(getBatteryLevel())
                    "isDefaultLauncher" -> result.success(isDefaultLauncher())
                    "getNavigationMode" -> result.success(getNavigationMode())
                    "requestDefaultLauncher" -> {
                        requestDefaultLauncher()
                        result.success(null)
                    }
                    "openAppSettings" -> {
                        openAppSettings()
                        result.success(null)
                    }
                    "openNavigationSettings" -> {
                        openNavigationSettings()
                        result.success(null)
                    }
                    "restoreGestureNavigation" -> {
                        val ok = restoreGestureNavigation()
                        result.success(ok)
                    }
                    "openAccessibilitySettings" -> {
                        openAccessibilitySettings()
                        result.success(null)
                    }
                    "isGestureAccessibilityEnabled" -> {
                        result.success(isGestureAccessibilityEnabled())
                    }
                    "openRecents" -> {
                        openRecents()
                        result.success(null)
                    }
                    "restartApp" -> {
                        restartApp()
                        result.success(null)
                    }
                    "setImmersiveMode" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: true
                        if (enabled) applyImmersiveMode() else disableImmersiveMode()
                        result.success(null)
                    }
                    "launchApp" -> {
                        val pkg = call.argument<String>("package") ?: ""
                        val activity = call.argument<String>("activity") ?: ""
                        result.success(launchApp(pkg, activity))
                    }
                    "bindAppWidget" -> {
                        val provider = call.argument<String>("provider") ?: ""
                        bindAppWidget(provider, result)
                    }
                    "unbindAppWidget" -> {
                        val id = call.argument<Int>("id") ?: -1
                        try {
                            appWidgetHost.deleteAppWidgetId(id)
                            appWidgetIds.remove(id)
                            result.success(null)
                        } catch (_: Exception) {
                            result.success(null)
                        }
                    }
                    "runInTermux" -> {
                        val command = call.argument<String>("command") ?: ""
                        val args = (call.argument<List<Any>>("args") ?: emptyList())
                            .map { it.toString() }
                            .toTypedArray()
                        runInTermux(command, args, result)
                    }
                    "isTermuxApiInstalled" -> result.success(isTermuxApiInstalled())
                    else -> result.notImplemented()
                }
            }
    }

    /** Binds a system AppWidget and returns its appWidgetId. */
    private fun bindAppWidget(provider: String, result: MethodChannel.Result) {
        val component = ComponentName.unflattenFromString(provider)
        if (component == null) {
            result.error("bad_provider", "Proveedor de widget inválido", null)
            return
        }
        val manager = applicationContext.getSystemService(AppWidgetManager::class.java)
        val id = appWidgetHost.allocateAppWidgetId()
        if (manager.bindAppWidgetIdIfAllowed(id, component)) {
            appWidgetIds[id] = component
            try { appWidgetHost.startListening() } catch (_: Exception) { /* noop */ }
            result.success(id)
        } else {
            // Stores the id to finish the binding after the system permission.
            pendingBindWidgetId = id
            pendingBindWidgetComponent = component
            pendingBindProvider = provider
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, component)
            }
            try {
                startActivityForResult(intent, REQUEST_CODE_BIND_WIDGET)
                result.success(mapOf("id" to id, "needsBind" to true))
            } catch (e: Exception) {
                try { appWidgetHost.deleteAppWidgetId(id) } catch (_: Exception) { /* noop */ }
                result.error(
                    "bind_not_allowed",
                    "No se puede enlazar el widget. Establece Ohm Launcher como launcher por defecto.",
                    null,
                )
            }
        }
    }

    /** Completa el binding de un widget tras la autorización del sistema. */
    private fun completePendingWidgetBind() {
        val id = pendingBindWidgetId
        val component = pendingBindWidgetComponent
        if (id == -1 || component == null) return
        val manager = applicationContext.getSystemService(AppWidgetManager::class.java)
        if (manager.bindAppWidgetIdIfAllowed(id, component)) {
            appWidgetIds[id] = component
            try { appWidgetHost.startListening() } catch (_: Exception) { /* noop */ }
            methodChannel?.invokeMethod("widgetBound", mapOf("id" to id, "provider" to pendingBindProvider))
        } else {
            try { appWidgetHost.deleteAppWidgetId(id) } catch (_: Exception) { /* noop */ }
            methodChannel?.invokeMethod("widgetBindFailed", mapOf("id" to id, "provider" to pendingBindProvider))
        }
        pendingBindWidgetId = -1
        pendingBindWidgetComponent = null
        pendingBindProvider = ""
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqScreenCapture) {
            _handleScreenResult(resultCode, data)
            return
        }
        if (requestCode == REQUEST_CODE_BIND_WIDGET && resultCode == RESULT_OK) {
            completePendingWidgetBind()
        } else if (requestCode == REQUEST_CODE_BIND_WIDGET) {
            try { appWidgetHost.deleteAppWidgetId(pendingBindWidgetId) } catch (_: Exception) { /* noop */ }
            methodChannel?.invokeMethod("widgetBindFailed", mapOf("id" to pendingBindWidgetId, "provider" to pendingBindProvider))
            pendingBindWidgetId = -1
            pendingBindWidgetComponent = null
            pendingBindProvider = ""
        }
    }

    /** Porcentaje de batería (0-100). */
    private fun getBatteryLevel(): Int {
        return try {
            val bm = applicationContext.getSystemService(BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            -1
        }
    }

    /** Apps reales instaladas y lanzables (launcher intents), SIN icono para no bloquear UI. */
    private fun getInstalledApps(): List<Map<String, Any>> {
        val pm = applicationContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val apps = mutableListOf<Map<String, Any>>()
        for (ri in resolved) {
            val ai = ri.activityInfo
            val label = ri.loadLabel(pm)?.toString() ?: ai.packageName
            apps.add(
                mapOf(
                    "label" to label,
                    "package" to ai.packageName,
                    "activity" to ai.name,
                ),
            )
        }
        apps.sortBy { (it["label"] as String).lowercase() }
        return apps
    }

    /** AppWidgets del sistema (proveedores) disponibles para el launcher. */
    private fun getInstalledAppWidgets(): List<Map<String, Any>> {
        val awm = applicationContext.getSystemService(android.appwidget.AppWidgetManager::class.java)
        val list = mutableListOf<Map<String, Any>>()
        for (p in awm.installedProviders) {
            val label = p.loadLabel(applicationContext.packageManager)?.toString() ?: p.provider.packageName
            list.add(
                mapOf(
                    "label" to label,
                    "package" to p.provider.packageName,
                    "provider" to p.provider.flattenToString(),
                    "minWidth" to p.minWidth,
                    "minHeight" to p.minHeight,
                ),
            )
        }
        list.sortBy { (it["label"] as String).lowercase() }
        return list
    }

    /** PNG icon of a specific app, loaded on demand. */    private fun getAppIcon(packageName: String, activityName: String): ByteArray? {
        return try {
            val pm = applicationContext.packageManager
            val info = if (activityName.isEmpty()) {
                pm.getApplicationInfo(packageName, 0)
            } else {
                pm.getActivityInfo(ComponentName(packageName, activityName), 0)
            }
            drawableToPng(info.loadIcon(pm))
        } catch (_: Exception) {
            null
        }
    }

    /** Convierte CUALQUIER Drawable (incluidos los iconos adaptativos) a PNG. */
    private fun drawableToPng(drawable: Drawable): ByteArray? {
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** Comprueba si Ohm Launcher es el launcher por defecto del sistema. */
    private fun isDefaultLauncher(): Boolean {
        return try {
            val pm = applicationContext.packageManager
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = pm.resolveActivity(home, 0)
            resolved?.activityInfo?.packageName == applicationContext.packageName
        } catch (_: Exception) {
            false
        }
    }

    /** Opens the app info screen to manage permissions. */
    private fun openAppSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            // Si falla, intenta abrir Ajustes generales.
            try {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) { /* noop */ }
        }
    }

    /** Abre el selector del sistema para elegir el launcher por defecto. */
    private fun requestDefaultLauncher() {
        val attempts = mutableListOf<Intent>()

        // 1) Direct "Home app" picker (AOSP, MIUI, HyperOS).
        //    This intent opens the screen with the radio buttons of installed launchers.
        try {
            attempts.add(Intent(android.provider.Settings.ACTION_HOME_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) { /* ignora */ }

        // 2) Android 10+ diálogo nativo de RoleManager (funciona en AOSP/Pixel).
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                val roleManager = applicationContext.getSystemService(android.app.role.RoleManager::class.java)
                attempts.add(roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME))
            } catch (_: Exception) { /* ignora */ }
        }

        // 3) Intent específico de MIUI/HyperOS.
        try {
            val miui = Intent("com.miui.settings.HOME_SETTINGS_MIUI").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (miui.resolveActivity(packageManager) != null) attempts.add(miui)
        } catch (_: Exception) { /* ignora */ }

        // 4) Pantalla de Ajustes → Aplicaciones por defecto.
        try {
            attempts.add(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) { /* ignora */ }

        // 5) Último recurso: Ajustes generales.
        try {
            attempts.add(Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) { /* ignora */ }

        // Lanza el primer intent que resuelva algo sin crashear.
        for (intent in attempts) {
            try {
                if (intent.resolveActivity(packageManager) != null) {
                    runOnUiThread { startActivity(intent) }
                    return
                }
            } catch (_: Exception) {
                // Prueba el siguiente.
            }
        }
    }

    /** Restaura la navegación por gestos (navigation_mode=2) cuando Xiaomi/HyperOS
     *  la ha desactivado por usar un launcher de terceros.
     *  Requires the WRITE_SECURE_SETTINGS permission granted via ADB. */
    private fun restoreGestureNavigation(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                val cr = applicationContext.contentResolver
                android.provider.Settings.Secure.putInt(cr, "navigation_mode", 2)
                android.provider.Settings.Global.putInt(cr, "force_fsg_nav_bar", 1)
                android.provider.Settings.Global.putInt(cr, "hide_gesture_line", 0)
                // Asegura que nuestro launcher esté en la whitelist de gestos de MIUI.
                val current = android.provider.Settings.Global.getString(cr, "rt_gesture_white_list") ?: ""
                val needed = applicationContext.packageName
                if (!current.contains(needed)) {
                    val updated = if (current.isEmpty()) needed else "$current,$needed"
                    android.provider.Settings.Global.putString(cr, "rt_gesture_white_list", updated)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 0=botones, 2=gestos. */
    private fun getNavigationMode(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                android.provider.Settings.Secure.getInt(
                    applicationContext.contentResolver,
                    "navigation_mode",
                    0,
                )
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }

    /** Abre los Ajustes del sistema → Navegación (para reactivar los gestos en MIUI/HyperOS). */
    private fun openNavigationSettings() {
        val candidates = listOf(
            "com.android.settings/.Settings\$GestureNavigationSettingsActivity",
            "com.android.settings/.Settings\$FullScreenDisplaySettingsActivity",
            "com.android.settings/.Settings\$SystemNavigationSettingsActivity",
            "com.android.settings/.Settings\$ManageDefaultAppsSettingsActivity",
        )
        for (c in candidates) {
            try {
                val intent = Intent().apply {
                    setClassName("com.android.settings", c.substringAfter("/"))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(packageManager) != null) {
                    runOnUiThread { startActivity(intent) }
                    return
                }
            } catch (_: Exception) { /* prueba la siguiente */ }
        }
        // Fallback: system gesture screen.
        try {
            val intent = Intent("android.settings.SYSTEM_NAVIGATION_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(packageManager) != null) {
                runOnUiThread { startActivity(intent) }
                return
            }
        } catch (_: Exception) { /* noop */ }
        // Último recurso: Ajustes generales.
        try {
            runOnUiThread {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        } catch (_: Exception) { /* noop */ }
    }

    /** Abre los Ajustes del sistema → Accesibilidad para activar el servicio de gestos. */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runOnUiThread { startActivity(intent) }
        } catch (_: Exception) { /* noop */ }
    }

    /** Comprueba si nuestro servicio de gestos está activado en Accesibilidad. */
    private fun isGestureAccessibilityEnabled(): Boolean {
        return try {
            val enabled = android.provider.Settings.Secure.getString(
                applicationContext.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            enabled?.contains("cl.villagranquiroz.ohm_launcher/cl.villagranquiroz.ohm_launcher.OhmGestureAccessibilityService") == true
        } catch (_: Exception) {
            false
        }
    }

    /** Lanza la actividad MAIN/LAUNCHER de la app indicada. */
    private fun launchApp(pkg: String, activity: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            intent.setClassName(pkg, activity)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Reinicia el launcher (recrea la Activity y, con ella, el motor Flutter). */
    private fun restartApp() {
        recreate()
    }

    /** Runs [command] in Termux via the com.termux.RUN_COMMAND intent
     *  (requires Termux:API installed). The result arrives by broadcast to
     *  [termuxReceiver] and is handed to Dart's [result]. If Termux is not
     *  available, the Dart side falls back to Process.run. */
    private fun runInTermux(command: String, args: Array<String>, result: MethodChannel.Result) {
        try {
            val id = ++termuxRequestId
            termuxResults[id] = result
            val resultIntent = Intent(termuxResultAction).setPackage(applicationContext.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getBroadcast(applicationContext, id, resultIntent, flags)
            val exec = Intent("com.termux.RUN_COMMAND").apply {
                setPackage("com.termux")
                putExtra("com.termux.RUN_COMMAND_COMMAND", command)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
                putExtra("com.termux.RUN_COMMAND_WORKDIR", applicationContext.filesDir.absolutePath)
                putExtra("com.termux.RUN_COMMAND_RESULT_INTENT_SENDER", pending.intentSender)
                putExtra("req", id)
            }
            applicationContext.sendBroadcast(exec)
            Log.d("OhmTermux", "RUN_COMMAND enviado: $command (args=${args.contentToString()})")
        } catch (e: Exception) {
            termuxResults.remove(termuxRequestId)
            result.error("termux_failed", e.message, null)
        }
    }

    /** Indica si Termux:API (com.termux.api) está instalado. El ejecutor de
     *  comandos embebido de Ohm Launcher NO lo requiere; es solo una mejora
     *  opcional para reutilizar el entorno de paquetes de Termux. */
    private fun isTermuxApiInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("com.termux.api", 0) != null
        } catch (_: Exception) {
            false
        }
    }

    // ============================================================
    //  Screen sharing (scrcpy-like) via MediaProjection.
    //  The launcher captures the screen and sends JPEG frames over the
    //  'ohm/screen' channel to the Dart side, which forwards them over
    //  WebSocket to the peer.
    // ============================================================
    private val screenChannelName = "ohm/screen"
    private var screenChannel: MethodChannel? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var screenThread: Thread? = null
    private var screenRunning = false
    private val reqScreenCapture = 0x5CEE

    private fun setupScreenChannel(flutterEngine: FlutterEngine) {
        screenChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, screenChannelName)
        screenChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "startCapture" -> startScreenCapture(result)
                "stopCapture" -> { stopScreenCapture(); result.success(true) }
                else -> result.notImplemented()
            }
        }
    }

    private fun startScreenCapture(result: MethodChannel.Result) {
        try {
            mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = mediaProjectionManager!!.createScreenCaptureIntent()
            startActivityForResult(intent, reqScreenCapture)
            result.success(true)
        } catch (e: Exception) {
            result.error("screen_failed", e.message, null)
        }
    }

    private fun stopScreenCapture() {
        screenRunning = false
        try { screenThread?.join(500) } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
    }

    private fun _handleScreenResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK || data == null) return
        try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data!!)
            val metrics = resources.displayMetrics
            val w = metrics.widthPixels
            val h = metrics.heightPixels
            imageReader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopScreenCapture() }
            }, handler)
            screenRunning = true
            screenThread = Thread {
                while (screenRunning) {
                    try {
                        val image = imageReader?.acquireLatestImage() ?: run { Thread.sleep(100); continue }
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * w
                        val bitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)
                        image.close()
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
                        bitmap.recycle()
                        val jpeg = out.toByteArray()
                        runOnUiThread { screenChannel?.invokeMethod("onFrame", jpeg) }
                    } catch (_: Exception) {
                        Thread.sleep(100)
                    }
                }
            }
            screenThread?.start()
        } catch (e: Exception) {
            Log.e("OhmScreen", "capture error: ${e.message}")
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(termuxReceiver)
        } catch (_: Exception) { /* noop */ }
        super.onDestroy()
    }
}