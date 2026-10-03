package cl.villagranquiroz.ohm_launcher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.net.Uri
import androidx.core.app.NotificationCompat

/**
 * Foreground service required by Android 14+ (API 34) to allow MediaProjection:
 * `MediaProjectionManager.getMediaProjection()` throws unless a foreground
 * service with type `mediaProjection` is running. The actual capture loop lives
 * in MainActivity; this service only holds the foreground state while sharing.
 */
@SuppressLint("ForegroundServiceType") // Full manifest declares mediaProjection; Play removes this service.
class ScreenCaptureService : Service() {
    private var fluxStopId: Long? = null
    private var foregroundActive = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A service restart cannot recover the Activity's OS capture-consent grant.
        if (intent == null) {
            if (!foregroundActive) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_FLUX_STOP || intent?.action == ACTION_FLUX_ENDED) {
            val id = intent.getLongExtra(EXTRA_FLUX_STOP_ID, -1L)
            if (!BuildConfig.PLAY_STORE_DISTRIBUTION && id >= 0L) {
                if (intent.action == ACTION_FLUX_STOP) FluxScreenStops.stop(id)
                if (fluxStopId == id) {
                    fluxStopId = null
                    foregroundActive = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            if (!foregroundActive) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra("stop", false) == true) {
            // An older Omarchy capture must not stop a newer Flux capture.
            if (fluxStopId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val flux = intent?.getStringExtra(EXTRA_TARGET) == "flux"
        val captureId = if (flux) intent?.getLongExtra(EXTRA_FLUX_STOP_ID, -1L) else null
        if (flux && (BuildConfig.PLAY_STORE_DISTRIBUTION || captureId == null ||
                !FluxScreenStops.isCurrent(captureId))) {
            intent?.getLongExtra(ScreenCaptureForegroundRequests.EXTRA_REQUEST_ID, -1L)
                ?.takeIf { it >= 0L }?.let(ScreenCaptureForegroundRequests::cancel)
            if (!foregroundActive) stopSelf(startId)
            return START_NOT_STICKY
        }
        fluxStopId = captureId
        val notif = buildNotification(flux, captureId)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        foregroundActive = true
        val requestId = intent?.getLongExtra(ScreenCaptureForegroundRequests.EXTRA_REQUEST_ID, -1L) ?: -1L
        if (requestId >= 0L) ScreenCaptureForegroundRequests.signal(requestId)
        return if (flux) START_NOT_STICKY else START_STICKY
    }


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val captureId = fluxStopId
        fluxStopId = null
        foregroundActive = false
        captureId?.let(FluxScreenStops::stop)
        super.onDestroy()
    }

    private fun buildNotification(flux: Boolean, captureId: Long?): Notification {
        val chanId = if (flux) "ohm_flux_screen" else "ohm_screen"
        val title = if (flux) getString(R.string.flux_screen_notification_title) else "Omarchy Screen Share"
        val text = if (flux) getString(R.string.flux_screen_notification_text) else "Compartiendo pantalla con el PC"
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val chan = NotificationChannel(chanId, title, NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(chan)
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, chanId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi!!)
            .setOngoing(true)
        if (flux && captureId != null) {
            val stop = PendingIntent.getService(this, 0, fluxStopIntent(this, captureId),
                PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.flux_screen_stop), stop)
        }
        return builder.build()
    }

    companion object {
        const val NOTIF_ID = 9002
        const val EXTRA_TARGET = "screen_target"
        const val EXTRA_FLUX_STOP_ID = "flux_screen_stop_id"
        private const val ACTION_FLUX_STOP = "cl.villagranquiroz.ohm_launcher.FLUX_SCREEN_STOP"
        private const val ACTION_FLUX_ENDED = "cl.villagranquiroz.ohm_launcher.FLUX_SCREEN_ENDED"

        internal fun fluxStopIntent(context: Context, id: Long, ended: Boolean = false): Intent =
            Intent(context, ScreenCaptureService::class.java)
                .setAction(if (ended) ACTION_FLUX_ENDED else ACTION_FLUX_STOP)
                // PendingIntent identity excludes extras; keep each capture's action distinct.
                .setData(Uri.parse("ohm-flux-screen://capture/$id"))
                .putExtra(EXTRA_FLUX_STOP_ID, id)
    }
}
