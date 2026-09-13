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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        // Signal the activity that the FGS is up so it can create the projection.
        foregroundLatch.countDown()
        if (intent?.getBooleanExtra("stop", false) == true) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        foregroundLatch = CountDownLatch(1)
        super.onDestroy()
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

        /** Latch that opens once the service reached the foreground state. */
        @Volatile var foregroundLatch = CountDownLatch(1)

        /** Await the foreground state (max [timeoutMs]); true when reached. */
        fun awaitForeground(timeoutMs: Long): Boolean =
            try { foregroundLatch.await(timeoutMs, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { false }
    }
}
