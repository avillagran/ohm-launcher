package cl.villagranquiroz.ohm_launcher

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Give an unpair notice a bounded opportunity to write before aborting the control socket. */
internal class FluxPairCancellation(workers: Int = 4, pending: Int = 4) {
    private val writes = ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(pending), { task ->
            Thread(task, "ohm-flux-pair-cancel").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "ohm-flux-pair-cancel-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun cancel(send: () -> Unit, finished: (Boolean) -> Unit, timeoutMs: Long = 2_000) {
        require(timeoutMs > 0)
        val completed = AtomicBoolean(false)
        val timer = AtomicReference<java.util.concurrent.ScheduledFuture<*>?>()
        fun finish(sent: Boolean) {
            if (completed.compareAndSet(false, true)) {
                timer.get()?.cancel(false)
                finished(sent)
            }
        }
        timer.set(deadlines.schedule({ finish(false) }, timeoutMs, TimeUnit.MILLISECONDS))
        try {
            writes.execute {
                if (!completed.get()) finish(runCatching(send).isSuccess)
            }
        } catch (_: RejectedExecutionException) { finish(false) }
    }

    internal fun shutdown() { writes.shutdown(); deadlines.shutdown() }

    companion object { val shared = FluxPairCancellation() }
}
