package cl.villagranquiroz.ohm_launcher

import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/** Thread-safe persistence for the Flutter-compatible settings document. */
class LauncherSettingsStore(
    private val file: File,
    private val legacyPeerFile: File? = null,
) {
    @Synchronized
    fun read(): LauncherSettings {
        val root = readDocument()
        migrateLegacyPeerIfNeeded(root)
        return LauncherSettings.parse(root)
    }

    @Synchronized
    fun write(settings: LauncherSettings) {
        writeDocument(settings.toJson())
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
