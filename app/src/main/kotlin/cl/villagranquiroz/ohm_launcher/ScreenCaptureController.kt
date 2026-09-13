package cl.villagranquiroz.ohm_launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal fun paddedBitmapWidth(width: Int, pixelStride: Int, rowStride: Int): Int {
    require(width > 0 && pixelStride > 0 && rowStride >= width * pixelStride)
    return width + (rowStride - pixelStride * width) / pixelStride
}

/** MediaProjection capture loop that emits cropped JPEG frames off the UI thread. */
class ScreenCaptureController(
    private val context: Context,
    private val onFrame: (jpeg: ByteArray, width: Int, height: Int) -> Unit,
) {
    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val running = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    @Volatile private var lastFrameAt = 0L

    fun createConsentIntent(): Intent = projectionManager.createScreenCaptureIntent()

    fun start(resultCode: Int, data: Intent): Boolean {
        if (resultCode != Activity.RESULT_OK || running.get()) return false
        ContextCompat.startForegroundService(context, Intent(context, ScreenCaptureService::class.java))
        if (!ScreenCaptureService.awaitForeground(3_000)) return false
        return runCatching {
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val worker = HandlerThread("ohm-screen-capture").apply { start() }
            val handler = Handler(worker.looper)
            val projection = projectionManager.getMediaProjection(resultCode, Intent(data))
                ?: error("MediaProjection consent was not accepted")
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = stop(releaseProjection = false)
            }, handler)
            imageReader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                image.use {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameAt < FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                    lastFrameAt = now
                    val plane = it.planes.firstOrNull() ?: return@setOnImageAvailableListener
                    val paddedWidth = paddedBitmapWidth(width, plane.pixelStride, plane.rowStride)
                    val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                    try {
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val visible = if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
                        try {
                            val output = ByteArrayOutputStream()
                            if (visible.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                                onFrame(output.toByteArray(), width, height)
                            }
                        } finally {
                            if (visible !== padded) visible.recycle()
                        }
                    } finally {
                        padded.recycle()
                    }
                }
            }, handler)
            val virtualDisplay = projection.createVirtualDisplay(
                "ohm-screen",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler,
            )
            this.thread = worker
            this.projection = projection
            reader = imageReader
            display = virtualDisplay
            running.set(true)
            true
        }.getOrElse {
            stop()
            false
        }
    }

    fun stop() = stop(releaseProjection = true)

    fun isRunning(): Boolean = running.get()

    private fun stop(releaseProjection: Boolean) {
        if (!running.getAndSet(false) && projection == null && reader == null) return
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        display = null
        reader?.close()
        reader = null
        val activeProjection = projection
        projection = null
        if (releaseProjection) runCatching { activeProjection?.stop() }
        thread?.quitSafely()
        thread = null
        runCatching {
            context.startService(
                Intent(context, ScreenCaptureService::class.java).putExtra("stop", true),
            )
        }
    }

    companion object {
        private const val JPEG_QUALITY = 60
        private const val FRAME_INTERVAL_MS = 100L
    }
}
