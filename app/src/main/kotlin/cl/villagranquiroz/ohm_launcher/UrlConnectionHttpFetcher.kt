package cl.villagranquiroz.ohm_launcher

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

/** Bounded HTTPS client restricted to the marketplace and GitHub content hosts. */
class UrlConnectionHttpFetcher(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 12_000,
) : HttpFetcher {
    override fun fetch(url: String, maxBytes: Long): ByteArray? {
        if (maxBytes !in 1..MAX_REQUEST_BYTES || !isAllowed(url)) return null
        return runCatching {
            var current = URI(url)
            var redirects = 0
            while (true) {
                val connection = current.toURL().openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = connectTimeoutMs
                    connection.readTimeout = readTimeoutMs
                    connection.useCaches = false
                    connection.setRequestProperty("Accept", "application/json, text/plain, application/octet-stream")
                    connection.setRequestProperty("User-Agent", "OhmLauncher-native/1")
                    val code = connection.responseCode
                    if (code in 300..399) {
                        if (redirects++ >= MAX_REDIRECTS) return null
                        val location = connection.getHeaderField("Location") ?: return null
                        current = current.resolve(location)
                        if (!isAllowed(current.toASCIIString())) return null
                        continue
                    }
                    if (code !in 200..299) return null
                    val declared = connection.contentLengthLong
                    if (declared > maxBytes) return null
                    return connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > maxBytes) return null
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                } finally {
                    connection.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }.getOrNull()
    }

    companion object {
        private const val MAX_REDIRECTS = 3
        private const val MAX_REQUEST_BYTES = 32L * 1024 * 1024
        private val allowedHosts = setOf(
            "omarchyplugins.com",
            "raw.githubusercontent.com",
            "api.github.com",
        )

        fun isAllowed(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host?.lowercase() in allowedHosts &&
                uri.rawUserInfo == null &&
                uri.port in setOf(-1, 443) &&
                uri.rawFragment == null
        }.getOrDefault(false)
    }
}
