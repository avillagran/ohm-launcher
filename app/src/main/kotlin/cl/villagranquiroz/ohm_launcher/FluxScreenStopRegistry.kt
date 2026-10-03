package cl.villagranquiroz.ohm_launcher

import android.os.Handler
import android.os.Looper
import java.security.SecureRandom

/** Only the exact capture that issued a stop affordance may consume it. */
internal class FluxScreenStopRegistry(
    initialId: Long = SecureRandom().nextLong().ushr(2),
    private val dispatch: (() -> Unit) -> Unit,
) {
    private data class Entry(val owner: Any, val id: Long, val close: () -> Unit)
    // PendingIntents may outlive this process. Reserve a new random ID range each time.
    private var nextId = initialId
    private var current: Entry? = null
    init { require(initialId >= 0L) }

    @Synchronized fun register(owner: Any, close: () -> Unit): Long? {
        if (current != null || nextId == Long.MAX_VALUE) return null
        val id = ++nextId
        current = Entry(owner, id, close)
        return id
    }

    @Synchronized fun currentId(): Long? = current?.id
    @Synchronized fun isCurrent(id: Long): Boolean = current?.id == id
    @Synchronized fun canSendStop(id: Long): Boolean = current == null || current?.id == id

    @Synchronized fun unregister(owner: Any, id: Long): Boolean {
        if (current?.owner !== owner || current?.id != id) return false
        current = null
        return true
    }

    fun stop(id: Long): Boolean {
        val stopped = synchronized(this) {
            current?.takeIf { it.id == id }?.also { current = null }
        } ?: return false
        dispatch(stopped.close)
        return true
    }
}

/** Process-wide stop metadata; does not publish any peer or plugin authority. */
internal object FluxScreenStops {
    private val main = Handler(Looper.getMainLooper())
    private val registry = FluxScreenStopRegistry { close ->
        if (Looper.myLooper() == Looper.getMainLooper()) close() else main.post(close)
    }

    fun register(owner: Any, close: () -> Unit): Long? =
        if (BuildConfig.PLAY_STORE_DISTRIBUTION) null else registry.register(owner, close)
    fun currentId(): Long? = if (BuildConfig.PLAY_STORE_DISTRIBUTION) null else registry.currentId()
    fun isCurrent(id: Long): Boolean = !BuildConfig.PLAY_STORE_DISTRIBUTION && registry.isCurrent(id)
    fun canSendStop(id: Long): Boolean = !BuildConfig.PLAY_STORE_DISTRIBUTION && registry.canSendStop(id)
    fun unregister(owner: Any, id: Long): Boolean = registry.unregister(owner, id)
    fun stop(id: Long): Boolean = !BuildConfig.PLAY_STORE_DISTRIBUTION && registry.stop(id)
}
