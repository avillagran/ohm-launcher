package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject

fun interface HttpFetcher {
    fun fetch(url: String, maxBytes: Long): ByteArray?
}

data class MarketplaceEntry(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val category: String,
    val tags: List<String>,
    val repoUrl: String,
    val installCommand: String,
    val installNote: String,
    val isSuite: Boolean,
)

class MarketplaceFetchException(message: String) : Exception(message)

class MarketplaceCatalog(private val http: HttpFetcher) {
    fun fetch(): List<MarketplaceEntry> {
        val bytes = http.fetch(PRIMARY_URL, MAX_REGISTRY_BYTES)
            ?: http.fetch(FALLBACK_URL, MAX_REGISTRY_BYTES)
            ?: throw MarketplaceFetchException("Could not download the marketplace registry.")
        return parse(String(bytes, Charsets.UTF_8))
    }

    internal fun parse(body: String): List<MarketplaceEntry> {
        val sources = JSONObject(body).optJSONArray("sources") ?: return emptyList()
        return buildList {
            for (index in 0 until sources.length()) {
                val source = sources.optJSONObject(index) ?: continue
                val repo = source.optString("repo", "")
                source.optJSONObject("catalog")?.let { catalog ->
                    val id = catalog.string("id", "suite")
                    add(
                        MarketplaceEntry(
                            id = id,
                            name = catalog.string("name", id.ifEmpty { "Suite" }),
                            description = catalog.string("description", ""),
                            author = catalog.string("author", ""),
                            version = catalog.string("version", ""),
                            category = catalog.string("category", "Desktop"),
                            tags = catalog.stringList("tags"),
                            repoUrl = repo,
                            installCommand = catalog.string("installCommand", ""),
                            installNote = catalog.string("installNote", ""),
                            isSuite = true,
                        ),
                    )
                }
                val plugins = source.optJSONObject("plugins") ?: continue
                val keys = plugins.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val metadata = plugins.optJSONObject(id)
                    val installation = metadata?.optJSONObject("installation")
                    add(
                        MarketplaceEntry(
                            id = id,
                            name = humanize(id),
                            description = metadata.string("description", "Community plugin for Omarchy."),
                            author = metadata.string("author", ""),
                            version = metadata.string("version", ""),
                            category = metadata.string("category", "Widgets"),
                            tags = metadata?.stringList("tags").orEmpty(),
                            repoUrl = repo,
                            installCommand = "",
                            installNote = installation.string("note", ""),
                            isSuite = false,
                        ),
                    )
                }
            }
        }
    }

    private fun humanize(id: String): String = id.split('.', '_', '-')
        .filter(String::isNotEmpty)
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        .ifEmpty { id }

    companion object {
        const val PRIMARY_URL = "https://omarchyplugins.com/registry.json"
        const val FALLBACK_URL =
            "https://raw.githubusercontent.com/HANCORE-linux/omarchy-plugin-marketplace/main/registry.json"
        const val MAX_REGISTRY_BYTES = 2L * 1024 * 1024
    }
}

private fun JSONObject?.string(key: String, fallback: String): String =
    this?.opt(key).let { if (it is String) it else fallback }

private fun JSONObject.stringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            (array.opt(index) as? String)?.let(::add)
        }
    }
}
