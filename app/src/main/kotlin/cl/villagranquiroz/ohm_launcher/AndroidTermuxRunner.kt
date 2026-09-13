package cl.villagranquiroz.ohm_launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Optional Termux RUN_COMMAND bridge; callers retain the embedded-shell fallback. */
class AndroidTermuxRunner(context: Context) : TermuxRunner {
    private val appContext = context.applicationContext

    override fun run(command: String, args: List<String>?): ShellResult? {
        if (command.isBlank()) return null
        val requestId = nextRequest.incrementAndGet()
        val completed = CountDownLatch(1)
        var value: ShellResult? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != RESULT_ACTION || intent.getIntExtra("req", -1) != requestId) return
                value = ShellResult(
                    exitCode = intent.getIntExtra(EXTRA_EXIT_CODE, -1),
                    stdout = intent.getStringExtra(EXTRA_STDOUT).orEmpty(),
                    stderr = intent.getStringExtra(EXTRA_STDERR).orEmpty(),
                    via = "termux",
                )
                completed.countDown()
            }
        }
        return try {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(RESULT_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            val resultIntent = Intent(RESULT_ACTION)
                .setPackage(appContext.packageName)
                .putExtra("req", requestId)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(appContext, requestId, resultIntent, flags)
            appContext.sendBroadcast(
                Intent(RUN_ACTION)
                    .setPackage(TERMUX_PACKAGE)
                    .putExtra(EXTRA_COMMAND, command)
                    .putExtra(EXTRA_ARGUMENTS, args.orEmpty().toTypedArray())
                    .putExtra(EXTRA_WORKDIR, appContext.filesDir.absolutePath)
                    .putExtra(EXTRA_RESULT_SENDER, pending.intentSender)
                    .putExtra("req", requestId),
            )
            if (completed.await(4, TimeUnit.SECONDS)) value else null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    companion object {
        private const val RUN_ACTION = "com.termux.RUN_COMMAND"
        private const val RESULT_ACTION = "cl.villagranquiroz.ohm_launcher.TERMUX_RESULT"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val EXTRA_COMMAND = "com.termux.RUN_COMMAND_COMMAND"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_RESULT_SENDER = "com.termux.RUN_COMMAND_RESULT_INTENT_SENDER"
        private const val EXTRA_STDOUT = "com.termux.RUN_COMMAND_RESULT_BROADCAST_EXTRA_STDOUT"
        private const val EXTRA_STDERR = "com.termux.RUN_COMMAND_RESULT_BROADCAST_EXTRA_STDERR"
        private const val EXTRA_EXIT_CODE = "com.termux.RUN_COMMAND_RESULT_BROADCAST_EXTRA_EXIT_CODE"
        private val nextRequest = AtomicInteger()
    }
}
