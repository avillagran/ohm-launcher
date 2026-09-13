package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persistent floating widgets injected through the local API. */
class RuntimeWidgetStore(private val file: File) {
    @Synchronized
    fun load(): List<JSONObject> = runCatching {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        buildList {
            for (index in 0 until minOf(array.length(), MAX_WIDGETS)) {
                array.optJSONObject(index)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun append(source: String, format: String) {
        require(source.toByteArray().size <= MAX_SOURCE_BYTES) { "widget_source_too_large" }
        val additions = when (format.lowercase()) {
            "qml" -> listOf(JSONObject().put("type", "qml").put("source", source))
            "json" -> parseJsonNodes(source)
            else -> throw IllegalArgumentException("unsupported_widget_format")
        }
        val updated = (load() + additions).takeLast(MAX_WIDGETS)
        write(updated)
    }

    @Synchronized
    fun clear() = write(emptyList())

    private fun parseJsonNodes(source: String): List<JSONObject> {
        val trimmed = source.trim()
        if (trimmed.startsWith("{")) return listOf(JSONObject(trimmed))
        val array = JSONArray(trimmed)
        return buildList {
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(::add)
        }
    }

    private fun write(nodes: List<JSONObject>) {
        file.parentFile?.mkdirs()
        val temporary = file.parentFile.resolve("${file.name}.tmp")
        temporary.writeText(JSONArray(nodes).toString(2))
        check(temporary.renameTo(file) || temporary.copyTo(file, overwrite = true).let { temporary.delete() })
    }

    companion object {
        private const val MAX_WIDGETS = 100
        private const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
    }
}
