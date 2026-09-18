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
import android.util.Log
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
    private var foregroundRequestId: Long? = null
    @Volatile private var lastFrameAt = 0L

    fun createConsentIntent(): Intent = projectionManager.createScreenCaptureIntent()

    fun start(resultCode: Int, data: Intent, onStarted: (Boolean) -> Unit): Boolean {
        if (resultCode != Activity.RESULT_OK || running.get()) return false
        var requestId = 0L
        val foregroundRequest = ScreenCaptureForegroundRequests.createRequest {
            if (foregroundRequestId != requestId) return@createRequest
            foregroundRequestId = null
            onStarted(startProjection(resultCode, data))
        }
        requestId = foregroundRequest.id
        foregroundRequestId = requestId
        return runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenCaptureService::class.java)
                    .putExtra(ScreenCaptureForegroundRequests.EXTRA_REQUEST_ID, foregroundRequest.id),
            )
            Handler(context.mainLooper).postDelayed({
                if (foregroundRequestId == requestId && ScreenCaptureForegroundRequests.cancel(requestId)) {
                    foregroundRequestId = null
                    Log.e(TAG, "Screen capture foreground service did not start")
                    onStarted(false)
                }
            }, FOREGROUND_START_TIMEOUT_MS)
            true
        }.getOrElse { error ->
            ScreenCaptureForegroundRequests.cancel(requestId)
            foregroundRequestId = null
            Log.e(TAG, "Unable to request screen capture foreground service", error)
            onStarted(false)
            false
        }
    }

    private fun startProjection(resultCode: Int, data: Intent): Boolean = runCatching {
        val metrics = context.resources.displayMetrics
            // The viewer renders at most ~560px wide: capture at half resolution
            // (VirtualDisplay scales, layout kept via halved dpi). That makes
            // the JPEG encode ~4x cheaper so 15fps is actually sustainable,
            // while status keeps reporting the REAL size so remote-control
            // taps keep mapping onto full phone pixels.
            val realWidth = metrics.widthPixels
            val realHeight = metrics.heightPixels
            val captureWidth = (realWidth / 2) and 1.inv()
            val captureHeight = (realHeight / 2) and 1.inv()
            val captureDpi = (metrics.densityDpi / 2).coerceAtLeast(120)
            val worker = HandlerThread("ohm-screen-capture").apply { start() }
            val handler = Handler(worker.looper)
            val projection = projectionManager.getMediaProjection(resultCode, Intent(data))
                ?: error("MediaProjection consent was not accepted")
            val imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
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
                    val paddedWidth = paddedBitmapWidth(captureWidth, plane.pixelStride, plane.rowStride)
                    val padded = Bitmap.createBitmap(paddedWidth, captureHeight, Bitmap.Config.ARGB_8888)
                    try {
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val visible = if (paddedWidth == captureWidth) padded else Bitmap.createBitmap(padded, 0, 0, captureWidth, captureHeight)
                        try {
                            val output = ByteArrayOutputStream()
                            if (visible.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                                onFrame(output.toByteArray(), realWidth, realHeight)
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
                captureWidth,
                captureHeight,
                captureDpi,
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
    }.getOrElse { error ->
        Log.e(TAG, "Unable to start screen capture", error)
        stop()
        false
    }

    fun stop() {
        foregroundRequestId?.let(ScreenCaptureForegroundRequests::cancel)
        foregroundRequestId = null
        stop(releaseProjection = true)
    }

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
        private const val TAG = "OhmScreenCapture"
        private const val FOREGROUND_START_TIMEOUT_MS = 3_000L
        private const val JPEG_QUALITY = 60
        private const val FRAME_INTERVAL_MS = 66L
    }
}
