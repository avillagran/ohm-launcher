package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import java.io.File

/** Filesystem repository for Omarchy-compatible plugins. */
class PluginRepository(
    private val publicRoot: File = File(PluginContract.PUBLIC_ROOT),
) {
    val pluginsDirectory: File get() = publicRoot.resolve(PluginContract.ENABLED_DIRECTORY)
    val disabledPluginsDirectory: File get() = publicRoot.resolve(PluginContract.DISABLED_DIRECTORY)

    /** Discovers enabled plugin directories without dropping invalid entries. */
    fun discover(): List<Plugin> = discoverIn(pluginsDirectory)

    /** Discovers disabled plugin directories while retaining validation diagnostics. */
    fun discoverDisabled(): List<Plugin> = discoverIn(disabledPluginsDirectory)

    fun disabledPluginIds(): List<String> = discoverDisabled().map(Plugin::id)

    fun disable(id: String): Boolean = moveByManifestId(id, pluginsDirectory, disabledPluginsDirectory)

    fun disablePlugin(id: String): Boolean = disable(id)

    fun enable(id: String): Boolean = moveByManifestId(id, disabledPluginsDirectory, pluginsDirectory)

    fun enablePlugin(id: String): Boolean = enable(id)

    fun delete(id: String): Boolean {
        val matches = listOf(pluginsDirectory, disabledPluginsDirectory)
            .flatMap(::discoverIn)
            .filter { it.id == id }
        if (matches.isEmpty()) return false
        return matches.all { deleteTreeWithoutFollowingLinks(it.folder) }
    }

    fun deletePlugin(id: String): Boolean = delete(id)

    fun installPrepared(prepared: PreparedPlugin): Plugin {
        require(PluginRemoteFetcher.isSafePluginId(prepared.pluginId)) { "Unsafe plugin id" }
        require(prepared.files.isNotEmpty()) { "Prepared plugin has no files" }
        pluginsDirectory.mkdirs()
        val temporary = pluginsDirectory.resolve(".${prepared.pluginId}.tmp-${System.nanoTime()}")
        val destination = pluginsDirectory.resolve(prepared.pluginId)
        val backup = pluginsDirectory.resolve(".${prepared.pluginId}.backup-${System.nanoTime()}")
        try {
            temporary.mkdirs()
            prepared.files.forEach { (path, bytes) ->
                require(PluginInstallPreparer.isSafeRelativePath(path)) { "Unsafe plugin path: $path" }
                val target = temporary.resolve(path).canonicalFile
                require(target.path.startsWith(temporary.canonicalPath + File.separator)) { "Plugin path escaped staging" }
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            val staged = readPlugin(temporary)
            require(staged.isValid && staged.id == prepared.pluginId) {
                staged.validationErrors.joinToString("; ").ifEmpty { "Plugin id mismatch" }
            }
            if (destination.exists()) check(destination.renameTo(backup)) { "Could not back up installed plugin" }
            check(temporary.renameTo(destination)) { "Could not activate prepared plugin" }
            backup.deleteRecursively()
            discoverIn(disabledPluginsDirectory)
                .filter { it.id == prepared.pluginId }
                .forEach { deleteTreeWithoutFollowingLinks(it.folder) }
            return readPlugin(destination)
        } catch (error: Exception) {
            if (!destination.exists() && backup.exists()) backup.renameTo(destination)
            throw error
        } finally {
            temporary.deleteRecursively()
            if (destination.exists()) backup.deleteRecursively()
        }
    }

    private fun discoverIn(directory: File): List<Plugin> {
        val children = directory.listFiles()
            ?.filter { it.isDirectory || isSymbolicLink(it) }
            .orEmpty()
        return children.map(::readPlugin).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.id })
    }

    private fun moveByManifestId(id: String, sourceRoot: File, destinationRoot: File): Boolean {
        val source = discoverIn(sourceRoot).firstOrNull { it.id == id }?.folder ?: return false
        if (!destinationRoot.exists() && !destinationRoot.mkdirs()) return false
        val destination = destinationRoot.resolve(source.name)
        val obsolete = (discoverIn(destinationRoot).filter { it.id == id }.map(Plugin::folder) + destination)
            .distinctBy(File::getAbsolutePath)
            .filter { it.exists() || isSymbolicLink(it) }
        if (!obsolete.map(::deleteTreeWithoutFollowingLinks).all { it }) return false
        return source.renameTo(destination)
    }

    private fun deleteTreeWithoutFollowingLinks(file: File): Boolean {
        if (!file.exists() && !isSymbolicLink(file)) return true
        if (!isSymbolicLink(file) && file.isDirectory) {
            val children = file.listFiles() ?: return false
            if (!children.all(::deleteTreeWithoutFollowingLinks)) return false
        }
        return file.delete()
    }

    private fun readPlugin(folder: File): Plugin {
        if (isSymbolicLink(folder)) {
            return Plugin(folder.name, folder, null, listOf("Plugin directory is a symlink."))
        }
        val errors = mutableListOf<String>()
        val manifestFile = folder.resolve("manifest.json")
        if (!manifestFile.isFile) {
            return Plugin(folder.name, folder, null, listOf("Missing manifest.json in plugin directory."))
        }

        val manifest = try {
            parseManifest(JSONObject(manifestFile.readText()))
        } catch (error: Exception) {
            errors += "Invalid manifest.json: ${error.message ?: error.javaClass.simpleName}"
            null
        }
        if (manifest != null) errors += validate(folder, manifest)
        return Plugin(manifest?.id?.takeIf(String::isNotEmpty) ?: folder.name, folder, manifest, errors)
    }

    private fun parseManifest(json: JSONObject): PluginManifest {
        val kinds = json.optJSONArray("kinds")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.opt(index)
                    if (value is String) add(value)
                }
            }
        }.orEmpty()
        val entryPoints = json.optJSONObject("entryPoints")?.let { entries ->
            buildMap {
                val keys = entries.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, entries.opt(key) as? String ?: "")
                }
            }
        }.orEmpty()
        return PluginManifest(
            schemaVersion = (json.opt("schemaVersion") as? Number)?.toInt() ?: 0,
            id = json.opt("id") as? String ?: "",
            name = json.opt("name") as? String ?: "",
            version = json.opt("version") as? String ?: "",
            author = json.opt("author") as? String ?: "",
            license = json.opt("license") as? String ?: "",
            description = json.opt("description") as? String ?: "",
            kinds = kinds,
            entryPoints = entryPoints,
            barWidget = json.optJSONObject("barWidget")?.toValueMap(),
        )
    }

    private fun JSONObject.toValueMap(): Map<String, Any?> = buildMap {
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, opt(key).takeUnless { it == JSONObject.NULL })
        }
    }

    private fun validate(folder: File, manifest: PluginManifest): List<String> {
        val errors = mutableListOf<String>()
        if (manifest.schemaVersion != PluginContract.SCHEMA_VERSION) errors += "schemaVersion must be 1."
        if (manifest.id.isEmpty()) errors += "Field \"id\" is required."
        if (manifest.id.startsWith("omarchy.", ignoreCase = true)) {
            errors += "Third-party IDs cannot use the reserved \"omarchy.\" prefix."
        }
        if (manifest.name.isEmpty()) errors += "Field \"name\" is required."
        if (manifest.version.isEmpty()) errors += "Field \"version\" is required."
        if (manifest.author.isEmpty()) errors += "Field \"author\" is required."
        if (manifest.kinds.isEmpty()) errors += "Field \"kinds\" cannot be empty."

        findSymlinks(folder).forEach { relativePath ->
            errors += "Plugin contains a symlink: $relativePath"
        }

        manifest.entryPoints.toSortedMap().forEach { (key, entry) ->
            when {
                !isSafeRelativePath(entry) -> errors += "entryPoints.$key contains an unsafe path: \"$entry\"."
                !folder.resolve(entry).isFile -> errors += "Entry point not found for entryPoints.$key: \"$entry\"."
            }
        }

        manifest.kinds.forEach { kind ->
            val key = PluginContract.KIND_TO_ENTRY_POINT[kind]
            if (key == null) {
                errors += "Unknown kind: \"$kind\"."
            } else if (manifest.entryPoints[key].isNullOrEmpty()) {
                errors += "Kind \"$kind\" requires entryPoints.$key."
            }
        }
        folder.walkTopDown()
            .filter { it.isFile && it.extension.equals("qml", ignoreCase = true) }
            .forEach { qml ->
                val source = runCatching { qml.readText() }.getOrDefault("")
                sequenceOf(QML_RESOLVED_URL.findAll(source), QML_LOCAL_IMPORT.findAll(source))
                    .flatten()
                    .map { it.groupValues[1] }
                    .distinct()
                    .forEach { relative ->
                        val referenced = (qml.parentFile ?: folder).resolve(relative)
                        val safe = isSafeRelativePath(relative) && runCatching {
                            referenced.canonicalPath.startsWith(folder.canonicalPath + File.separator)
                        }.getOrDefault(false)
                        when {
                            !safe -> errors += "QML reference contains an unsafe path: \"$relative\"."
                            !referenced.isFile -> errors += "QML reference not found: \"$relative\" from ${qml.name}."
                        }
                    }
            }
        return errors
    }

    private fun isSafeRelativePath(value: String): Boolean {
        if (value.isEmpty() || value.startsWith('/') || value.startsWith('\\') || value.contains('\u0000')) return false
        if (Regex("^[A-Za-z]:").containsMatchIn(value)) return false
        return value.replace('\\', '/').split('/').none { it == ".." }
    }

    private fun findSymlinks(root: File): List<String> {
        val links = mutableListOf<String>()
        fun visit(directory: File) {
            directory.listFiles()?.sortedBy(File::getName)?.forEach { child ->
                if (isSymbolicLink(child)) {
                    links += child.relativeTo(root).path
                } else if (child.isDirectory) {
                    visit(child)
                }
            }
        }
        visit(root)
        return links
    }

    private fun isSymbolicLink(file: File): Boolean = runCatching {
        val canonicalParent = file.parentFile?.canonicalFile ?: return@runCatching false
        File(canonicalParent, file.name).canonicalFile != File(canonicalParent, file.name).absoluteFile
    }.getOrDefault(true)

    private companion object {
        val QML_RESOLVED_URL = Regex("Qt\\.resolvedUrl\\(\\s*[\"']([^\"']+)[\"']\\s*\\)")
        val QML_LOCAL_IMPORT = Regex("(?m)^\\s*import\\s+[\"']([^\"']+)[\"']")
    }
}
