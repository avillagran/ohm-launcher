package cl.villagranquiroz.ohm_launcher

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/** Address advertised by the Omarchy desktop QR code. */
data class OmarchyPeer(
    val host: String,
    val port: Int,
    val id: String,
    val token: String = "",
) {
    init {
        require(host.isNotBlank() && host.none { it.isISOControl() || it.isWhitespace() || it == '[' || it == ']' })
        require(port in 1..65535)
        require(id.isNotBlank() && id.length <= 128 && id.none(Char::isISOControl))
        require(token.length <= 128 && token.none(Char::isISOControl))
    }

    private val authorityHost: String
        get() = if (host.contains(':')) "[$host]" else host

    val httpBaseUrl: String
        get() = "http://$authorityHost:$port"

    val webSocketUrl: String
        get() = "ws://$authorityHost:$port/omarchy/ws"

    fun toJson(): JSONObject = JSONObject()
        .put("ip", host)
        .put("port", port)
        .put("id", id)
        .put("token", token)

    companion object {
        fun fromJson(json: JSONObject): OmarchyPeer? {
            val host = json.opt("ip") as? String ?: return null
            val portNumber = json.opt("port") as? Number ?: return null
            val port = portNumber.toInt()
            if (portNumber.toDouble() != port.toDouble()) return null
            val id = json.opt("id") as? String ?: return null
            val token = json.opt("token") as? String ?: ""
            return runCatching { OmarchyPeer(host, port, id, token) }.getOrNull()
        }
    }
}

internal object OmarchyPeerRestorePolicy {
    fun canRestore(peer: OmarchyPeer, playStore: Boolean): Boolean =
        !playStore || peer.token.isNotBlank()
}

/** Strict parser for `omarchy://host:port?id=name&token=secret` discovery links. */
object OmarchyPeerUri {
    fun parse(value: String): OmarchyPeer? {
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return null
        }
        if (!uri.scheme.equals("omarchy", ignoreCase = true) ||
            uri.host.isNullOrBlank() ||
            uri.port !in 1..65535 ||
            uri.userInfo != null ||
            uri.fragment != null ||
            (!uri.path.isNullOrEmpty() && uri.path != "/")
        ) {
            return null
        }

        val host = uri.host.removeSurrounding("[", "]")
        val query = parseQuery(uri.rawQuery) ?: return null
        if (query.keys.any { it !in setOf("id", "token") }) return null
        val id = if (query.containsKey("id")) query.getValue("id") else host
        if (id.isBlank() || id.length > MAX_ID_LENGTH || id.any(Char::isISOControl)) return null
        val token = query["token"].orEmpty()
        if (token.length > MAX_TOKEN_LENGTH || token.any(Char::isISOControl)) return null

        return OmarchyPeer(host, uri.port, id, token)
    }

    private fun parseQuery(rawQuery: String?): Map<String, String>? {
        if (rawQuery == null) return emptyMap()
        if (rawQuery.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (part in rawQuery.split('&')) {
            if (part.isEmpty()) return null
            val separator = part.indexOf('=')
            val rawKey = if (separator < 0) part else part.substring(0, separator)
            val rawValue = if (separator < 0) "" else part.substring(separator + 1)
            val key = decode(rawKey) ?: return null
            val decoded = decode(rawValue) ?: return null
            if (key.isEmpty() || result.put(key, decoded) != null) return null
        }
        return result
    }

    private fun decode(value: String): String? = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        null
    }

    private const val MAX_ID_LENGTH = 128
    private const val MAX_TOKEN_LENGTH = 128
}

/** Thread-safe state shared by discovery, controls and protocol handlers. */
class OmarchyConnectionState {
    @Volatile
    var peer: OmarchyPeer? = null
        private set

    @Volatile
    var screenSharing: Boolean = false
        private set

    val isConnected: Boolean
        get() = peer != null

    @Synchronized
    fun connect(newPeer: OmarchyPeer, replace: Boolean = false): Boolean {
        if (peer != null && !replace) return false
        peer = newPeer
        screenSharing = false
        return true
    }

    @Synchronized
    fun disconnect(): Boolean {
        val changed = peer != null || screenSharing
        peer = null
        screenSharing = false
        return changed
    }

    @Synchronized
    fun setScreenSharing(active: Boolean): Boolean {
        if (active && peer == null) return false
        screenSharing = active
        return true
    }
}
