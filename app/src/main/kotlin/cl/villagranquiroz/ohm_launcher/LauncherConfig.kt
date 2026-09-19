package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray
import org.json.JSONObject

data class LauncherConfig(
    val wallpaper: String,
    val desktops: List<DesktopConfig>,
    val edgeBoxes: List<EdgeBoxConfig>,
    val favorites: List<String> = emptyList(),
    val raw: JSONObject = JSONObject(),
) {
    companion object {
        fun parse(source: String): LauncherConfig {
            val root = JSONObject(source)
            val desktopsJson = root.optJSONArray("desktops") ?: JSONArray()
            val desktops = buildList {
                for (index in 0 until desktopsJson.length()) {
                    add(DesktopConfig.parse(desktopsJson.optJSONObject(index) ?: JSONObject(), index))
                }
            }
            val edgeBoxesJson = root.optJSONArray("edgeBoxes") ?: JSONArray()
            val edgeBoxes = buildList {
                for (index in 0 until edgeBoxesJson.length()) {
                    val box = edgeBoxesJson.optJSONObject(index) ?: continue
                    add(EdgeBoxConfig.parse(box, index))
                }
            }
            return LauncherConfig(
                wallpaper = root.optString("wallpaper", "#0B0F14"),
                desktops = desktops.ifEmpty { listOf(DesktopConfig.default(0)) },
                edgeBoxes = edgeBoxes,
                raw = root,
            )
        }
    }
}

data class DesktopConfig(
    val name: String,
    val background: String,
    val backgroundImage: String,
    val gridColumns: Int,
    val gridRows: Int,
    val ttfx: TtfxConfig,
    val widgets: List<WidgetNode>,
    val raw: JSONObject,
) {
    companion object {
        fun default(index: Int) = parse(JSONObject(), index)

        fun parse(json: JSONObject, index: Int): DesktopConfig {
            val widgetsJson = json.optJSONArray("widgets") ?: JSONArray()
            val widgets = buildList {
                for (widgetIndex in 0 until widgetsJson.length()) {
                    val widget = widgetsJson.optJSONObject(widgetIndex) ?: continue
                    add(WidgetNode.parse(widget, widgetIndex))
                }
            }
            return DesktopConfig(
                name = json.optString("name").ifBlank { "Escritorio ${index + 1}" },
                background = json.optString("background", "#0B0F14"),
                backgroundImage = json.optString("backgroundImage", ""),
                gridColumns = json.optInt("gridColumns", 14).coerceIn(1, 64),
                gridRows = json.optInt("gridRows", 10).coerceIn(1, 64),
                ttfx = TtfxConfig.parse(json),
                widgets = widgets,
                raw = json,
            )
        }
    }
}

data class TtfxConfig(
    val enabled: Boolean,
    val effect: String,
    val text: String,
    val textSize: Int,
    val textX: Double,
    val textY: Double,
    val audio: Boolean,
    val intensity: Int,
    val speed: Double,
    val resolution: Int,
    val reactivity: Int,
    val controlsVisible: Boolean = true,
) {
    companion object {
        fun parse(json: JSONObject) = TtfxConfig(
            enabled = json.optBoolean("ttfxBackground", true),
            effect = json.optString("ttfxEffect", "matrix"),
            text = json.optString("ttfxText", "OHM"),
            textSize = json.optInt("ttfxTextSize", 3).coerceIn(1, 12),
            textX = json.optDouble("ttfxTextX", 0.5).coerceIn(0.0, 1.0),
            textY = json.optDouble("ttfxTextY", 0.5).coerceIn(0.0, 1.0),
            audio = json.optBoolean("ttfxAudio", true),
            intensity = json.optInt("ttfxIntensity", 5).coerceIn(0, 10),
            speed = json.optDouble("ttfxSpeed", 1.0).coerceIn(0.2, 5.0),
            resolution = json.optInt("ttfxResolution", 2).coerceIn(1, 8),
            reactivity = json.optInt("ttfxReactivity", 2).coerceIn(0, 5),
            controlsVisible = json.optBoolean("ttfxControlsVisible", true),
        )
    }
}

data class WidgetNode(
    val type: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val span: Int?,
    val raw: JSONObject,
) {
    constructor(type: String, raw: JSONObject) : this(
        type = type,
        x = raw.optInt("x", 0).coerceAtLeast(0),
        y = raw.optInt("y", 0).coerceAtLeast(0),
        width = (if (raw.has("w")) raw.optInt("w", 4) else raw.optInt("span", 4)).coerceAtLeast(1),
        height = raw.optInt("h", 1).coerceAtLeast(1),
        span = if (raw.has("span") && !raw.isNull("span")) raw.optInt("span", 1).coerceAtLeast(1) else null,
        raw = raw,
    )

    companion object {
        fun parse(json: JSONObject, index: Int): WidgetNode {
            val span = if (json.has("span") && !json.isNull("span")) {
                json.optInt("span", 1).coerceAtLeast(1)
            } else {
                null
            }
            return WidgetNode(
                type = json.optString("type", "container"),
                x = json.optInt("x", 0).coerceAtLeast(0),
                y = json.optInt("y", index).coerceAtLeast(0),
                width = (if (json.has("w")) json.optInt("w", 4) else span ?: 4).coerceAtLeast(1),
                height = json.optInt("h", 1).coerceAtLeast(1),
                span = span,
                raw = json,
            )
        }
    }
}
