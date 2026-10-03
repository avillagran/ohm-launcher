package cl.villagranquiroz.ohm_launcher

import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A view-scoped, bounded writer. [onRevoke] must withdraw authority without blocking. */
internal class FluxRemoteInputQueue(
    private val send: (FluxRemoteInputProtocol.Action) -> Unit,
    private val onAbort: () -> Unit,
    private val closeGraceMs: Long = 250L,
    private val onRevoke: () -> Unit = {},
) {
    companion object {
        const val MAX_PENDING = 32
        private const val MAX_WRITERS = 4
        private val writerSlots = AtomicInteger()
        private fun reserveWriter(): Boolean {
            while (true) {
                val count = writerSlots.get()
                if (count >= MAX_WRITERS) return false
                if (writerSlots.compareAndSet(count, count + 1)) return true
            }
        }
        private val deadlineExecutor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "flux-input-deadline").apply { isDaemon = true }
        }
        private val abortExecutor = ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(4), { task -> Thread(task, "flux-input-abort").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }

    init { require(closeGraceMs >= 0) }

    private val lock = Object()
    private val pending = ArrayDeque<FluxRemoteInputProtocol.Action>()
    private val aborted = AtomicBoolean(false)
    private val slotReleased = AtomicBoolean(false)
    val accepted: Boolean = reserveWriter()
    private var started = false
    private var closing = false
    // Set before dispatching hold, since a blocked writer may already have transmitted it.
    private var held = false

    val pendingCount: Int get() = synchronized(lock) { pending.size }
    private fun releaseWriter() {
        if (accepted && slotReleased.compareAndSet(false, true)) writerSlots.decrementAndGet()
    }

    /** False means the action was not admitted; callers must never assume a rejected control was sent. */
    fun offer(action: FluxRemoteInputProtocol.Action): Boolean = synchronized(lock) {
        if (!accepted || closing) return false
        val type = typeOf(action)
        if (type == Motion.MOVE || type == Motion.SCROLL) {
            // Replace only the adjacent motion of the same kind; never cross a control boundary.
            if (pending.isNotEmpty() && typeOf(pending.last) == type) {
                val previous = pending.removeLast().body()
                val next = action.body()
                val dx = previous.getDouble("dx") + next.getDouble("dx")
                val dy = previous.getDouble("dy") + next.getDouble("dy")
                if (dx == 0.0 && dy == 0.0) return true
                // Keep each frame within the protocol limit; excess motion is disposable.
                pending.addLast(if (dx in -2000.0..2000.0 && dy in -2000.0..2000.0) {
                    if (type == Motion.SCROLL) FluxRemoteInputProtocol.scroll(dx, dy)
                    else FluxRemoteInputProtocol.move(dx, dy)
                } else action)
                return true
            }
        }
        val ordinaryLimit = MAX_PENDING - 1 // Keep one slot for a release at saturation.
        if (pending.size >= ordinaryLimit && type != Motion.RELEASE) {
            // A control can displace disposable motion, but never a previously admitted control.
            if (type == Motion.CONTROL || type == Motion.HOLD) {
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val candidate = typeOf(iterator.next())
                    if (candidate == Motion.MOVE || candidate == Motion.SCROLL) {
                        iterator.remove()
                        break
                    }
                }
            }
            if (pending.size >= ordinaryLimit) return false
        }
        if (pending.size >= MAX_PENDING) {
            if (type != Motion.RELEASE) return false
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val candidate = typeOf(iterator.next())
                if (candidate == Motion.MOVE || candidate == Motion.SCROLL) {
                    iterator.remove()
                    break
                }
            }
            if (pending.size >= MAX_PENDING) return false
        }
        pending.addLast(action)
        if (!started) {
            started = true
            try {
                Thread({ try { drain() } finally { releaseWriter() } }, "flux-input-writer")
                    .apply { isDaemon = true }.start()
            } catch (e: RuntimeException) {
                closing = true
                pending.clear()
                releaseWriter()
                abort()
                return false
            }
        }
        lock.notifyAll()
        true
    }

    /** Discard queued work and put release first if a hold may have reached the peer. Never waits for IO. */
    fun close() {
        if (!accepted) return
        val releaseNeeded = synchronized(lock) {
            if (closing) return
            closing = true
            pending.clear()
            if (held) pending.addLast(FluxRemoteInputProtocol.release())
            lock.notifyAll()
            if (!started) releaseWriter()
            held
        }
        if (releaseNeeded) {
            deadlineExecutor.schedule({ abort() }, closeGraceMs, TimeUnit.MILLISECONDS)
        } else abort()
    }

    private fun drain() {
        while (true) {
            val action = synchronized(lock) {
                while (pending.isEmpty() && !closing) lock.wait()
                if (pending.isEmpty()) return
                pending.removeFirst().also { if (typeOf(it) == Motion.HOLD) held = true }
            }
            try {
                // A deadline or write error revokes the grant; do not send a stale queued release.
                if (aborted.get()) return
                send(action)
                synchronized(lock) {
                    if (typeOf(action) == Motion.RELEASE) held = false
                }
            } catch (_: Exception) {
                synchronized(lock) { closing = true; pending.clear(); lock.notifyAll() }
                abort()
                return
            }
            if (synchronized(lock) { closing && pending.isEmpty() }) {
                abort()
                return
            }
        }
    }

    private fun abort() {
        if (aborted.compareAndSet(false, true)) {
            // Withdraw authority even when asynchronous cleanup is saturated.
            onRevoke()
            try { abortExecutor.execute { onAbort() } }
            catch (_: RejectedExecutionException) {
                // Revocation has already completed. Never block the UI on saturated cleanup.
            }
        }
    }

    private enum class Motion { MOVE, SCROLL, HOLD, RELEASE, CONTROL }
    private fun typeOf(action: FluxRemoteInputProtocol.Action): Motion {
        val body = action.body()
        return when {
            body.has("singlehold") -> Motion.HOLD
            body.has("singlerelease") -> Motion.RELEASE
            body.has("scroll") -> Motion.SCROLL
            body.has("dx") -> Motion.MOVE
            else -> Motion.CONTROL
        }
    }
}
