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
    fun shouldQueueForActiveTransition(
        target: OmarchyThemePalette?,
        next: OmarchyThemePalette?,
    ): Boolean = target != null && target == next

    fun shouldAnimate(
        previous: OmarchyThemePalette?,
        next: OmarchyThemePalette?,
        width: Int,
        height: Int,
    ): Boolean = previous != next && next != null && width > 0 && height > 0
}

object OmarchyDesktopTextPolicy {
    fun preferredColor(colors: Map<String, String>): String? =
        colors["foreground"] ?: colors["bright_foreground"] ?: colors["accent"]

    fun shouldUseSecondary(pixels: IntArray, primary: Int): Boolean {
        if (pixels.isEmpty()) return false
        var visible = 0
        var primaryLike = 0
        val red = primary ushr 16 and 0xff
        val green = primary ushr 8 and 0xff
        val blue = primary and 0xff
        pixels.forEach { pixel ->
            if (pixel ushr 24 < 0x40) return@forEach
            visible++
            val dr = (pixel ushr 16 and 0xff) - red
            val dg = (pixel ushr 8 and 0xff) - green
            val db = (pixel and 0xff) - blue
            if (dr * dr + dg * dg + db * db <= PRIMARY_DISTANCE_SQUARED) primaryLike++
        }
        return visible > 0 && primaryLike.toFloat() / visible >= PRIMARY_COVERAGE
    }

    private const val PRIMARY_DISTANCE_SQUARED = 75 * 75
    private const val PRIMARY_COVERAGE = 0.20f
}

data class OmarchyThemePalette(
    val name: String,
    val mode: OmarchyThemeMode,
    val colors: Map<String, String>,
    val source: String = "omarchy",
    val background: OmarchyThemeBackground? = null,
    val desktopBackground: OmarchyDesktopBackground? = null,
    val geometry: OmarchyThemeGeometry? = null,
) {
    val useDarkSystemIcons: Boolean
        get() = mode == OmarchyThemeMode.LIGHT

    val hasSquareCorners: Boolean
        get() = geometry?.cornerRadius == 0f

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
            .also { root ->
                background?.let { root.put("background", it.toJson()) }
                desktopBackground?.let { root.put("desktopBackground", it.toJson()) }
                geometry?.let { root.put("geometry", it.toJson()) }
            }
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
                background = OmarchyThemeBackground.parse(payload.optJSONObject("background")),
                desktopBackground = OmarchyDesktopBackground.parse(payload.optJSONObject("desktopBackground")),
                geometry = OmarchyThemeGeometry.parse(payload.optJSONObject("geometry")),
            )
        }

        fun fromSettings(settings: JSONObject): OmarchyThemePalette? =
            settings.optJSONObject("omarchyTheme")?.let(::parse)
    }
}

data class OmarchyThemeGeometry(val cornerRadius: Float) {
    fun toJson(): JSONObject = JSONObject().put("cornerRadius", cornerRadius.toDouble())

    companion object {
        fun parse(raw: JSONObject?): OmarchyThemeGeometry? {
            raw ?: return null
            if (!raw.has("cornerRadius")) return null
            val radius = raw.optDouble("cornerRadius", Double.NaN)
            if (!radius.isFinite() || radius < 0.0) return null
            return OmarchyThemeGeometry(radius.coerceAtMost(128.0).toFloat())
        }
    }
}

object OmarchyThemeShapePolicy {
    fun surfaceRadius(requested: Float, palette: OmarchyThemePalette?): Float {
        val canonical = palette?.geometry?.cornerRadius ?: 0f
        return minOf(requested.coerceAtLeast(0f), canonical)
    }
}

object OmarchyThemeShapeState {
    @Volatile
    private var palette: OmarchyThemePalette? = null

    fun apply(value: OmarchyThemePalette?) {
        palette = value
    }

    fun surfaceRadiusPx(requestedPx: Float, density: Float): Float =
        OmarchyThemeShapePolicy.surfaceRadius(requestedPx / density.coerceAtLeast(0.01f), palette) * density
}

data class OmarchyDesktopBackground(
    val type: String,
    val path: String,
    val ttfx: OmarchyDesktopTtfx? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type)
        .put("path", path)
        .also { root -> ttfx?.let { root.put("ttfx", it.toJson()) } }

    companion object {
        fun parse(raw: JSONObject?): OmarchyDesktopBackground? {
            raw ?: return null
            val type = raw.optString("type").lowercase()
            val path = raw.optString("path")
            if (type.isBlank()) return null
            return OmarchyDesktopBackground(type, path, OmarchyDesktopTtfx.parse(raw.optJSONObject("ttfx")))
        }
    }
}

data class OmarchyDesktopTtfx(
    val enabled: Boolean,
    val effect: String?,
    val text: String?,
    val textSize: Int?,
    val audio: Boolean?,
    val intensity: Int?,
    val speed: Double?,
    val resolution: Int?,
    val reactivity: Int?,
) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).also { root ->
        effect?.let { root.put("effect", it) }
        text?.let { root.put("text", it) }
        textSize?.let { root.put("textSize", it) }
        audio?.let { root.put("audio", it) }
        intensity?.let { root.put("intensity", it) }
        speed?.let { root.put("speed", it) }
        resolution?.let { root.put("resolution", it) }
        reactivity?.let { root.put("reactivity", it) }
    }

    companion object {
        fun parse(raw: JSONObject?): OmarchyDesktopTtfx? {
            raw ?: return null
            return OmarchyDesktopTtfx(
                enabled = raw.optBoolean("enabled", false),
                effect = raw.stringOrNull("effect"),
                text = raw.stringOrNull("text"),
                textSize = raw.intOrNull("textSize"),
                audio = raw.booleanOrNull("audio"),
                intensity = raw.intOrNull("intensity"),
                speed = raw.doubleOrNull("speed"),
                resolution = raw.intOrNull("resolution"),
                reactivity = raw.intOrNull("reactivity"),
            )
        }
    }
}

object OmarchyDesktopBackgroundApplier {
    fun apply(desktop: JSONObject, background: OmarchyDesktopBackground?, fallbackPath: String) {
        val localTextGeometry = listOf("ttfxTextSize", "ttfxTextX", "ttfxTextY")
            .filter(desktop::has)
            .associateWith(desktop::get)
        desktop.put("backgroundImage", fallbackPath)
        val ttfx = background?.ttfx
        val enabled = background?.type == "audio" && ttfx?.enabled == true
        desktop.put("ttfxBackground", enabled)
        if (!enabled) return

        (background.path.takeIf(String::isNotBlank) ?: ttfx.effect)?.let { desktop.put("ttfxEffect", it) }
        ttfx.text?.let { desktop.put("ttfxText", it) }
        ttfx.textSize?.let { desktop.put("ttfxTextSize", it) }
        ttfx.audio?.let { desktop.put("ttfxAudio", it) }
        ttfx.intensity?.let { desktop.put("ttfxIntensity", it) }
        ttfx.speed?.let { desktop.put("ttfxSpeed", it) }
        ttfx.resolution?.let { desktop.put("ttfxResolution", it) }
        ttfx.reactivity?.let { desktop.put("ttfxReactivity", it) }
        localTextGeometry.forEach(desktop::put)
    }
}

internal object OmarchyLocalStyleSelection {
    fun apply(
        id: String,
        applyLocal: () -> Unit,
        publishSelection: (String) -> Unit,
        scheduleRemote: (String) -> Unit,
    ) {
        applyLocal()
        publishSelection(id)
        scheduleRemote(id)
    }
}

internal object OmarchyThemeBackgroundSelection {
    fun resolve(
        backgrounds: List<OmarchyBackgroundChoice>,
        backgroundId: String?,
    ): OmarchyBackgroundChoice? {
        val id = backgroundId?.takeIf { it.isNotBlank() } ?: return null
        return backgrounds.firstOrNull { it.id == id && !it.previewPath.isNullOrBlank() }
    }
}

internal object OmarchyLocalBackgroundPreview {
    fun apply(source: LauncherConfig, previewPath: String): LauncherConfig {
        require(previewPath.isNotBlank())
        val document = JSONObject(source.raw.toString())
        val desktops = document.optJSONArray("desktops") ?: return source
        for (index in 0 until desktops.length()) {
            desktops.optJSONObject(index)?.put("backgroundImage", previewPath)
        }
        return LauncherConfig.parse(document.toString())
    }
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

private fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.doubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null

private fun JSONObject.booleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null

data class OmarchyThemeBackground(
    val name: String,
    val mime: String,
    val sha256: String,
    val phonePath: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("mime", mime)
        .put("sha256", sha256)
        .also { root -> phonePath?.let { root.put("phonePath", it) } }

    companion object {
        private val shaPattern = Regex("^[0-9a-fA-F]{64}$")

        fun parse(raw: JSONObject?): OmarchyThemeBackground? {
            raw ?: return null
            val name = raw.optString("name").substringAfterLast('/').substringAfterLast('\\')
            val mime = raw.optString("mime")
            val sha256 = raw.optString("sha256").lowercase()
            if (name.isBlank() || mime.isBlank() || !shaPattern.matches(sha256)) return null
            val phonePath = raw.optString("phonePath").takeIf(String::isNotBlank)
            return OmarchyThemeBackground(name, mime, sha256, phonePath)
        }
    }
}
