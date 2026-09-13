package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Supported REST operations under the `/omarchy/` namespace. */
enum class OmarchyRestRoute(
    val method: String,
    val path: String,
    val capability: String?,
) {
    DISCOVER("GET", "/omarchy/discover", null),
    CLIPBOARD_GET("GET", "/omarchy/clipboard", "clipboard"),
    CLIPBOARD_PUT("PUT", "/omarchy/clipboard", "clipboard"),
    THEME_GET("GET", "/omarchy/theme", "theme"),
    THEME_PUT("PUT", "/omarchy/theme", "theme"),
    FILE_UPLOAD("POST", "/omarchy/file", "file"),
    FILE_DOWNLOAD("GET", "/omarchy/file", "file"),
    FILES_LIST("GET", "/omarchy/files", "files"),
    INPUT("POST", "/omarchy/input", "input"),
    SCREEN_START("POST", "/omarchy/screen/start", "screen"),
    SCREEN_STOP("POST", "/omarchy/screen/stop", "screen"),
    PHOTOS_BACKUP("POST", "/omarchy/photos/backup", "photos");

    companion object {
        fun resolve(method: String, path: String): OmarchyRestRoute? =
            entries.firstOrNull { it.method == method.uppercase() && it.path == path }
    }
}

/** Transport-neutral REST request used by a future HTTP server adapter. */
data class OmarchyRestRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: JSONObject = JSONObject(),
) {
    val route: OmarchyRestRoute?
        get() = OmarchyRestRoute.resolve(method, path)

    companion object {
        fun fromUri(method: String, value: String, body: JSONObject = JSONObject()): OmarchyRestRequest? {
            val uri = try {
                URI(value)
            } catch (_: Exception) {
                return null
            }
            if (uri.path.isNullOrEmpty() || uri.fragment != null) return null
            val query = decodeQuery(uri.rawQuery) ?: return null
            return OmarchyRestRequest(method.uppercase(), uri.path, query, body)
        }

        private fun decodeQuery(raw: String?): Map<String, String>? {
            if (raw.isNullOrEmpty()) return emptyMap()
            val result = linkedMapOf<String, String>()
            for (part in raw.split('&')) {
                if (part.isEmpty()) return null
                val pieces = part.split('=', limit = 2)
                val key = decodeComponent(pieces[0]) ?: return null
                val value = decodeComponent(pieces.getOrElse(1) { "" }) ?: return null
                if (key.isEmpty() || result.put(key, value) != null) return null
            }
            return result
        }

        private fun decodeComponent(value: String): String? = try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

data class ClipboardPayload(val text: String) {
    fun toJson(): JSONObject = JSONObject().put("text", text)

    companion object {
        const val MAX_TEXT_LENGTH = 1_048_576

        fun parse(json: JSONObject): ClipboardPayload? {
            val text = json.opt("text") as? String ?: return null
            return text.takeIf { it.length <= MAX_TEXT_LENGTH }?.let(::ClipboardPayload)
        }
    }
}

data class DiscoverResponse(
    val name: String,
    val model: String,
    val version: Int,
    val lanIp: String,
    val port: Int,
    val capabilities: List<String>,
) {
    init {
        require(port in 1..65535)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("model", model)
        .put("version", version)
        .put("lan_ip", lanIp)
        .put("port", port)
        .put("capabilities", JSONArray(capabilities))

    fun toPeerHelloJson(): JSONObject = toJson().put("type", "peer_hello")
}

data class OmarchyFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
) {
    init {
        require(size >= 0)
        require(modified >= 0)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("path", path)
        .put("isDir", isDirectory)
        .put("size", size)
        .put("modified", modified)
}

data class FilesResponse(
    val path: String,
    val parent: String,
    val entries: List<OmarchyFileEntry>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("path", path)
        .put("parent", parent)
        .put("entries", JSONArray(entries.map(OmarchyFileEntry::toJson)))
}

data class OmarchyApiResponse(
    val statusCode: Int,
    val body: JSONObject,
) {
    companion object {
        fun ok(body: JSONObject = JSONObject().put("ok", true)) = OmarchyApiResponse(200, body)

        fun badRequest(error: String) =
            OmarchyApiResponse(400, JSONObject().put("error", error))

        fun unsupported(capability: String) = OmarchyApiResponse(
            501,
            JSONObject().put("error", "unsupported").put("capability", capability),
        )

        fun notFound(path: String) = OmarchyApiResponse(
            404,
            JSONObject().put("error", "not_found").put("path", path),
        )
    }
}
