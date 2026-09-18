package cl.villagranquiroz.ohm_launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service required by Android 14+ (API 34) to allow MediaProjection:
 * `MediaProjectionManager.getMediaProjection()` throws unless a foreground
 * service with type `mediaProjection` is running. The actual capture loop lives
 * in MainActivity; this service only holds the foreground state while sharing.
 */
class ScreenCaptureService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        val requestId = intent?.getLongExtra(ScreenCaptureForegroundRequests.EXTRA_REQUEST_ID, -1L) ?: -1L
        if (requestId >= 0L) ScreenCaptureForegroundRequests.signal(requestId)
        if (intent?.getBooleanExtra("stop", false) == true) {
            stopSelf()
        }
        return START_STICKY
    }


    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val chanId = "ohm_screen"
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val chan = NotificationChannel(chanId, "Omarchy Screen Share", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(chan)
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, chanId)
            .setContentTitle("Omarchy Screen Share")
            .setContentText("Compartiendo pantalla con el PC")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi!!)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val NOTIF_ID = 9002
    }
}
