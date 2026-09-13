package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import java.net.URI

sealed class InstallPreparation {
    data class Success(val plugin: PreparedPlugin) : InstallPreparation()
    data class Failure(val message: String) : InstallPreparation()
}

data class PreparedPlugin(
    val pluginId: String,
    val branch: String,
    val files: Map<String, ByteArray>,
    val jsonFiles: Int,
    val qmlFiles: Int,
    val missingFiles: Int,
)

/** Downloads and validates a plugin into memory, leaving the atomic filesystem install to its repository. */
class PluginInstallPreparer(
    private val http: HttpFetcher,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {
    fun prepare(repoUrl: String, expectedId: String = ""): InstallPreparation {
        val repository = parseRepository(repoUrl)
            ?: return InstallPreparation.Failure("Invalid or untrusted GitHub repository URL.")
        if (expectedId.isNotEmpty() && !PluginRemoteFetcher.isSafePluginId(expectedId)) {
            return InstallPreparation.Failure("The expected plugin id is unsafe.")
        }

        for (branch in listOf("main", "master")) {
            val remote = PluginRemoteFetcher(http).findManifest(
                repository.first,
                repository.second,
                branch,
                expectedId,
            ) ?: continue
            val manifestBytes = remote.body.toByteArray(Charsets.UTF_8)
            if (manifestBytes.size > PluginRemoteFetcher.MAX_MANIFEST_BYTES || manifestBytes.size > maxTotalBytes) {
                return InstallPreparation.Failure("Plugin download exceeds the size limit.")
            }
            val manifest = runCatching { JSONObject(remote.body) }.getOrElse {
                return InstallPreparation.Failure("manifest.json is not valid JSON.")
            }
            val id = manifest.opt("id") as? String
                ?: return InstallPreparation.Failure("manifest.json has no id field.")
            if (!PluginRemoteFetcher.isSafePluginId(id)) {
                return InstallPreparation.Failure("The plugin id is unsafe.")
            }
            if (id.startsWith("omarchy.", ignoreCase = true)) {
                return InstallPreparation.Failure("The plugin id uses the reserved omarchy. prefix.")
            }
            if (expectedId.isNotEmpty() && id != expectedId) {
                return InstallPreparation.Failure("The manifest id does not match the marketplace entry.")
            }
            val entryPoints = manifest.optJSONObject("entryPoints")
                ?: return InstallPreparation.Failure("manifest.json has no entryPoints object.")

            val candidates = linkedSetOf<String>()
            val keys = entryPoints.keys()
            while (keys.hasNext()) {
                val path = entryPoints.opt(keys.next())
                if (path is String && path.isNotEmpty()) candidates += path
            }
            candidates += "Panel.json"
            val unsafe = candidates.firstOrNull { !isSafeRelativePath(it) }
            if (unsafe != null) return InstallPreparation.Failure("Entry point contains an unsafe path: $unsafe")

            val files = linkedMapOf("manifest.json" to manifestBytes)
            var total = manifestBytes.size.toLong()
            var jsonFiles = 1
            var qmlFiles = 0
            var missingFiles = 0
            val rawRoot = listOfNotNull(
                repository.first,
                repository.second,
                branch,
                remote.manifestDir.takeIf(String::isNotEmpty),
            ).joinToString("/")
            val pending = ArrayDeque(candidates)
            val queued = candidates.toMutableSet()
            while (pending.isNotEmpty()) {
                val path = pending.removeFirst()
                val url = URI("https", "raw.githubusercontent.com", "/$rawRoot/$path", null).toASCIIString()
                val content = http.fetch(url, maxFileBytes)
                if (content == null) {
                    missingFiles++
                    continue
                }
                if (content.size > maxFileBytes || total + content.size > maxTotalBytes) {
                    return InstallPreparation.Failure("Plugin download exceeds the size limit.")
                }
                total += content.size
                files[path] = content
                if (path.endsWith(".json", ignoreCase = true)) {
                    jsonFiles++
                } else {
                    qmlFiles++
                    val source = content.toString(Charsets.UTF_8)
                    val dependencies = sequenceOf(
                        QML_RESOLVED_URL.findAll(source),
                        QML_LOCAL_IMPORT.findAll(source),
                    ).flatten().map { it.groupValues[1] }.distinct()
                    dependencies.forEach { relative ->
                        if (!isSafeRelativePath(relative)) {
                            return InstallPreparation.Failure("QML dependency contains an unsafe path: $relative")
                        }
                        val parent = path.substringBeforeLast('/', "")
                        val dependency = if (parent.isEmpty()) relative else "$parent/$relative"
                        if (!isSafeRelativePath(dependency)) {
                            return InstallPreparation.Failure("QML dependency contains an unsafe path: $dependency")
                        }
                        if (queued.add(dependency)) pending.addLast(dependency)
                    }
                }
            }
            return InstallPreparation.Success(
                PreparedPlugin(id, branch, files, jsonFiles, qmlFiles, missingFiles),
            )
        }
        return InstallPreparation.Failure("manifest.json was not found on the main or master branch.")
    }

    private fun parseRepository(value: String): Pair<String, String>? = runCatching {
        val uri = URI(value)
        if (uri.scheme != "https" || !uri.host.equals("github.com", ignoreCase = true)) return@runCatching null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.port !in setOf(-1, 443)) {
            return@runCatching null
        }
        val parts = uri.path.trimEnd('/').split('/').filter(String::isNotEmpty)
        if (parts.size != 2) return@runCatching null
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        if (!PluginRemoteFetcher.isSafeSegment(owner) || !PluginRemoteFetcher.isSafeSegment(repo)) return@runCatching null
        owner to repo
    }.getOrNull()

    companion object {
        const val DEFAULT_MAX_FILE_BYTES = 2L * 1024 * 1024
        const val DEFAULT_MAX_TOTAL_BYTES = 16L * 1024 * 1024
        private val QML_RESOLVED_URL = Regex("Qt\\.resolvedUrl\\(\\s*[\"']([^\"']+)[\"']\\s*\\)")
        private val QML_LOCAL_IMPORT = Regex("(?m)^\\s*import\\s+[\"']([^\"']+)[\"']")

        internal fun isSafeRelativePath(value: String): Boolean {
            if (value.isEmpty() || value.startsWith('/') || value.startsWith('\\') || value.contains('\u0000')) return false
            if (value.contains('\\') || Regex("^[A-Za-z]:").containsMatchIn(value)) return false
            return value.split('/').all { part ->
                part.isNotEmpty() && part != "." && part != ".." && part.length <= 255
            }
        }
    }
}
