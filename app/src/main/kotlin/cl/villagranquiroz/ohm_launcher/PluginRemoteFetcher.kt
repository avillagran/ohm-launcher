package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray
import org.json.JSONObject

class RemoteManifest(
    val body: String,
    val manifestDir: String,
) {
    val data: JSONObject get() = JSONObject(body)
}

class PluginRemoteFetcher(private val http: HttpFetcher) {
    fun findManifest(owner: String, repo: String, branch: String, expectedId: String = ""): RemoteManifest? {
        if (!isSafeSegment(owner) || !isSafeSegment(repo) || !isSafeSegment(branch)) return null
        if (expectedId.isNotEmpty() && !isSafePluginId(expectedId)) return null

        val rawBase = "$RAW_BASE/$owner/$repo/$branch"
        val root = fetchManifest("$rawBase/manifest.json", expectedId)
        if (root != null) return RemoteManifest(root, "")
        if (expectedId.isEmpty()) return null

        listOf(expectedId, "plugins/$expectedId").forEach { directory ->
            fetchManifest("$rawBase/$directory/manifest.json", expectedId)?.let {
                return RemoteManifest(it, directory)
            }
        }

        val rootListing = http.fetch(
            "$API_BASE/repos/$owner/$repo/contents/?ref=$branch",
            MAX_LISTING_BYTES,
        )?.utf8JsonArray()
        if (rootListing != null) {
            for (directory in directories(rootListing).filterNot { it == "plugins" }) {
                fetchManifest("$rawBase/$directory/manifest.json", expectedId)?.let {
                    return RemoteManifest(it, directory)
                }
            }
            val pluginListing = http.fetch(
                "$API_BASE/repos/$owner/$repo/contents/plugins?ref=$branch",
                MAX_LISTING_BYTES,
            )?.utf8JsonArray()
            if (pluginListing != null) {
                for (directory in directories(pluginListing)) {
                    fetchManifest("$rawBase/plugins/$directory/manifest.json", expectedId)?.let {
                        return RemoteManifest(it, "plugins/$directory")
                    }
                }
            }
        }
        return null
    }

    private fun fetchManifest(url: String, expectedId: String): String? {
        val body = http.fetch(url, MAX_MANIFEST_BYTES)?.toString(Charsets.UTF_8) ?: return null
        if (expectedId.isEmpty()) return body.takeIf(::isJsonObject)
        return body.takeIf { manifestId(it) == expectedId }
    }

    private fun directories(array: JSONArray): List<String> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.opt("name") as? String ?: continue
            if (item.opt("type") == "dir" && isSafeSegment(name)) add(name)
        }
    }

    private fun manifestId(body: String): String? = runCatching {
        JSONObject(body).opt("id") as? String
    }.getOrNull()

    private fun isJsonObject(body: String): Boolean = runCatching { JSONObject(body); true }.getOrDefault(false)

    companion object {
        const val RAW_BASE = "https://raw.githubusercontent.com"
        const val API_BASE = "https://api.github.com"
        const val MAX_MANIFEST_BYTES = 256L * 1024
        const val MAX_LISTING_BYTES = 2L * 1024 * 1024

        internal fun isSafeSegment(value: String): Boolean =
            value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) && value != "." && value != ".."

        internal fun isSafePluginId(value: String): Boolean = isSafeSegment(value)
    }
}

private fun ByteArray.utf8JsonArray(): JSONArray? = runCatching {
    JSONArray(toString(Charsets.UTF_8))
}.getOrNull()
