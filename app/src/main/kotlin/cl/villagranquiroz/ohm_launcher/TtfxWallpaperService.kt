package cl.villagranquiroz.ohm_launcher

import android.graphics.Color
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import java.io.File

/**
 * Live wallpaper TTFX: replica el fondo del escritorio (config del desktop 0,
 * paleta Omarchy desde settings.json) y lo dibuja también detrás del lockscreen.
 * Incluye el reloj de arena (partículas) en el tercio superior, como un reloj
 * de lockscreen. Sin audio: un wallpaper no debe usar el micrófono.
 *
 * Las vistas nunca se adjuntan a una ventana: el engine las mide/ubica y las
 * dibuja manualmente sobre la superficie del wallpaper en cada frame.
 */
class TtfxWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = TtfxEngine()

    private inner class TtfxEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private lateinit var root: FrameLayout
        private lateinit var ttfx: NativeTtfxView
        private lateinit var clock: ParticleClockView
        private var observer: FileObserver? = null
        private var configDirectory: File? = null
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var surfaceReady = false

        private val drawRunnable = Runnable { drawFrame() }
        private val tickRunnable = object : Runnable {
            override fun run() {
                requestDraw()
                handler.postDelayed(this, TICK_MS)
            }
        }
        private val reloadRunnable = Runnable { loadState() }

        override fun onCreate(surfaceHolder: android.view.SurfaceHolder) {
            super.onCreate(surfaceHolder)
            ttfx = NativeTtfxView(this@TtfxWallpaperService).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                onNewFrame = { requestDraw() }
            }
            clock = ParticleClockView(
                this@TtfxWallpaperService,
                format = "HH:mm",
                color = defaultAccent(),
                configuredTextSize = 96f,
            )
            root = FrameLayout(this@TtfxWallpaperService).apply {
                addView(ttfx)
                addView(clock)
            }
        }

        override fun onSurfaceCreated(holder: android.view.SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            loadState()
            startObserver()
            startTicker()
            requestDraw()
        }

        override fun onSurfaceChanged(
            holder: android.view.SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            relayout()
            requestDraw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                ttfx.setRenderActive(true)
                startTicker()
                requestDraw()
            } else {
                ttfx.setRenderActive(false)
                stopTicker()
            }
        }

        override fun onSurfaceDestroyed(holder: android.view.SurfaceHolder) {
            surfaceReady = false
            stopTicker()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopTicker()
            stopObserver()
            if (::ttfx.isInitialized) ttfx.release()
            super.onDestroy()
        }

        private fun requestDraw() {
            if (!surfaceReady || !isVisible) return
            handler.removeCallbacks(drawRunnable)
            handler.post(drawRunnable)
        }

        private fun drawFrame() {
            if (!surfaceReady || !isVisible || surfaceWidth <= 0 || surfaceHeight <= 0) return
            val canvas: Canvas = try {
                surfaceHolder.lockCanvas() ?: return
            } catch (_: Throwable) {
                return
            }
            try {
                root.draw(canvas)
            } finally {
                runCatching { surfaceHolder.unlockCanvasAndPost(canvas) }
            }
        }

        private fun relayout() {
            val widthSpec = View.MeasureSpec.makeMeasureSpec(surfaceWidth, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(surfaceHeight, View.MeasureSpec.EXACTLY)
            clock.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (surfaceHeight * CLOCK_HEIGHT_FRACTION).toInt().coerceAtLeast(dp(120)),
                Gravity.TOP,
            ).apply { topMargin = (surfaceHeight * CLOCK_TOP_FRACTION).toInt() }
            root.measure(widthSpec, heightSpec)
            root.layout(0, 0, surfaceWidth, surfaceHeight)
        }

        private fun loadState() {
            val storage = ConfigStorage(
                publicRoot = File(Environment.getExternalStorageDirectory(), "OhmLauncher"),
                legacyRoot = File(Environment.getExternalStorageDirectory(), "OmarchyLauncher"),
                privateRoot = (getExternalFilesDir(null) ?: filesDir).resolve("OhmLauncher"),
            )
            val canUsePublic = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()
            val directory = runCatching { storage.initialize(canUsePublic) }.getOrNull() ?: return
            configDirectory = directory
            val settings = runCatching {
                LauncherSettingsStore(directory.resolve(LauncherSettingsStore.FILE_NAME)).read()
            }.getOrDefault(LauncherSettings.parse("{}"))
            val palette = OmarchyThemePalette.fromSettings(settings.raw)
            ttfx.submitTheme(palette)
            clock.setColor(
                palette?.color("accent")?.let { runCatching { Color.parseColor(it) }.getOrNull() }
                    ?: defaultAccent(),
            )
            val config = runCatching {
                TtfxWallpaperConfig.resolve(directory.resolve(ConfigStorage.CONFIG_NAME).readText())
            }.getOrNull() ?: return
            ttfx.submit(config)
        }

        private fun startObserver() {
            stopObserver()
            val directory = configDirectory ?: return
            observer = object : FileObserver(directory, CLOSE_WRITE or MOVED_TO or CREATE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == ConfigStorage.CONFIG_NAME || path == LauncherSettingsStore.FILE_NAME) {
                        handler.removeCallbacks(reloadRunnable)
                        handler.postDelayed(reloadRunnable, RELOAD_DEBOUNCE_MS)
                    }
                }
            }.also { it.startWatching() }
        }

        private fun stopObserver() {
            observer?.stopWatching()
            observer = null
        }

        private fun startTicker() {
            stopTicker()
            handler.postDelayed(tickRunnable, TICK_MS)
        }

        private fun stopTicker() {
            handler.removeCallbacks(tickRunnable)
        }

        private fun defaultAccent(): Int = 0xFF66E0FF.toInt()

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val TICK_MS = 150L
        private const val RELOAD_DEBOUNCE_MS = 400L
        private const val CLOCK_HEIGHT_FRACTION = 0.18f
        private const val CLOCK_TOP_FRACTION = 0.2f
    }
}
