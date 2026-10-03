package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject

/** Data only: receiving a catalog never selects or applies a theme. */
internal data class FluxThemeCatalog(
    val current: String,
    val themes: List<Theme>,
) {
    data class Theme(val id: String, val label: String, val palette: OmarchyThemePalette)
}

/** Strict, bounded v1 payload from a pinned Flux desktop; no command dispatch. */
internal object FluxThemePacket {
    const val TYPE = "flux.omarchy_theme"
    const val MAX_PACKET_BYTES = 64 * 1024
    const val MAX_THEMES = 64
    const val MAX_COLORS = 48
    private val idPattern = Regex("[A-Za-z0-9](?:[A-Za-z0-9._ -]{0,62}[A-Za-z0-9])?")
    private val rolePattern = Regex("[a-z][a-z0-9_]{0,39}")
    private val colorPattern = Regex("#[0-9a-fA-F]{6}")

    /** Null for forbidden/irrelevant traffic; malformed authorized catalogs are rejected. */
    fun receive(type: String, body: JSONObject?, paired: Boolean, playStore: Boolean): FluxThemeCatalog? {
        if (type != TYPE || !paired || playStore) return null
        require(body != null) { "Missing theme catalog body" }
        require(body.toString().toByteArray(Charsets.UTF_8).size < MAX_PACKET_BYTES) { "Catalog too large" }
        exactKeys(body, setOf("kind", "version", "current", "themes"))
        require(string(body, "kind") == "catalog")
        require(body.getInt("version") == 1 && body.get("version") is Int) { "Unsupported theme catalog version" }
        val current = string(body, "current")
        require(idPattern.matches(current))
        val rawThemes = body.opt("themes") as? org.json.JSONArray ?: throw IllegalArgumentException("Missing themes")
        require(rawThemes.length() in 1..MAX_THEMES)
        val ids = mutableSetOf<String>()
        val themes = (0 until rawThemes.length()).map { index ->
            val raw = rawThemes.opt(index) as? JSONObject ?: throw IllegalArgumentException("Invalid theme")
            exactKeys(raw, setOf("id", "label", "palette"))
            val id = string(raw, "id")
            require(idPattern.matches(id) && ids.add(id)) { "Invalid or duplicate theme id" }
            val label = string(raw, "label")
            require(label.length in 1..80 && label == label.trim() && label.none { it.isISOControl() })
            val palette = raw.opt("palette") as? JSONObject ?: throw IllegalArgumentException("Missing palette")
            exactKeys(palette, setOf("name", "mode", "source", "colors"))
            val name = string(palette, "name")
            require(name.length in 1..80 && name == name.trim() && name.none { it.isISOControl() })
            val mode = when (string(palette, "mode")) {
                "dark" -> OmarchyThemeMode.DARK
                "light" -> OmarchyThemeMode.LIGHT
                else -> throw IllegalArgumentException("Invalid theme mode")
            }
            require(string(palette, "source") == "omarchy")
            val rawColors = palette.opt("colors") as? JSONObject ?: throw IllegalArgumentException("Missing colors")
            require(rawColors.length() in 1..MAX_COLORS)
            val colors = linkedMapOf<String, String>()
            rawColors.keys().forEach { role ->
                require(rolePattern.matches(role))
                val value = string(rawColors, role)
                require(colorPattern.matches(value))
                colors[role] = value
            }
            FluxThemeCatalog.Theme(id, label, OmarchyThemePalette(name, mode, colors))
        }
        require(current in ids) { "Current theme missing from catalog" }
        return FluxThemeCatalog(current, themes)
    }

    private fun string(objectValue: JSONObject, key: String): String =
        objectValue.opt(key) as? String ?: throw IllegalArgumentException("Invalid $key")

    private fun exactKeys(objectValue: JSONObject, expected: Set<String>) {
        require(objectValue.keys().asSequence().toSet() == expected) { "Unexpected catalog fields" }
    }
}
