package cl.villagranquiroz.ohm_launcher

import java.io.Closeable

/** An IO starter may attach only while its exact Activity ownership is still live. */
internal class FluxResponderLease internal constructor(val owner: Any) {
    @Volatile var active = true
        private set
    private var responder: Closeable? = null

    @Synchronized fun attach(value: Closeable): Boolean {
        if (!active || responder != null) return false
        responder = value
        return true
    }

    @Synchronized internal fun invalidate(): Closeable? {
        active = false
        return responder.also { responder = null }
    }
}

/** One listener identity per process. HOME remains its owner while other tasks open. */
internal class FluxResponderOwnerRegistry(private val capturePinned: () -> Boolean) {
    private class Entry(val owner: Any, var home: Boolean, var order: Long,
                        val granted: (FluxResponderLease) -> Unit,
                        val revoked: (FluxResponderLease) -> Unit) {
        var failed = false
    }
    private data class Transition(val old: Entry?, val oldLease: FluxResponderLease?,
                                  val close: Closeable?, val next: Entry?, val lease: FluxResponderLease?)
    class Outbound internal constructor()
    private val lock = Any()
    private val entries = java.util.IdentityHashMap<Any, Entry>()
    private val outbound = mutableSetOf<Outbound>()
    private var order = 0L
    private var current: Entry? = null
    @Volatile private var currentLease: FluxResponderLease? = null

    fun request(owner: Any, home: Boolean, granted: (FluxResponderLease) -> Unit,
                revoked: (FluxResponderLease) -> Unit) {
        val transition = synchronized(lock) {
            val entry = entries[owner] ?: Entry(owner, home, ++order, granted, revoked).also { entries[owner] = it }
            entry.home = entry.home || home
            entry.order = ++order
            entry.failed = false
            transition()
        }
        apply(transition)
    }

    fun release(owner: Any) {
        val transition = synchronized(lock) { entries.remove(owner); transition() }
        apply(transition)
    }

    fun failed(owner: Any, lease: FluxResponderLease) {
        val transition = synchronized(lock) {
            if (!isCurrent(owner, lease)) return
            entries[owner]?.failed = true
            transition()
        }
        apply(transition)
    }

    fun reconsider() { apply(synchronized(lock) { transition() }) }

    fun isCurrent(owner: Any, lease: FluxResponderLease): Boolean =
        lease.active && lease.owner === owner && currentLease === lease

    fun beginOutbound(): Outbound? = synchronized(lock) {
        if (capturePinned()) null else Outbound().also { outbound.add(it) }
    }
    fun finishOutbound(ticket: Outbound) { synchronized(lock) { outbound.remove(ticket) } }
    fun canStartCapture(): Boolean = synchronized(lock) { outbound.isEmpty() && !capturePinned() }

    private fun transition(): Transition? {
        val available = entries.values.filter { !it.failed }
        val old = current
        val pinned = capturePinned()
        val retained = old?.takeIf { entries[it.owner] === it && (!it.failed || pinned) }
        val next = if (pinned) retained else {
            available.filter { it.home }.maxByOrNull { it.order }
                ?: retained ?: available.maxByOrNull { it.order }
        }
        if (next === current) return null
        val oldLease = currentLease
        val close = oldLease?.invalidate() // Withdraw permission before any owner callback or network cleanup.
        val lease = next?.let { FluxResponderLease(it.owner) }
        current = next
        currentLease = lease
        return Transition(old, oldLease, close, next, lease)
    }

    private fun apply(change: Transition?) {
        if (change == null) return
        // Callbacks may reenter the registry. No network or Activity callback holds its monitor.
        change.oldLease?.let { runCatching { change.old?.revoked?.invoke(it) } }
        change.close?.let { runCatching { it.close() } }
        change.lease?.takeIf { isCurrent(it.owner, it) }?.let { lease ->
            runCatching { change.next?.granted?.invoke(lease) }
                .onFailure { failed(lease.owner, lease) }
        }
    }
}

internal object FluxResponderOwners {
    val registry = FluxResponderOwnerRegistry { FluxScreenStops.currentId() != null }
}
