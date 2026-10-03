package cl.villagranquiroz.ohm_launcher

import android.system.Os
import android.system.OsConstants
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One-shot, session/offer-token-owned handoff from a private staged payload to a SAF stream.
 * Construct only after explicit user consent. The caller must synchronously revoke on Forget,
 * replacement, disconnect, cancellation, and Activity destruction. openOutput runs on a separate
 * bounded worker, never the Activity's sole IO executor or its UI thread.
 */
internal class FluxIncomingFileSave(
    private val session: Any,
    private val token: String,
    private val currentSession: () -> Any?,
    private val currentToken: () -> String?,
    private val authorized: () -> Boolean,
    private val openOutput: () -> OutputStream?,
    private val timeoutNanos: Long = TimeUnit.MINUTES.toNanos(10),
    private val clockNanos: () -> Long = System::nanoTime,
    // Robolectric's Os descriptor/stat emulation is incomplete; tests supply an opener.
    private val stageOpener: ((File, Long) -> InputStream)? = null,
) {
    init { require(token.isNotEmpty() && timeoutNanos in 1..TimeUnit.MINUTES.toNanos(10)) }

    private val lock = Any()
    private var revoked = false
    private var started = false
    private var stage: File? = null
    private var output: OutputStream? = null
    private var deadline: Long = 0
    private var timeout: ScheduledFuture<*>? = null
    private var closeQueued = false
    private var deleteQueued = false
    private var cleanupOutstanding = 0
    private var workerFinished = false
    private var slotReleased = false

    private fun cleanup(action: () -> Unit) {
        cleanupOutstanding++
        cleanupWorkers.execute {
            try { runCatching { action() } }
            finally {
                synchronized(lock) {
                    cleanupOutstanding--
                    releaseIfFinished()
                }
            }
        }
    }

    private fun releaseIfFinished() {
        if (workerFinished && cleanupOutstanding == 0 && !slotReleased) {
            slotReleased = true
            slots.release()
        }
    }

    private fun checkLive() {
        check(!revoked && clockNanos() - deadline < 0 && currentSession() === session &&
            currentToken() == token && authorized()) { "Incoming Flux save no longer authorized" }
    }

    /** Transfers ownership of staged to this coordinator, even when admission fails. */
    fun start(staged: File, expectedBytes: Long, complete: (Result<Unit>) -> Unit): Boolean {
        synchronized(lock) {
            if (started) return false // ownership of the first file remains here
            started = true
            stage = staged
            if (!slots.tryAcquire()) {
                queueDelete()
                return false
            }
            deadline = clockNanos() + timeoutNanos
            timeout = deadlines.schedule({ revoke() }, timeoutNanos, TimeUnit.NANOSECONDS)
            workers.execute {
                val outcome = runCatching { copy(staged, expectedBytes) }
                val finalOutcome = synchronized(lock) {
                    timeout?.cancel(false)
                    timeout = null
                    if (outcome.isSuccess) {
                        val withdrawn = runCatching { checkLive() }.exceptionOrNull()
                        if (withdrawn != null) Result.failure(withdrawn) else outcome
                    } else outcome
                }
                finish(complete, finalOutcome, staged)
            }
            return true
        }
    }

    private fun finish(complete: (Result<Unit>) -> Unit, outcome: Result<Unit>, staged: File) {
        try {
            // Unlink before announcing completion; a failed unlink must not report success.
            val deleted = !staged.exists() || staged.delete()
            val result = if (deleted) outcome else Result.failure(IllegalStateException("Staged file deletion failed"))
            runCatching { complete(result) }
        } finally {
            synchronized(lock) {
                stage = null
                workerFinished = true
                releaseIfFinished()
            }
        }
    }

    private fun copy(staged: File, expectedBytes: Long) {
        require(expectedBytes in 1..256L * 1024 * 1024) { "Invalid payload length" }
        check(staged.isFile && staged.length() == expectedBytes) { "Staged payload length changed" }
        synchronized(lock) { checkLive() }
        // O_NOFOLLOW closes the path-swap window between validation and open. Validate the
        // opened inode, not just the path, before asking the document provider for output.
        val input = stageOpener?.invoke(staged, expectedBytes) ?: openVerifiedStage(staged, expectedBytes)
        input.use {
            synchronized(lock) { checkLive() }
            val stream = openOutput() ?: error("Document provider refused the output stream")
            try {
                synchronized(lock) { output = stream; checkLive() }
                val buffer = ByteArray(64 * 1024)
                var remaining = expectedBytes
                while (remaining > 0) {
                    synchronized(lock) { checkLive() }
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    synchronized(lock) { checkLive() }
                    if (count < 0) throw EOFException("Staged payload truncated")
                    if (count == 0) continue
                    // Provider write can block. Never hold the revocation monitor across it.
                    stream.write(buffer, 0, count)
                    remaining -= count
                    synchronized(lock) { checkLive() }
                }
                synchronized(lock) { checkLive() }
                stream.flush()
                synchronized(lock) { checkLive() }
            } finally {
                // A provider can block in close too. The deadline remains independent.
                try { stream.close() } finally {
                    synchronized(lock) { if (output === stream) output = null }
                }
            }
        }
    }

    private fun openVerifiedStage(staged: File, expectedBytes: Long): InputStream {
        val descriptor = Os.open(staged.absolutePath,
            OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        return try {
            val stat = Os.fstat(descriptor)
            check(OsConstants.S_ISREG(stat.st_mode) && stat.st_size == expectedBytes) {
                "Staged payload length changed"
            }
            FileInputStream(descriptor)
        } catch (error: Throwable) {
            try { Os.close(descriptor) } catch (closeError: Throwable) { error.addSuppressed(closeError) }
            throw error
        }
    }

    /** Withdraws authority synchronously; provider close and unlink never run on the caller. */
    fun revoke() {
        synchronized(lock) {
            if (revoked) return
            revoked = true
            timeout?.cancel(false)
            timeout = null
            if (!closeQueued) output?.let { stream ->
                closeQueued = true
                cleanup { stream.close() }
            }
            queueDelete()
        }
    }

    private fun queueDelete() {
        if (!deleteQueued) stage?.let { file ->
            deleteQueued = true
            cleanup { file.delete() }
        }
    }

    companion object {
        private val slots = Semaphore(4)
        private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(4)) { task ->
            Thread(task, "ohm-flux-save").apply { isDaemon = true }
        }
        private val cleanupWorkers = ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(8)) { task ->
            Thread(task, "ohm-flux-save-cleanup").apply { isDaemon = true }
        }
        private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "ohm-flux-save-deadline").apply { isDaemon = true }
        }
    }
}
