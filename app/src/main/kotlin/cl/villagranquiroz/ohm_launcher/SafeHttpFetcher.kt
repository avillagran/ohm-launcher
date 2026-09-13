package cl.villagranquiroz.ohm_launcher

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Small blocking HTTP client for bounded marketplace metadata and plugin files. */
class SafeHttpFetcher(
    private val allowedHosts: Set<String> = DEFAULT_ALLOWED_HOSTS,
    private val connectionOpener: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) : HttpFetcher {
    override fun fetch(url: String, maxBytes: Long): ByteArray? {
        if (maxBytes <= 0) return null
        var current = validate(url) ?: return null
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = try {
                connectionOpener(current).apply {
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json, text/plain, application/octet-stream")
                }
            } catch (_: Exception) {
                return null
            }
            try {
                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return readBounded(connection, maxBytes)
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308,
                    -> {
                        if (redirectCount == MAX_REDIRECTS) return null
                        val location = connection.getHeaderField("Location") ?: return null
                        current = validate(URL(current, location).toString()) ?: return null
                    }
                    else -> return null
                }
            } catch (_: Exception) {
                return null
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun readBounded(connection: HttpURLConnection, maxBytes: Long): ByteArray? {
        val declared = connection.contentLengthLong
        if (declared > maxBytes) return null
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream(if (declared in 0..Int.MAX_VALUE) declared.toInt() else 8192)
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun validate(value: String): URL? = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase() ?: return@runCatching null
        if (uri.scheme != "https" || host !in allowedHosts.map(String::lowercase)) return@runCatching null
        if (uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in setOf(-1, 443)) return@runCatching null
        if (uri.rawPath.isNullOrEmpty() || uri.rawPath.contains('\\') || uri.rawPath.contains("\u0000")) {
            return@runCatching null
        }
        uri.toURL()
    }.getOrNull()

    companion object {
        val DEFAULT_ALLOWED_HOSTS = setOf(
            "omarchyplugins.com",
            "raw.githubusercontent.com",
            "api.github.com",
            "github.com",
            "codeload.github.com",
        )
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_REDIRECTS = 4
        private const val USER_AGENT = "OhmLauncher/1.0"
    }
}
