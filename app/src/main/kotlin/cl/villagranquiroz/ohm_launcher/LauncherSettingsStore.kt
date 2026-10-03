package cl.villagranquiroz.ohm_launcher

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

enum class ThemeCommitStatus { NOT_COMMITTED, COMMITTED, INDETERMINATE }

data class ThemeCommitResult(val status: ThemeCommitStatus, val failure: Exception? = null)

/** Inject only the blocking stages; the store owns temp creation, serialization, and cleanup. */
interface ThemeTransactionIo {
    fun stage(temporary: File, bytes: ByteArray)
    fun replace(temporary: File, target: File)
    fun syncDirectory(directory: File)
}

private object FileThemeTransactionIo : ThemeTransactionIo {
    override fun stage(temporary: File, bytes: ByteArray) {
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    override fun replace(temporary: File, target: File) {
        check(temporary.renameTo(target)) { "Could not atomically replace ${target.absolutePath}" }
    }

    override fun syncDirectory(directory: File) {
        check(directory.isDirectory) { "Not a directory: $directory" }
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }
}

/** Thread-safe persistence for the Flutter-compatible settings document. */
class LauncherSettingsStore(
    private val file: File,
    private val legacyPeerFile: File? = null,
    private val themeIo: ThemeTransactionIo = FileThemeTransactionIo,
) {
    /** Preparation is data-only: no settings read, disk write, or store monitor held. */
    fun prepareOmarchyTheme(payload: JSONObject): PreparedOmarchyTheme {
        val palette = OmarchyThemePalette.parse(JSONObject(payload.toString()))
        require(palette.colors.isNotEmpty()) { "A theme palette must have colors" }
        return PreparedOmarchyTheme(palette.toJson().toString())
    }

    inner class PreparedOmarchyTheme internal constructor(private val themeJson: String) {
        private val used = AtomicBoolean(false)

        /** Authority must be a nonblocking, independently revocable token read. One shot. */
        fun commit(authority: () -> Boolean): ThemeCommitResult {
            check(used.compareAndSet(false, true)) { "Theme transaction already committed or attempted" }
            return commitPreparedTheme(themeJson, authority)
        }
    }

    /** Keep staging, replacement and other settings writers on the same store monitor. */
    @Synchronized
    private fun commitPreparedTheme(themeJson: String, authority: () -> Boolean): ThemeCommitResult {
        var replaced = false
        var temporary: File? = null
        try {
            // Read at commit, not prepare: concurrent peer/raw updates are retained.
            val root = readDocument()
            root.put("omarchyTheme", JSONObject(themeJson))
            val parent = checkNotNull(file.absoluteFile.parentFile)
            check(parent.mkdirs() || parent.isDirectory) { "Could not create ${parent.absolutePath}" }
            temporary = File.createTempFile(".${file.name}.tmp-", "", parent)
            themeIo.stage(temporary, root.toString(2).toByteArray(Charsets.UTF_8))
            // No potentially blocking operation between this check and rename. A revoke during
            // a blocked rename cannot be undone; callers must not promise strict cancellation.
            if (!authority()) return ThemeCommitResult(ThemeCommitStatus.NOT_COMMITTED)
            themeIo.replace(temporary, file)
            replaced = true
            themeIo.syncDirectory(parent)
            return ThemeCommitResult(ThemeCommitStatus.COMMITTED)
        } catch (error: Exception) {
            return ThemeCommitResult(
                if (replaced) ThemeCommitStatus.INDETERMINATE else ThemeCommitStatus.NOT_COMMITTED,
                error,
            )
        } finally {
            temporary?.delete()
        }
    }

    @Synchronized
    fun read(): LauncherSettings {
        val root = readDocument()
        migrateLegacyPeerIfNeeded(root)
        return LauncherSettings.parse(root)
    }

    @Synchronized
    fun write(settings: LauncherSettings): LauncherSettings {
        // A full-model UI snapshot can predate a remote palette transaction. Only explicit
        // theme setters may replace the persisted theme; preserve it at the write boundary.
        val updated = settings.toJson()
        val latest = readDocument()
        if (latest.has("omarchyTheme")) updated.put("omarchyTheme", latest.get("omarchyTheme"))
        else updated.remove("omarchyTheme")
        writeDocument(updated)
        return LauncherSettings.parse(updated)
    }

    /** Applies a raw document transaction and writes only after the result is valid JSON. */
    @Synchronized
    fun updateRaw(transform: (JSONObject) -> JSONObject): LauncherSettings {
        val updated = transform(JSONObject(readDocument().toString()))
        val validated = JSONObject(updated.toString())
        writeDocument(validated)
        return LauncherSettings.parse(validated)
    }

    /** Stores peer state at settings.json's root, including an explicit null on disconnect. */
    @Synchronized
    fun updatePeer(peer: OmarchyPeer?): LauncherSettings = updateRaw { root ->
        JSONObject(PeerConfigEditor.store(root.toString(), peer))
    }

    /** Returns the legacy-compatible `{colors:{...}}` theme response. */
    @Synchronized
    fun themeSnapshot(): JSONObject {
        val root = readDocument()
        root.optJSONObject("omarchyTheme")?.let { return JSONObject(it.toString()) }
        val colors = JSONObject()
        root.keys().forEach { key ->
            val normalized = key.lowercase()
            if (THEME_KEY_PARTS.any(normalized::contains)) {
                colors.put(key, root.get(key))
            }
        }
        return JSONObject().put("colors", colors)
    }

    /** Stores a canonical Omarchy palette under one namespaced settings key. */
    @Synchronized
    fun updateOmarchyTheme(payload: JSONObject): LauncherSettings {
        val palette = OmarchyThemePalette.parse(payload)
        if (palette.colors.isEmpty()) return updateTheme(payload)
        return updateRaw { root ->
            root.put("omarchyTheme", palette.toJson())
            root
        }
    }

    /** Merges every supplied top-level theme/settings entry without dropping extensions. */
    @Synchronized
    fun updateTheme(changes: JSONObject): LauncherSettings = updateRaw { root ->
        changes.keys().forEach { key -> root.put(key, changes.get(key)) }
        root
    }

    private fun readDocument(): JSONObject =
        if (file.isFile) JSONObject(file.readText()) else JSONObject()

    private fun migrateLegacyPeerIfNeeded(root: JSONObject) {
        if (root.has("omarchyPeer")) return
        val legacyFile = legacyPeerFile?.takeIf(File::isFile) ?: return
        val legacyRoot = JSONObject(legacyFile.readText())
        val legacySettings = legacyRoot.optJSONObject("settings") ?: return
        val legacyPeerJson = legacySettings.optJSONObject("omarchyPeer") ?: return
        val peer = LauncherSettings.parse(JSONObject().put("omarchyPeer", legacyPeerJson)).omarchyPeer ?: return

        val migratedPeer = JSONObject(legacyPeerJson.toString())
            .put("ip", peer.host)
            .put("port", peer.port)
            .put("id", peer.id)
        root.put("omarchyPeer", migratedPeer)
        writeDocument(root)

        legacySettings.remove("omarchyPeer")
        writeAtomically(legacyFile, legacyRoot.toString(2))
    }

    private fun writeDocument(root: JSONObject) {
        writeAtomically(file, root.toString(2))
    }

    private fun writeAtomically(target: File, source: String) {
        val parent = checkNotNull(target.absoluteFile.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "Could not create ${parent.absolutePath}" }
        val temporary = File.createTempFile(".${target.name}.tmp-", "", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(source.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "Could not atomically replace ${target.absolutePath}" }
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val FILE_NAME = "settings.json"
        private val THEME_KEY_PARTS = listOf("color", "tema", "theme", "background", "accent")
    }
}
