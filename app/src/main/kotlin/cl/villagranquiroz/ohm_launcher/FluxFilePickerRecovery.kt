package cl.villagranquiroz.ohm_launcher

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID

/** Owns one DocumentsUI selection, not a control-session instance. All probes run off the UI/IO executors. */
internal class FluxFilePickerRecovery<S : Any>(
    private val scheduler: Scheduler = background,
    private val editionAllowed: () -> Boolean,
    private val sessions: () -> Collection<S>,
    private val peerId: (S) -> String,
    private val certificateDer: (S) -> ByteArray,
    private val paired: (S) -> Boolean,
    private val open: (S) -> Boolean,
    private val live: (S) -> Boolean,
) {
    fun interface Cancel { fun cancel() }
    interface Scheduler {
        fun execute(task: () -> Unit)
        fun schedule(delayMs: Long, task: () -> Unit): Cancel
    }
    enum class Failure { CANCELLED, REVOKED, TIMED_OUT, DESTROYED, WRONG_EDITION }

    /** Opaque, one-use intent; DER is copied and deliberately absent from toString(). */
    class Token internal constructor(val ownerPeerId: String, private val der: ByteArray, val generation: Long) {
        internal fun matches(other: ByteArray): Boolean = der.contentEquals(other)
        override fun toString(): String = "FluxFilePickerRecovery.Token(redacted)"
    }

    private class Pending<S : Any>(
        val token: Token,
        var success: ((S) -> Unit)? = null,
        var failure: ((Failure) -> Unit)? = null,
        var timer: Cancel? = null,
    )

    private val lock = Any()
    private val generations = mutableMapOf<String, Long>()
    private var pending: Pending<S>? = null
    private var destroyed = false

    /** Call before launching the picker. A second picker is rejected until this one terminates. */
    fun begin(ownerPeerId: String, ownerCertificateDer: ByteArray): Token = synchronized(lock) {
        check(!destroyed && editionAllowed() && pending == null)
        require(ownerPeerId.isNotBlank() && ownerCertificateDer.isNotEmpty())
        Token(ownerPeerId, ownerCertificateDer.copyOf(), generations[ownerPeerId] ?: 0).also {
            pending = Pending(it)
        }
    }

    /** The picker returned a URI: dispatch once to a currently eligible session, or await reconnect. */
    fun complete(token: Token, onReady: (S) -> Unit, onFailure: (Failure) -> Unit): Boolean {
        synchronized(lock) {
            val entry = pending ?: return false
            if (entry.token !== token || entry.success != null) return false
            entry.success = onReady
            entry.failure = onFailure
            entry.timer = scheduler.schedule(WAIT_MS) { terminate(token, Failure.TIMED_OUT) }
        }
        signal()
        return true
    }

    /** Call for registration, disconnection, pairing and edition changes. No polling or blocking wait. */
    fun signal() { scheduler.execute(::probe) }

    /** Null URI / failed launch; safe to call before or after complete. */
    fun cancel(token: Token) { terminate(token, Failure.CANCELLED) }

    /** Forget/unpair must call this even if the peer is subsequently pinned to the same certificate. */
    fun revokePeer(id: String) {
        val token = synchronized(lock) {
            generations[id] = (generations[id] ?: 0) + 1
            pending?.token?.takeIf { it.ownerPeerId == id }
        }
        token?.let { terminate(it, Failure.REVOKED) }
    }

    fun destroy() {
        val token = synchronized(lock) { destroyed = true; pending?.token }
        token?.let { terminate(it, Failure.DESTROYED) }
    }

    private fun probe() {
        val entry = synchronized(lock) { pending?.takeIf { it.success != null } } ?: return
        if (!editionAllowed()) { terminate(entry.token, Failure.WRONG_EDITION); return }
        // Never trust a stale session reference captured at picker launch.
        val candidate = sessions().firstOrNull {
            peerId(it) == entry.token.ownerPeerId && entry.token.matches(certificateDer(it)) &&
                paired(it) && open(it) && live(it)
        } ?: return
        val dispatch = synchronized(lock) {
            if (pending !== entry || destroyed ||
                (generations[entry.token.ownerPeerId] ?: 0L) != entry.token.generation || !editionAllowed()) null
            else {
                pending = null
                entry.timer?.cancel()
                entry.success
            }
        }
        dispatch?.invoke(candidate)
    }

    private fun terminate(token: Token, reason: Failure) {
        val failure = synchronized(lock) {
            val entry = pending?.takeIf { it.token === token } ?: return
            pending = null
            entry.timer?.cancel()
            entry.failure
        }
        if (failure != null) scheduler.execute { failure(reason) }
    }

    companion object {
        const val WAIT_MS = 30_000L
        private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "ohm-flux-picker-recovery").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private val background = object : Scheduler {
            override fun execute(task: () -> Unit) { executor.execute(task) }
            override fun schedule(delayMs: Long, task: () -> Unit): Cancel {
                val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
                return Cancel { future.cancel(false) }
            }
        }
    }
}

/** Connects the picker callback to recovery without transferring ownership of the original link. */
internal class FluxFilePickerBridge<S : Any, U : Any>(
    private val recovery: FluxFilePickerRecovery<S>,
    private val peerId: (S) -> String,
    private val certificateDer: (S) -> ByteArray,
    private val close: (S) -> Unit,
    private val submit: (S, U, (Result<Unit>) -> Unit) -> Unit,
    private val delivered: (Result<Unit>) -> Unit,
    private val failed: (FluxFilePickerRecovery.Failure) -> Unit,
    private val revoke: (S) -> Unit = {},
    private val closeExecutor: ((() -> Unit) -> Unit) = { task -> closePool.execute(task) },
) {
    /** Unique registry key and callback identity for exactly one picker invocation. */
    class Launch internal constructor(val key: String = "flux-file-picker-${UUID.randomUUID()}")

    private class Selection<S : Any>(val launch: Launch, val token: FluxFilePickerRecovery.Token, val owner: S, val closeAfter: Boolean,
                                      var awaitingResult: Boolean = true, var invalidated: Boolean = false)
    private val lock = Any()
    private var pending: Selection<S>? = null

    private fun closeOwner(owner: S, closeAfter: Boolean) {
        if (!closeAfter) return
        revoke(owner) // Invalidate the transfer gate before potentially blocking socket close.
        closeExecutor { runCatching { close(owner) } }
    }
    private fun closeOwner(selection: Selection<S>) = closeOwner(selection.owner, selection.closeAfter)

    /** A rejected transient owner must close without blocking DocumentsUI's main-thread caller. */
    fun reject(owner: S, closeAfter: Boolean) {
        closeOwner(owner, closeAfter)
    }

    fun launch(owner: S, closeAfter: Boolean): Launch? = synchronized(lock) {
        if (pending != null) return null
        Launch().also { pending = Selection(it, recovery.begin(peerId(owner), certificateDer(owner)), owner, closeAfter) }
    }

    fun returned(launch: Launch, uri: U?) {
        val selection = synchronized(lock) {
            pending?.takeIf { it.launch === launch && it.awaitingResult }?.also {
                it.awaitingResult = false
                if (it.invalidated) pending = null
            }
        } ?: return
        if (selection.invalidated) return
        if (uri == null) {
            synchronized(lock) { if (pending === selection) pending = null }
            recovery.cancel(selection.token)
            closeOwner(selection)
            return
        }
        recovery.complete(selection.token, onReady@{ current ->
            val owned = synchronized(lock) {
                if (pending !== selection) false else { pending = null; true }
            }
            if (!owned) return@onReady
            if (current !== selection.owner) closeOwner(selection)
            // submitFile retains its exact-live transfer gate; recovery never retries after dispatch.
            runCatching {
                submit(current, uri) { result ->
                    delivered(result)
                    if (current === selection.owner) closeOwner(selection)
                }
            }.onFailure { error ->
                delivered(Result.failure(error))
                if (current === selection.owner) closeOwner(selection)
            }
        }, { reason ->
            val owned = synchronized(lock) {
                if (pending !== selection) false else { pending = null; true }
            }
            if (owned) {
                closeOwner(selection)
                failed(reason)
            }
        })
    }

    fun registered() = recovery.signal()
    fun disconnected() = recovery.signal()
    fun revoked(id: String) {
        val selection = synchronized(lock) {
            pending?.takeIf { it.token.ownerPeerId == id && !it.invalidated }?.also {
                it.invalidated = true
                if (!it.awaitingResult) pending = null
            }
        }
        recovery.revokePeer(id)
        if (selection != null) {
            closeOwner(selection)
            failed(FluxFilePickerRecovery.Failure.REVOKED)
        }
    }
    fun destroy() {
        val selection = synchronized(lock) { pending?.takeIf { !it.invalidated }?.also { it.invalidated = true; pending = null } }
        recovery.destroy()
        if (selection != null) {
            closeOwner(selection)
            failed(FluxFilePickerRecovery.Failure.DESTROYED)
        }
    }
    companion object {
        private val closePool = Executors.newCachedThreadPool { task ->
            Thread(task, "ohm-flux-picker-close").apply { isDaemon = true }
        }
    }
}
