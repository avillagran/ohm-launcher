package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class OmarchyThemeChoice(
    val id: String,
    val label: String,
    val previewPath: String,
    val palette: OmarchyThemePalette? = null,
)

data class OmarchyThemeCatalog(
    val current: String,
    val themes: List<OmarchyThemeChoice>,
)

data class OmarchyBackgroundCurrent(val id: String, val type: String, val path: String)

data class OmarchyBackgroundChoice(
    val id: String,
    val label: String,
    val type: String,
    val preview: String,
    val hasPreview: Boolean,
    val previewPath: String? = null,
)

data class OmarchyBackgroundCatalog(
    val current: OmarchyBackgroundCurrent,
    val backgrounds: List<OmarchyBackgroundChoice>,
)

/** HTTP client for the small server shipped by the Omarchy Link desktop plugin. */
class OmarchyPeerClient(
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 30_000,
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

    fun fetchThemes(peer: OmarchyPeer): OmarchyThemeCatalog? =
        request<OmarchyThemeCatalog?>(peer, "GET", "/omarchy/themes") { code, body ->
            if (code !in 200..299 || body.length > MAX_CATALOG_BYTES) return@request null
            runCatching {
                val json = JSONObject(body)
                val array = json.getJSONArray("themes")
                check(array.length() in 1..MAX_THEME_COUNT)
                val themes = buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        val id = item.getString("id")
                        val preview = item.optString("preview")
                        check(THEME_ID.matches(id))
                        check(preview.startsWith("/omarchy/themes/") && preview.endsWith("/preview"))
                        add(
                            OmarchyThemeChoice(
                                id,
                                item.optString("label", id),
                                preview,
                                item.optJSONObject("palette")?.let(OmarchyThemePalette::parse),
                            ),
                        )
                    }
                }.distinctBy { it.id }
                check(themes.size == array.length())
                OmarchyThemeCatalog(json.optString("current"), themes)
            }.getOrNull()
        }

    fun fetchThemePreview(peer: OmarchyPeer, choice: OmarchyThemeChoice): ByteArray? = runCatching {
        check(THEME_ID.matches(choice.id))
        check(choice.previewPath.startsWith("/omarchy/themes/") && choice.previewPath.endsWith("/preview"))
        val authority = if (peer.host.contains(':')) "[${peer.host}]" else peer.host
        val connection = URI("http://$authority:${peer.port}${choice.previewPath}")
            .toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Connection", "close")
            if (peer.token.isNotEmpty()) connection.setRequestProperty("X-Omarchy-Link-Token", peer.token)
            check(connection.responseCode in 200..299)
            check(connection.contentLengthLong in -1..MAX_PREVIEW_BYTES)
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                check(input.copyTo(output, DEFAULT_BUFFER_SIZE) <= MAX_PREVIEW_BYTES)
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    fun selectTheme(peer: OmarchyPeer, id: String): OmarchyThemePalette? {
        if (!THEME_ID.matches(id)) return null
        val body = JSONObject().put("id", id).toString().toByteArray(StandardCharsets.UTF_8)
        return request<OmarchyThemePalette?>(peer, "POST", "/omarchy/themes/select", body) { code, response ->
            if (code !in 200..299) null
            else runCatching { OmarchyThemePalette.parse(JSONObject(response)) }
                .getOrNull()
                ?.takeIf { it.colors.isNotEmpty() }
        }
    }

    fun fetchBackgrounds(peer: OmarchyPeer): OmarchyBackgroundCatalog? =
        request<OmarchyBackgroundCatalog?>(peer, "GET", "/omarchy/backgrounds") { code, body ->
            if (code !in 200..299 || body.length > MAX_CATALOG_BYTES) return@request null
            runCatching {
                val json = JSONObject(body)
                val currentJson = json.getJSONObject("current")
                val current = OmarchyBackgroundCurrent(
                    id = currentJson.optString("id"),
                    type = currentJson.getString("type"),
                    path = currentJson.getString("path"),
                )
                val array = json.getJSONArray("backgrounds")
                check(array.length() <= MAX_BACKGROUND_COUNT)
                val backgrounds = buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        val id = item.getString("id")
                        check(validBackgroundId(id))
                        add(
                            OmarchyBackgroundChoice(
                                id = id,
                                label = item.optString("label", id),
                                type = item.getString("type"),
                                preview = item.optString("preview"),
                                hasPreview = item.optBoolean("hasPreview", false),
                            ),
                        )
                    }
                }.distinctBy { it.id }
                check(backgrounds.size == array.length())
                OmarchyBackgroundCatalog(current, backgrounds)
            }.getOrNull()
        }

    fun fetchBackgroundPreview(peer: OmarchyPeer, choice: OmarchyBackgroundChoice): ByteArray? = runCatching {
        check(choice.hasPreview && validBackgroundId(choice.id))
        val encodedId = URLEncoder.encode(choice.id, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val authority = if (peer.host.contains(':')) "[${peer.host}]" else peer.host
        val connection = URI("http://$authority:${peer.port}/omarchy/backgrounds/$encodedId/preview")
            .toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Connection", "close")
            if (peer.token.isNotEmpty()) connection.setRequestProperty("X-Omarchy-Link-Token", peer.token)
            check(connection.responseCode in 200..299)
            check(connection.contentLengthLong in -1..MAX_PREVIEW_BYTES)
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                check(input.copyTo(output, DEFAULT_BUFFER_SIZE) <= MAX_PREVIEW_BYTES)
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    fun selectBackground(peer: OmarchyPeer, id: String): Boolean {
        if (!validBackgroundId(id)) return false
        val body = JSONObject().put("id", id).toString().toByteArray(StandardCharsets.UTF_8)
        return request<Boolean?>(peer, "POST", "/omarchy/backgrounds/select", body) { code, response ->
            code in 200..299 && runCatching { JSONObject(response).optBoolean("ok", false) }.getOrDefault(false)
        } ?: false
    }

    fun fetchThemeBackground(
        peer: OmarchyPeer,
        background: OmarchyThemeBackground,
        directory: File,
    ): File? = runCatching {
        check(directory.mkdirs() || directory.isDirectory)
        val extension = background.name.substringAfterLast('.', "bin").take(8).filter(Char::isLetterOrDigit)
        val target = directory.resolve("${background.sha256.take(16)}.$extension")
        if (target.isFile && sha256(target) == background.sha256) return@runCatching target
        val temporary = File.createTempFile(".omarchy-background-", ".tmp", directory)
        try {
            val authority = if (peer.host.contains(':')) "[${peer.host}]" else peer.host
            val connection = URI("http://$authority:${peer.port}/omarchy/theme/background")
                .toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            if (peer.token.isNotEmpty()) connection.setRequestProperty("X-Omarchy-Link-Token", peer.token)
            check(connection.responseCode in 200..299)
            check(connection.contentLengthLong in -1..MAX_BACKGROUND_BYTES)
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    check(input.copyTo(output) <= MAX_BACKGROUND_BYTES)
                }
            }
            connection.disconnect()
            check(sha256(temporary) == background.sha256)
            if (target.exists()) target.delete()
            check(temporary.renameTo(target))
            target
        } finally {
            temporary.delete()
        }
    }.getOrNull()

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
        if (peer.token.isNotEmpty()) connection.setRequestProperty("X-Omarchy-Link-Token", peer.token)
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_BACKGROUND_BYTES = 512L * 1024L * 1024L
        const val MAX_PREVIEW_BYTES = 16L * 1024L * 1024L
        const val MAX_CATALOG_BYTES = 256 * 1024
        const val MAX_THEME_COUNT = 256
        const val MAX_BACKGROUND_COUNT = 1024
        val THEME_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

        fun validBackgroundId(id: String): Boolean = id.isNotBlank() && id.length <= 2048 && '\u0000' !in id
    }
}
