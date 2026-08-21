package cl.villagranquiroz.ohm_launcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * Servicio de accesibilidad que dibuja pequeños overlays transparentes en los
 * bordes de la pantalla para capturar gestos de navegación globales cuando
 * MIUI/HyperOS no proporciona gestos nativos para launchers de terceros.
 *
 * SOLO se dibujan cuando el sistema usa navegación por gestos (navigation_mode
 * = 2). Si se usan los botones de Android (navigation_mode = 0) los overlays
 * taparían los botones y los bordes, así que se eliminan.
 *
 * Se crean tres ventanas independientes (izquierda, derecha, abajo) en lugar
 * de una ventana a pantalla completa, para que los toques en el centro de la
 * pantalla (p. ej. al elegir una ventana en Recientes) pasen a la app de abajo.
 */
class OhmGestureAccessibilityService : AccessibilityService() {

    private val overlayViews = mutableListOf<View>()
    private var recentsWasShown = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_CONFIGURATION_CHANGED -> applyOverlaysForNavMode()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "onServiceConnected")
        applyOverlaysForNavMode()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val className = event.className?.toString().orEmpty()
        val pkg = event.packageName?.toString().orEmpty()
        // Ignorar eventos de nuestros propios overlays (View/FrameLayout):
        // agregarlos o quitarlos genera eventos que provocan un ciclo.
        if (pkg == packageName && className != "$packageName.MainActivity") return
        // Con botones de Android activos no debe haber overlays.
        if (!navModeIsGesture()) {
            if (overlayViews.isNotEmpty()) {
                Log.d(TAG, "buttons mode, removing overlays")
                removeOverlays()
            }
            recentsWasShown = false
            return
        }
        // Cuando se abre la vista de Recientes de MIUI/HyperOS, sus tarjetas
        // llegan hasta los bordes de la pantalla y nuestros overlays les roban
        // los toques. Los quitamos mientras Recientes esté al frente y los
        // restauramos al volver a cualquier otra ventana.
        // Verificar la ventana realmente enfocada: los eventos pueden mentir
        // (p. ej. MainActivity parpadeando al quitar overlays con Recientes abierto).
        val recentsFocused = isRecentsFocused()
        if (recentsFocused) {
            if (overlayViews.isNotEmpty()) {
                Log.d(TAG, "recents focused, removing overlays")
                removeOverlays()
            }
        } else if (overlayViews.isEmpty() && recentsWasShown) {
            Log.d(TAG, "recents gone, recreating overlays")
            recreateOverlays()
        }
        recentsWasShown = recentsFocused
    }
    override fun onInterrupt() {}

    private fun navModeIsGesture(): Boolean {
        return try {
            Settings.Secure.getInt(contentResolver, "navigation_mode", 0) == 2
        } catch (_: Exception) {
            false
        }
    }

    /** Crea o elimina los overlays según el modo de navegación del sistema. */
    private fun applyOverlaysForNavMode() {
        if (navModeIsGesture()) {
            if (overlayViews.isEmpty()) {
                Log.d(TAG, "gesture mode, creating overlays")
                recreateOverlays()
            }
        } else {
            if (overlayViews.isNotEmpty()) {
                Log.d(TAG, "buttons mode, removing overlays")
                removeOverlays()
            }
        }
    }

    private fun isRecentsFocused(): Boolean {
        return try {
            val root = getRootInActiveWindow()
            val pkg = root?.packageName?.toString().orEmpty()
            Log.d(TAG, "active window: $pkg")
            pkg == RECENTS_PACKAGE
        } catch (_: Exception) {
            false
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind")
        removeOverlays()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        return super.onUnbind(intent)
    }

    private fun recreateOverlays() {
        removeOverlays()
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)

        val density = resources.displayMetrics.density
        val edgeX = (EDGE_X_DP * density).toInt()
        val edgeY = (EDGE_Y_DP * density).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        // Borde izquierdo: swipe hacia arriba = Escritorio (Home),
        // swipe horizontal hacia adentro = Atrás.
        val swipeThreshold = SWIPE_THRESHOLD_DP * density
        addEdgeOverlay(
            wm = wm,
            type = type,
            flags = flags,
            width = edgeX,
            height = metrics.heightPixels,
            gravity = Gravity.TOP or Gravity.START,
            onRelease = { dx, dy ->
                when {
                    // dy > 0 significa swipe hacia arriba; debe dominar sobre lo horizontal.
                    dy > swipeThreshold && dy > abs(dx) -> {
                        Log.d(TAG, "home from left edge swipe up")
                        performHome()
                    }
                    dx > swipeThreshold && abs(dx) > abs(dy) -> {
                        Log.d(TAG, "back from left edge")
                        performBack()
                    }
                }
            },
        )

        // Borde derecho: swipe hacia arriba = Ventanas abiertas (Recientes),
        // swipe horizontal hacia adentro = Atrás.
        addEdgeOverlay(
            wm = wm,
            type = type,
            flags = flags,
            width = edgeX,
            height = metrics.heightPixels,
            gravity = Gravity.TOP or Gravity.END,
            onRelease = { dx, dy ->
                when {
                    dy > swipeThreshold && dy > abs(dx) -> {
                        Log.d(TAG, "recents from right edge swipe up")
                        performRecents()
                    }
                    dx < -swipeThreshold && abs(dx) > abs(dy) -> {
                        Log.d(TAG, "back from right edge")
                        performBack()
                    }
                }
            },
        )

        // Borde inferior: estilo Xiaomi — swipe rápido hacia arriba = Inicio,
        // swipe hacia arriba y mantener = Recientes.
        addBottomOverlay(
            wm = wm,
            type = type,
            flags = flags,
            width = metrics.widthPixels,
            height = edgeY,
            gravity = Gravity.BOTTOM or Gravity.START,
        )
    }

    private fun addBottomOverlay(
        wm: WindowManager,
        type: Int,
        flags: Int,
        width: Int,
        height: Int,
        gravity: Int,
    ) {
        var startY = 0f
        var lastY = 0f
        var lastMoveTime = 0L
        var triggered = false
        val density = resources.displayMetrics.density
        val homeThreshold = HOME_THRESHOLD_DP * density
        val recentsThreshold = RECENTS_THRESHOLD_DP * density
        val holdDuration = RECENTS_HOLD_DURATION_MS
        val stillSlop = 4f * density

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var holdRunnable: Runnable? = null

        val view = View(this).apply {
            setBackgroundColor(0x00000000)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        lastY = event.rawY
                        lastMoveTime = System.currentTimeMillis()
                        triggered = false
                        holdRunnable?.let { handler.removeCallbacks(it) }
                        // Recientes solo si el dedo subió lo suficiente Y se quedó
                        // quieto (hold). Un swipe continuo lento no debe dispararlo.
                        // OJO: no capturar el MotionEvent (se recicla); usamos lastY.
                        holdRunnable = object : Runnable {
                            override fun run() {
                                if (triggered) return
                                val dy = startY - lastY
                                if (dy <= recentsThreshold) return
                                val stillFor = System.currentTimeMillis() - lastMoveTime
                                if (stillFor >= STILL_DURATION_MS) {
                                    triggered = true
                                    Log.d(TAG, "recents from bottom hold")
                                    performRecents()
                                } else {
                                    // Sigue moviéndose: reintenta mientras el dedo esté abajo.
                                    handler.postDelayed(this, 80)
                                }
                            }
                        }.also { handler.postDelayed(it, holdDuration) }
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(event.rawY - lastY) > stillSlop) {
                            lastY = event.rawY
                            lastMoveTime = System.currentTimeMillis()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        holdRunnable?.let { handler.removeCallbacks(it) }
                        if (!triggered) {
                            val dy = startY - event.rawY
                            if (dy > homeThreshold) {
                                Log.d(TAG, "home from bottom swipe")
                                performHome()
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        holdRunnable?.let { handler.removeCallbacks(it) }
                    }
                }
                true
            }
        }

        val params = WindowManager.LayoutParams(
            width,
            height,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            x = 0
            y = 0
        }

        try {
            wm.addView(view, params)
            overlayViews.add(view)
        } catch (e: Exception) {
            Log.e(TAG, "addBottomOverlay failed", e)
        }
    }

    private fun addEdgeOverlay(
        wm: WindowManager,
        type: Int,
        flags: Int,
        width: Int,
        height: Int,
        gravity: Int,
        onRelease: (dx: Float, dy: Float) -> Unit,
    ) {
        var startX = 0f
        var startY = 0f

        val view = View(this).apply {
            setBackgroundColor(0x00000000)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_UP -> {
                        // Decidir al soltar: si la acción se dispara a mitad del
                        // gesto (p. ej. Recientes), el sistema queda esperando el
                        // resto del gesto y el siguiente toque se pierde.
                        onRelease(event.rawX - startX, startY - event.rawY)
                    }
                    else -> Unit
                }
                true
            }
        }

        val params = WindowManager.LayoutParams(
            width,
            height,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            x = 0
            y = 0
        }

        try {
            wm.addView(view, params)
            overlayViews.add(view)
        } catch (e: Exception) {
            Log.e(TAG, "addEdgeOverlay failed", e)
        }
    }

    private fun removeOverlays() {
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        Log.d(TAG, "removeOverlays: ${overlayViews.size} tracked views")
        overlayViews.forEach {
            try { wm.removeView(it) } catch (e: Exception) { Log.e(TAG, "removeView failed", e) }
        }
        overlayViews.clear()
    }

    private fun performBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun performHome() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun performRecents() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    companion object {
        private const val TAG = "OhmGestureA11y"
        private const val EDGE_X_DP = 24f
        private const val EDGE_Y_DP = 44f
        private const val SWIPE_THRESHOLD_DP = 40f
        private const val HOME_THRESHOLD_DP = 24f
        private const val RECENTS_THRESHOLD_DP = 48f
        private const val RECENTS_HOLD_DURATION_MS = 220L
        private const val STILL_DURATION_MS = 100L
        private const val RECENTS_PACKAGE = "com.miui.home"
    }
}
