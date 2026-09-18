package cl.villagranquiroz.ohm_launcher

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Correlates each foreground-service start with its own ready callback. */
internal class ScreenCaptureForegroundGate {
    internal data class Request(val id: Long)

    private val nextId = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, () -> Unit>()

    fun createRequest(onReady: () -> Unit): Request {
        val id = nextId.incrementAndGet()
        pending[id] = onReady
        return Request(id)
    }

    fun signal(id: Long): Boolean {
        val callback = pending.remove(id) ?: return false
        callback()
        return true
    }

    fun cancel(id: Long): Boolean = pending.remove(id) != null
}

internal object ScreenCaptureForegroundRequests {
    const val EXTRA_REQUEST_ID = "screen_capture_foreground_request_id"
    private val gate = ScreenCaptureForegroundGate()

    fun createRequest(onReady: () -> Unit): ScreenCaptureForegroundGate.Request =
        gate.createRequest(onReady)

    fun signal(id: Long): Boolean = gate.signal(id)

    fun cancel(id: Long): Boolean = gate.cancel(id)
}
