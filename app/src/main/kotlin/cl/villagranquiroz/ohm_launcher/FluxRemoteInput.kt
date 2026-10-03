package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** An old link's loss must not dismiss the replacement link's approval prompt. */
internal object FluxInputApprovalState {
    fun <S : Any> lost(pending: S?, disconnected: S): Boolean = pending === disconnected
}

/** Send-only KDE Connect mousepad actions. Never accepts arbitrary caller-supplied JSON. */
internal object FluxRemoteInputProtocol {
    const val REQUEST = "kdeconnect.mousepad.request"
    const val STATUS = "flux.input"
    private const val MAX_DELTA = 2000.0
    private const val MAX_FRAME_BYTES = 8192
    private const val MAX_TEXT_CODEPOINTS = 4096

    class Action private constructor(private val fields: Map<String, Any>) {
        internal fun body(): JSONObject = JSONObject(fields)
        companion object {
            internal fun from(body: JSONObject): Action = Action(body.keys().asSequence().associateWith { body.get(it) })
        }
    }
    enum class Click(val field: String) {
        LEFT("singleclick"), DOUBLE("doubleclick"), MIDDLE("middleclick"), RIGHT("rightclick")
    }
    enum class SpecialKey(val code: Int) {
        BACKSPACE(1), TAB(2), LEFT(4), UP(5), RIGHT(6), DOWN(7), PAGE_UP(8), PAGE_DOWN(9),
        HOME(10), END(11), ENTER(12), DELETE(13), ESCAPE(14), F1(21), F2(22), F3(23),
        F4(24), F5(25), F6(26), F7(27), F8(28), F9(29), F10(30), F11(31), F12(32)
    }
    data class Modifiers(val ctrl: Boolean = false, val alt: Boolean = false,
                         val shift: Boolean = false, val superKey: Boolean = false)

    private fun deltas(dx: Double, dy: Double): JSONObject {
        require(dx.isFinite() && dy.isFinite() && dx in -MAX_DELTA..MAX_DELTA && dy in -MAX_DELTA..MAX_DELTA)
        require(dx != 0.0 || dy != 0.0)
        return JSONObject().put("dx", dx).put("dy", dy)
    }
    fun move(dx: Double, dy: Double): Action = Action.from(deltas(dx, dy))
    fun scroll(dx: Double, dy: Double): Action = Action.from(deltas(dx, dy).put("scroll", true))
    fun click(button: Click): Action = Action.from(JSONObject().put(button.field, true))
    fun hold(): Action = Action.from(JSONObject().put("singlehold", true))
    fun release(): Action = Action.from(JSONObject().put("singlerelease", true))
    private fun withMods(body: JSONObject, modifiers: Modifiers): Action {
        if (modifiers.ctrl) body.put("ctrl", true)
        if (modifiers.alt) body.put("alt", true)
        if (modifiers.shift) body.put("shift", true)
        if (modifiers.superKey) body.put("super", true)
        return Action.from(body)
    }
    fun special(key: SpecialKey, modifiers: Modifiers = Modifiers()): Action =
        withMods(JSONObject().put("specialKey", key.code), modifiers)

    fun text(value: String, modifiers: Modifiers = Modifiers()): Action {
        val count = value.codePointCount(0, value.length)
        require(count in 1..MAX_TEXT_CODEPOINTS) { "Text must be nonempty and bounded" }
        require(value.codePoints().allMatch { cp ->
            !Character.isISOControl(cp) && cp != 0xfffd && cp !in 0xd800..0xdfff
        }) { "Text contains invalid Unicode or control characters" }
        return withMods(JSONObject().put("key", value), modifiers)
    }

    /** Exact peer status; a missing/non-boolean enabled field never grants input. */
    fun enabled(type: String, body: JSONObject, requestId: String): Boolean {
        val status = FluxWire.remoteInputState(type, body)
        require(status?.requestId == requestId)
        return status.enabled
    }

    fun packet(action: Action, id: Long = System.currentTimeMillis()): String {
        require(id >= 0)
        val frame = JSONObject().put("id", id).put("type", REQUEST).put("body", action.body()).toString() + "\n"
        require(frame.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES) { "Remote input frame too large" }
        return frame
    }
}

/** One view/credential grant for one exact mapped, pinned TLS control session. */
internal class FluxRemoteInputGate<T : Any>(
    private val peers: ConcurrentHashMap<String, T>, private val id: String, private val session: T,
    private val serializer: Any, private val pairedOpen: () -> Boolean,
    private val peerAcceptsRequest: () -> Boolean, private val directEdition: () -> Boolean,
    private val elapsedMs: () -> Long, private val abortWrite: () -> Unit,
) {
    private val state = Any()
    private var generation = 0L
    private var grantedAt = -1L
    private var viewOpen = false
    private var peerEnabled = false
    private var requestId: String? = null
    private val writing = AtomicBoolean(false)

    private fun current(): Boolean = peers[id] === session && pairedOpen() &&
        peerAcceptsRequest() && directEdition()

    fun beginRequest(id: String) {
        require(FluxWire.validRequestId(id))
        synchronized(peers) {
            synchronized(state) {
                check(current() && requestId == null)
                requestId = id
                peerEnabled = false
                generation++
                viewOpen = false
                grantedAt = -1
            }
        }
    }

    /** Withdraw before queuing network cancellation; an old cancel cannot revoke a new request. */
    fun cancelRequest(id: String) {
        val revoked = synchronized(state) {
            if (requestId != id) false else {
                requestId = null
                peerEnabled = false
                generation++
                viewOpen = false
                grantedAt = -1
                true
            }
        }
        if (revoked && writing.get()) abortWrite()
    }

    fun activeRequestId(): String? = synchronized(state) { requestId }

    /** Call only for a status packet read from this authenticated session's TLS link. */
    fun receiveStatus(type: String, body: JSONObject): Boolean? {
        val id = body.opt("requestId") as? String ?: return null
        if (type != FluxWire.REMOTE_INPUT_STATE || !FluxWire.validRequestId(id)) return null
        val enabled = FluxWire.remoteInputState(type, body)?.enabled ?: false
        synchronized(peers) {
            synchronized(state) {
                if (!current() || requestId != id) return null
                peerEnabled = enabled
                if (!enabled) { generation++; viewOpen = false; grantedAt = -1 }
            }
        }
        if (!enabled && writing.get()) abortWrite()
        return enabled
    }

    /** Call only after Android confirms a secure screen lock via device credential. */
    fun credentialVerified(): Long = synchronized(peers) {
        synchronized(state) {
            check(current() && requestId != null && peerEnabled) { "Remote input not allowed by the desktop" }
            generation++
            grantedAt = elapsedMs()
            check(grantedAt >= 0)
            viewOpen = true
            generation
        }
    }

    fun closeView() {
        synchronized(state) { generation++; viewOpen = false; grantedAt = -1 }
        if (writing.get()) abortWrite()
    }

    private fun authorized(token: Long): Boolean {
        val now = elapsedMs()
        return current() && requestId != null && peerEnabled && viewOpen && token == generation &&
            grantedAt >= 0 && now >= grantedAt && now - grantedAt < 300_000L
    }

    /** Serialize on the same control-socket monitor used by other packets; never hold the map during IO. */
    fun send(token: Long, action: FluxRemoteInputProtocol.Action, write: (String) -> Unit) {
        val frame = FluxRemoteInputProtocol.packet(action)
        synchronized(serializer) {
            synchronized(peers) {
                synchronized(state) { check(authorized(token)) { "Remote input authorization expired" } }
            }
            writing.set(true)
            try {
                synchronized(peers) {
                    synchronized(state) { check(authorized(token)) { "Remote input authorization expired" } }
                }
                write(frame)
            } finally { writing.set(false) }
        }
    }
}
