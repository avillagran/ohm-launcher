package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/** HTTP client for the small server shipped by the Omarchy Link desktop plugin. */
class OmarchyPeerClient(
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 3_000,
) {
    fun notify(
        peer: OmarchyPeer,
        launcherIp: String,
        launcherPort: Int,
        launcherName: String,
    ): Boolean {
        if (launcherIp.isBlank() || launcherPort !in 1..65535 || launcherName.isBlank()) return false
        val body = JSONObject()
            .put("ip", launcherIp)
            .put("port", launcherPort)
            .put("name", launcherName)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        return request(peer, "POST", "/omarchy/link", body) { it in 200..299 } ?: false
    }

    fun probe(peer: OmarchyPeer): Boolean = probeStatus(peer) == true

    fun fetchTheme(peer: OmarchyPeer): OmarchyThemePalette? =
        request<OmarchyThemePalette?>(peer, "GET", "/omarchy/theme") { code, body ->
            if (code !in 200..299) null
            else runCatching { OmarchyThemePalette.parse(JSONObject(body)) }
                .getOrNull()
                ?.takeIf { it.colors.isNotEmpty() }
        }

    /** Null means unreachable/malformed; only an explicit false should disconnect. */
    fun probeStatus(peer: OmarchyPeer): Boolean? = request<Boolean?>(peer, "GET", "/omarchy/link") { code, body ->
        if (code !in 200..299) null
        else runCatching { JSONObject(body).opt("connected") as? Boolean }.getOrNull()
    }

    fun postScreenFrame(peer: OmarchyPeer, jpeg: ByteArray, width: Int, height: Int): Boolean {
        if (jpeg.isEmpty() || width <= 0 || height <= 0) return false
        return request(
            peer,
            "POST",
            "/omarchy/screen/frame?w=$width&h=$height",
            jpeg,
            "image/jpeg",
        ) { it in 200..299 } ?: false
    }

    private fun <T> request(
        peer: OmarchyPeer,
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String = "application/json; charset=utf-8",
        consume: (Int, String) -> T,
    ): T? = runCatching {
        val authority = if (peer.host.contains(':')) "[${peer.host}]" else peer.host
        val connection = URI("http://$authority:${peer.port}$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        connection.setRequestProperty("Connection", "close")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        val code = connection.responseCode
        val stream = if (code >= 400) connection.errorStream else connection.inputStream
        val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        consume(code, response)
    }.getOrNull()

    private fun <T> request(
        peer: OmarchyPeer,
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String = "application/json; charset=utf-8",
        consume: (Int) -> T,
    ): T? = request(peer, method, path, body, contentType) { code, _ -> consume(code) }
}
