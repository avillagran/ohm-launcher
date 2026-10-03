package cl.villagranquiroz.ohm_launcher

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Separate raw/TLS workers keep a stalled close_notify from delaying transport interruption. */
internal class FluxControlCleanup(private val workers: Int = 4, private val pending: Int = 32) {
    private fun executor(kind: String) = ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(pending), { task ->
            Thread(task, "ohm-flux-control-$kind-close").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val raw = executor("raw")
    private val secure = executor("tls")

    fun raw(resource: Closeable): Boolean = schedule(raw, resource)
    fun secure(resource: Closeable): Boolean = schedule(secure, resource)

    private fun schedule(executor: ThreadPoolExecutor, resource: Closeable): Boolean = try {
        executor.execute { runCatching { resource.close() } }
        true
    } catch (_: RejectedExecutionException) {
        // A saturated pool never falls back to network I/O on the caller/UI thread.
        false
    }

    internal fun shutdown() { raw.shutdown(); secure.shutdown() }

    companion object { val shared = FluxControlCleanup() }
}

/** Logical closure precedes all cleanup and does not acquire the TLS writer's monitor. */
internal class FluxControlClose(
    private val raw: Closeable,
    private val secure: Closeable,
    private val cleanup: FluxControlCleanup = FluxControlCleanup.shared,
) {
    private val closed = AtomicBoolean(false)
    private val rawScheduled = AtomicBoolean(false)
    private val secureScheduled = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()

    fun abortRaw(): Boolean {
        if (!rawScheduled.compareAndSet(false, true)) return true
        return cleanup.raw(raw).also { if (!it) rawScheduled.set(false) }
    }

    fun close(revoke: () -> Unit): Boolean {
        if (closed.compareAndSet(false, true)) {
            try { revoke() } catch (error: Throwable) { schedule(); throw error }
        }
        return schedule()
    }

    private fun schedule(): Boolean {
        val rawAccepted = abortRaw()
        val secureAccepted = if (!secureScheduled.compareAndSet(false, true)) true else
            cleanup.secure(secure).also { if (!it) secureScheduled.set(false) }
        return rawAccepted && secureAccepted
    }
}
