package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.security.cert.X509Certificate
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle

/** The namespace chosen before TLS must be confirmed by the authenticated identity. */
internal enum class FluxDialect(val identityType: String, val serviceType: String, val verificationBytes: Int) {
    LEGACY_KDE("kdeconnect.identity", "_kdeconnect._udp.", 4),
    FLUX("flux.identity", "_flux._udp.", 8);
    companion object {
        fun service(type: String): FluxDialect? = entries.firstOrNull { it.serviceType.trimEnd('.') == type.trimEnd('.') }
    }
}

/** Version 8, with a fixed dialect for each authenticated control link. */
internal object FluxWire {
    const val IDENTITY = "kdeconnect.identity"
    const val PAIR = "kdeconnect.pair"
    const val SHARE = "kdeconnect.share.request"
    const val PING = "kdeconnect.ping"
    const val CLIPBOARD = "kdeconnect.clipboard"
    const val BATTERY = "kdeconnect.battery"
    const val NOTIFICATION = "kdeconnect.notification"
    const val SCREEN = "flux.screen"
    const val TUNNEL = "flux.tunnel"
    const val REMOTE_INPUT_REQUEST = "kdeconnect.mousepad.request"
    const val REMOTE_INPUT_STATE = "flux.input"
    const val INPUT_APPROVAL = "flux.input.request.v2"
    const val OMARCHY_THEME = FluxThemePacket.TYPE
    const val OMARCHY_THEME_SELECT = FluxThemeSelection.SELECT
    const val OMARCHY_THEME_SELECTED = FluxThemeSelection.SELECTED
    const val MPRIS = FluxMediaProtocol.STATE
    const val MPRIS_REQUEST = FluxMediaProtocol.REQUEST
    fun validId(id: String) = Regex("[a-zA-Z0-9_-]{32,38}").matches(id)

    private val renamed = listOf("identity", "pair", "ping", "battery", "clipboard", "clipboard.connect",
        "share.request", "share.request.update", "notification", "notification.request", "notification.reply",
        "notification.action", "findmyphone.request", "runcommand", "runcommand.request", "mpris", "mpris.request",
        "sftp", "sftp.request", "sms.messages", "sms.request", "sms.request_conversations", "sms.request_conversation",
        "telephony", "mousepad.request").associate { "kdeconnect.$it" to "flux.$it" }
    private val canonical = renamed.entries.associate { (legacy, native) -> native to legacy }
    fun type(type: String, dialect: FluxDialect): String = if (dialect == FluxDialect.FLUX) renamed[type] ?: type else type
    fun packet(type: String, body: JSONObject, dialect: FluxDialect = FluxDialect.LEGACY_KDE): String = JSONObject()
        .put("id", System.currentTimeMillis()).put("type", FluxWire.type(type, dialect)).put("body", body).toString() + "\n"

    /** Local helpers keep their canonical names; only the TLS boundary adapts the packet. */
    fun outgoing(frame: String, dialect: FluxDialect): String {
        val packet = JSONObject(frame)
        packet.put("type", type(packet.getString("type"), dialect))
        return packet.toString() + "\n"
    }
    fun incoming(frame: String, dialect: FluxDialect): JSONObject? {
        val packet = JSONObject(frame)
        val actual = packet.opt("type") as? String ?: return null
        if (dialect == FluxDialect.FLUX && actual.startsWith("kdeconnect.") ||
            dialect == FluxDialect.LEGACY_KDE && actual in canonical) return null
        packet.put("type", if (dialect == FluxDialect.FLUX) canonical[actual] ?: actual else actual)
        return packet
    }
    fun dialect(line: String): FluxDialect = FluxDialect.entries.firstOrNull {
        JSONObject(line).opt("type") == it.identityType
    } ?: throw IllegalArgumentException("Unsupported Flux identity namespace")

    data class PlainIdentity(val id: String, val dialect: FluxDialect)
    fun plainIdentity(line: String, ownId: String): PlainIdentity {
        val dialect = dialect(line)
        val body = JSONObject(line).getJSONObject("body")
        val id = body.getString("deviceId")
        require(validId(id) && id != ownId && body.opt("protocolVersion") is Int && body.getInt("protocolVersion") == 8)
        if (body.has("targetDeviceId")) require(body.opt("targetDeviceId") == ownId)
        if (body.has("targetProtocolVersion")) require(body.opt("targetProtocolVersion") == 8 || body.opt("targetProtocolVersion") == "8")
        return PlainIdentity(id, dialect)
    }

    /** Receiver-owned, one-use reverse payload listener on the authenticated control link. */
    fun tunnelPacket(id: String, port: Int): String {
        require(Regex("[0-9a-f]{24}").matches(id) && port in 1739..1764)
        return packet(TUNNEL, JSONObject().put("id", id).put("port", port))
    }

    /** A separate TLS payload socket, announced on the authenticated control link. */
    fun payloadPacket(type: String, body: JSONObject, size: Long, port: Int): String {
        require(size in 1..FluxFileTransfer.MAX_BYTES && port in 1739..1764)
        return JSONObject().put("id", System.currentTimeMillis()).put("type", type)
            .put("body", body).put("payloadSize", size)
            .put("payloadTransferInfo", JSONObject().put("port", port)).toString() + "\n"
    }

    fun identity(id: String, name: String, target: String? = null,
                 playStore: Boolean = BuildConfig.PLAY_STORE_DISTRIBUTION,
                 dialect: FluxDialect = FluxDialect.LEGACY_KDE): String {
        val body = JSONObject().put("deviceId", id).put("deviceName", name.take(32))
            .put("deviceType", "phone").put("protocolVersion", 8)
            .put("incomingCapabilities", JSONArray().apply {
                if (!playStore) { put(MPRIS); put(SHARE); put(REMOTE_INPUT_STATE); put(OMARCHY_THEME); put(OMARCHY_THEME_SELECTED); put(FluxWallpaper.TYPE) }
            })
            .put("outgoingCapabilities", JSONArray().put(SHARE).put(PING).put(CLIPBOARD).put(BATTERY).apply {
                if (!playStore) { put(NOTIFICATION); put(SCREEN); put(MPRIS_REQUEST); put(TUNNEL); put(REMOTE_INPUT_REQUEST); put(INPUT_APPROVAL); put(OMARCHY_THEME_SELECT); put(FluxWallpaper.TYPE) }
            })
        if (target != null) body.put("targetDeviceId", target).put("targetProtocolVersion", 8)
        for (key in listOf("incomingCapabilities", "outgoingCapabilities")) {
            val original = body.getJSONArray(key)
            body.put(key, JSONArray((0 until original.length()).map { type(original.getString(it), dialect) }))
        }
        return packet(IDENTITY, body, dialect)
    }

    fun parseIdentity(line: String, expectedId: String, expectedDialect: FluxDialect = FluxDialect.LEGACY_KDE): String {
        val message = JSONObject(line)
        require(message.getString("type") == expectedDialect.identityType)
        val body = message.getJSONObject("body")
        require(validId(expectedId) && body.getString("deviceId") == expectedId)
        require(body.opt("protocolVersion") is Int && body.getInt("protocolVersion") == 8)
        for (key in listOf("incomingCapabilities", "outgoingCapabilities")) {
            val capabilities = body.getJSONArray(key)
            require(capabilities.length() <= 128)
            for (i in 0 until capabilities.length()) {
                val capability = capabilities.get(i) as? String ?: throw IllegalArgumentException("Invalid Flux capability")
                require(capability.length <= 128 && if (expectedDialect == FluxDialect.FLUX)
                    !capability.startsWith("kdeconnect.") else capability !in canonical) { "Mixed Flux namespaces" }
            }
        }
        val capabilities = body.getJSONArray("incomingCapabilities")
        require((0 until capabilities.length()).any { capabilities.optString(it) == type(SHARE, expectedDialect) }) { "Peer cannot receive text" }
        return body.optString("deviceName", expectedId).take(32)
    }

    fun supportsMedia(line: String): Boolean {
        val incoming = JSONObject(line).getJSONObject("body").getJSONArray("incomingCapabilities")
        return (0 until incoming.length()).any { incoming.optString(it) == type(MPRIS_REQUEST, dialect(line)) }
    }
    fun supportsRemoteInput(line: String): Boolean {
        val incoming = JSONObject(line).getJSONObject("body").getJSONArray("incomingCapabilities")
        return (0 until incoming.length()).any { incoming.optString(it) == type(REMOTE_INPUT_REQUEST, dialect(line)) }
    }

    fun supportsInputApproval(line: String): Boolean {
        val incoming = JSONObject(line).getJSONObject("body").getJSONArray("incomingCapabilities")
        return (0 until incoming.length()).any { incoming.optString(it) == INPUT_APPROVAL }
    }
    /** Direction matters: desktop must accept select and emit selected. */
    fun supportsWallpaper(line:String):Boolean = runCatching {
        val body=JSONObject(line).getJSONObject("body")
        listOf("incomingCapabilities","outgoingCapabilities").all { key ->
            val a=body.getJSONArray(key); (0 until a.length()).any {a.optString(it)==FluxWallpaper.TYPE}
        }
    }.getOrDefault(false)

    fun supportsThemeSelection(line: String): Boolean {
        val body = JSONObject(line).getJSONObject("body")
        val incoming = body.optJSONArray("incomingCapabilities") ?: return false
        val outgoing = body.optJSONArray("outgoingCapabilities") ?: return false
        return (0 until incoming.length()).any { incoming.optString(it) == OMARCHY_THEME_SELECT } &&
            (0 until outgoing.length()).any { outgoing.optString(it) == OMARCHY_THEME_SELECTED }
    }

    private val REQUEST_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    fun validRequestId(id: String): Boolean = REQUEST_ID.matches(id) &&
        runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    fun inputApprovalPacket(request: Boolean, requestId: String): String {
        require(validRequestId(requestId))
        return packet(INPUT_APPROVAL, JSONObject().put("request", request).put("requestId", requestId))
    }

    data class InputState(val enabled: Boolean, val requestId: String)
    /** Legacy uncorrelated initial state is informational, never an approval decision. */
    fun remoteInputState(type: String, body: JSONObject): InputState? {
        val id = body.opt("requestId") as? String ?: return null
        return if (type == REMOTE_INPUT_STATE && body.length() == 2 &&
            body.opt("enabled") is Boolean && validRequestId(id))
            InputState(body.getBoolean("enabled"), id) else null
    }

    fun pairReply(line: String, dialect: FluxDialect = FluxDialect.LEGACY_KDE): Boolean? {
        val message = JSONObject(line)
        if (message.optString("type") != type(PAIR, dialect)) return null
        return message.optJSONObject("body")?.opt("pair") as? Boolean
    }

    fun pairTimestamp(timestamp: Long, nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        nowSeconds in 1800..(Long.MAX_VALUE - 1800) && timestamp > 0 &&
            timestamp in (nowSeconds - 1800)..(nowSeconds + 1800)

    fun verifyCertificate(id: String, certificate: X509Certificate, pinned: ByteArray?) {
        require(validId(id))
        val names = X500Name.getInstance(certificate.subjectX500Principal.encoded).getRDNs(BCStyle.CN)
            .flatMap { it.typesAndValues.filter { value -> value.type == BCStyle.CN } }
            .map { it.value.toString() }
        require(names == listOf(id)) { "Certificate identity mismatch" }
        pinned?.let { require(it.contentEquals(certificate.encoded)) { "Desktop certificate changed; link refused" } }
    }

    /** Go bytes.Compare(SPKI) descending, then decimal UNIX seconds; dialect-specific digest prefix. */
    fun verificationKey(a: ByteArray, b: ByteArray, timestamp: Long,
                        dialect: FluxDialect = FluxDialect.LEGACY_KDE): String {
        var order = a.size - b.size
        for (i in 0 until minOf(a.size, b.size)) {
            order = (a[i].toInt() and 255) - (b[i].toInt() and 255)
            if (order != 0) break
        }
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(if (order >= 0) a else b)
        hash.update(if (order >= 0) b else a)
        if (timestamp > 0) hash.update(timestamp.toString().toByteArray(Charsets.US_ASCII))
        return hash.digest().take(dialect.verificationBytes).joinToString("") { "%02X".format(it.toInt() and 255) }
    }

    /** Byte-at-a-time so plaintext identity parsing never consumes the TLS ClientHello. */
    fun readLine(input: InputStream, limit: Int = 8192, beforeRead: () -> Unit = {}): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < limit) {
            beforeRead()
            val b = input.read()
            if (b < 0) throw IllegalStateException("Peer closed the link")
            if (b == 10) return bytes.toString("UTF-8")
            bytes.write(b)
        }
        throw IllegalStateException("Peer packet exceeds $limit bytes")
    }
}
