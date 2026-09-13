package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject

enum class OmarchyThemeMode { DARK, LIGHT }

object OmarchyThemeColor {
    fun tint(source: Int, accent: Int): Int {
        val brightness = maxOf(source ushr 16 and 0xff, source ushr 8 and 0xff, source and 0xff)
        fun scale(channel: Int): Int = (channel * brightness + 127) / 255
        return (source ushr 24 shl 24) or
            (scale(accent ushr 16 and 0xff) shl 16) or
            (scale(accent ushr 8 and 0xff) shl 8) or
            scale(accent and 0xff)
    }
}

object OmarchyThemeTransitionPolicy {
    fun shouldAnimate(
        previous: OmarchyThemePalette?,
        next: OmarchyThemePalette?,
        width: Int,
        height: Int,
    ): Boolean = previous != next && next != null && width > 0 && height > 0
}

data class OmarchyThemePalette(
    val name: String,
    val mode: OmarchyThemeMode,
    val colors: Map<String, String>,
    val source: String = "omarchy",
) {
    val useDarkSystemIcons: Boolean
        get() = mode == OmarchyThemeMode.LIGHT

    fun color(role: String, fallback: String? = null): String? =
        colors[role.lowercase()] ?: fallback

    fun toJson(): JSONObject {
        val palette = JSONObject()
        colors.forEach { (role, value) -> palette.put(role, value) }
        return JSONObject()
            .put("name", name)
            .put("mode", mode.name.lowercase())
            .put("source", source)
            .put("colors", palette)
    }

    companion object {
        private val colorPattern = Regex("^#[0-9a-fA-F]{3,4}([0-9a-fA-F]{3,4})?$")

        fun parse(payload: JSONObject): OmarchyThemePalette {
            val rawColors = payload.optJSONObject("colors") ?: JSONObject()
            val colors = linkedMapOf<String, String>()
            rawColors.keys().forEach { key ->
                val value = rawColors.opt(key) as? String ?: return@forEach
                if (colorPattern.matches(value)) colors[key.lowercase()] = value
            }
            val mode = when (payload.optString("mode", rawColors.optString("mode", "dark")).lowercase()) {
                "light" -> OmarchyThemeMode.LIGHT
                else -> OmarchyThemeMode.DARK
            }
            return OmarchyThemePalette(
                name = payload.optString("name", "Omarchy").ifBlank { "Omarchy" },
                mode = mode,
                colors = colors,
                source = payload.optString("source", "omarchy").ifBlank { "omarchy" },
            )
        }

        fun fromSettings(settings: JSONObject): OmarchyThemePalette? =
            settings.optJSONObject("omarchyTheme")?.let(::parse)
    }
}
