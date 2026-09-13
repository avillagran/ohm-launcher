package cl.villagranquiroz.ohm_launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Foreground service that monitors the global system clipboard and pushes new
 * text clips to the connected Omarchy peer PC (PUT /omarchy/clipboard). This
 * lets the user copy text in any other app and have it synced to the desktop
 * without returning to the launcher. The peer (ip:port) is supplied via the
 * start Intent extras; when null the service idles.
 */
class ClipboardMonitorService : Service() {

    private var peerIp: String? = null
    private var peerPort: Int = 8753
    private var lastClip: String? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
        val text = clip.getItemAt(0).text?.toString() ?: return@OnPrimaryClipChangedListener
        if (text == lastClip) return@OnPrimaryClipChangedListener
        lastClip = text
        pushToPeer(text)
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.addPrimaryClipChangedListener(clipListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        peerIp = intent?.getStringExtra("peerIp")
        peerPort = intent?.getIntExtra("peerPort", 8753) ?: 8753
        if (intent?.getBooleanExtra("stop", false) == true) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pushToPeer(text: String) {
        val ip = peerIp ?: return
        scope.launch {
            try {
                val url = URL("http://$ip:$peerPort/omarchy/clipboard")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val body = "{\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"
                val wr = OutputStreamWriter(conn.outputStream)
                wr.write(body)
                wr.flush()
                wr.close()
                conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                Log.i(TAG, "clipboard pushed to peer $ip:$peerPort")
            } catch (e: Exception) {
                Log.w(TAG, "clipboard push failed: ${e.message}")
            }
        }
    }

    private fun buildNotification(): Notification {
        val chanId = "ohm_clipboard"
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val chan = NotificationChannel(chanId, "Omarchy Clipboard Sync", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(chan)
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, chanId)
            .setContentTitle("Omarchy Clipboard Sync")
            .setContentText("Copiando al portapapeles del PC")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pi!!)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "OhmClipboard"
        const val NOTIF_ID = 9001
    }
}
