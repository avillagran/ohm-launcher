package cl.villagranquiroz.ohm_launcher

import java.io.File

/** Omarchy schema v1 constants shared by plugin storage and consumers. */
object PluginContract {
    const val SCHEMA_VERSION = 1
    const val PUBLIC_ROOT = "/sdcard/OhmLauncher"
    const val ENABLED_DIRECTORY = "plugins"
    const val DISABLED_DIRECTORY = "plugins.disabled"

    val KIND_TO_ENTRY_POINT: Map<String, String> = linkedMapOf(
        "bar-widget" to "barWidget",
        "panel" to "panel",
        "overlay" to "overlay",
        "menu" to "menu",
        "service" to "service",
        "bar" to "bar",
    )
}

data class PluginManifest(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val license: String,
    val description: String,
    val kinds: List<String>,
    val entryPoints: Map<String, String>,
    val barWidget: Map<String, Any?>? = null,
)

/** A directory discovered on disk, including diagnostics when it is invalid. */
data class Plugin(
    val id: String,
    val folder: File,
    val manifest: PluginManifest?,
    val validationErrors: List<String>,
) {
    val isValid: Boolean get() = manifest != null && validationErrors.isEmpty()
    val kinds: List<String> get() = manifest?.kinds.orEmpty()

    fun entryFileForKind(kind: String): File? {
        if (!isValid) return null
        val key = PluginContract.KIND_TO_ENTRY_POINT[kind] ?: return null
        val relativePath = manifest?.entryPoints?.get(key)?.takeIf(String::isNotEmpty) ?: return null
        return folder.resolve(relativePath)
    }
}
