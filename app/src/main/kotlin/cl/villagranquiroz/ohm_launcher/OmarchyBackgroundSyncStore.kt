package cl.villagranquiroz.ohm_launcher

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

internal class OmarchyPendingSelection {
    private val pending = AtomicReference<String?>(null)

    fun select(id: String) {
        require(id.isNotBlank())
        pending.set(id)
    }

    fun snapshot(): JSONObject {
        val id = pending.get()
        return if (id == null) JSONObject().put("pending", false)
        else JSONObject().put("pending", true).put("id", id)
    }

    fun acknowledge(payload: JSONObject) {
        val id = payload.optString("id")
        while (id.isNotEmpty()) {
            val current = pending.get()
            if (current != id || pending.compareAndSet(current, null)) return
        }
    }
}

/** Validated on-device copy of the background catalog pushed by Omarchy. */
internal class OmarchyBackgroundSyncStore(private val configRoot: File) {
    private val catalogFile: File
        get() = configRoot.resolve(FILE_NAME)

    fun persist(payload: JSONObject): OmarchyBackgroundCatalog? {
        val catalog = parse(payload) ?: return null
        return runCatching {
            check(configRoot.mkdirs() || configRoot.isDirectory)
            val temporary = configRoot.resolve(".$FILE_NAME.tmp")
            temporary.writeText(payload.toString(2))
            check(temporary.renameTo(catalogFile) || temporary.copyTo(catalogFile, overwrite = true).let { temporary.delete() })
            catalog
        }.getOrNull()
    }

    fun load(): OmarchyBackgroundCatalog? = runCatching {
        if (!catalogFile.isFile) null else parse(JSONObject(catalogFile.readText()))
    }.getOrNull()

    fun loadPreview(choice: OmarchyBackgroundChoice): ByteArray? = runCatching {
        val path = choice.previewPath ?: return null
        val file = File(path).canonicalFile
        check(isUnderShared(file) && file.isFile && file.length() in 1..MAX_PREVIEW_BYTES)
        file.readBytes()
    }.getOrNull()

    private fun parse(payload: JSONObject): OmarchyBackgroundCatalog? = runCatching {
        val currentJson = payload.getJSONObject("current")
        val current = OmarchyBackgroundCurrent(
            id = currentJson.getString("id"),
            type = currentJson.optString("type"),
            path = currentJson.optString("path"),
        )
        check(current.id.isEmpty() || validId(current.id))
        val array = payload.getJSONArray("backgrounds")
        check(array.length() in 1..MAX_BACKGROUND_COUNT)
        val backgrounds = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.getString("id")
                check(validId(id))
                val preview = item.optString("previewPath").takeIf(String::isNotBlank)?.let(::File)?.canonicalFile
                if (preview != null) check(isUnderShared(preview) && preview.isFile && preview.length() in 1..MAX_PREVIEW_BYTES)
                add(
                    OmarchyBackgroundChoice(
                        id = id,
                        label = item.optString("label", id).ifBlank { id },
                        type = item.optString("type"),
                        preview = item.optString("preview"),
                        hasPreview = preview != null || item.optBoolean("hasPreview", false),
                        previewPath = preview?.absolutePath,
                    ),
                )
            }
        }
        check(backgrounds.distinctBy { it.id }.size == backgrounds.size)
        OmarchyBackgroundCatalog(current, backgrounds)
    }.getOrNull()

    private fun isUnderShared(file: File): Boolean {
        val shared = configRoot.resolve("shared").canonicalFile
        return file.path.startsWith(shared.path + File.separator)
    }

    private fun validId(id: String): Boolean = id.isNotBlank() && id.length <= 2048 && '\u0000' !in id

    private companion object {
        const val FILE_NAME = "omarchy_background_catalog.json"
        const val MAX_BACKGROUND_COUNT = 1024
        const val MAX_PREVIEW_BYTES = 16L * 1024L * 1024L
    }
}