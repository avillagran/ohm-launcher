package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray
import org.json.JSONObject

enum class EdgePosition(val jsonName: String) {
    TOP("top"),
    BOTTOM("bottom"),
    LEFT("left"),
    RIGHT("right");

    companion object {
        fun parse(value: String): EdgePosition = entries.firstOrNull { it.jsonName == value } ?: BOTTOM
    }
}

enum class EdgeDirection(val jsonName: String) {
    HORIZONTAL("horizontal"),
    VERTICAL("vertical"),
    GRID("grid"),
    LIST("list");

    companion object {
        fun parse(value: String): EdgeDirection = entries.firstOrNull { it.jsonName == value } ?: HORIZONTAL
    }
}

enum class EdgeItemType(val jsonName: String) {
    APP("app"),
    SYSTEM_WIDGET("system_widget"),
    PLUGIN("plugin"),
    UNKNOWN("");

    companion object {
        fun parse(value: String): EdgeItemType = entries.firstOrNull { it != UNKNOWN && it.jsonName == value } ?: UNKNOWN
    }
}

data class EdgeItemConfig(
    val type: EdgeItemType,
    val rawType: String,
    val packageName: String,
    val activity: String,
    val label: String,
    val provider: String,
    val pluginId: String,
    val raw: JSONObject,
) {
    companion object {
        fun parse(json: JSONObject): EdgeItemConfig {
            val rawType = json.optString("type", "")
            return EdgeItemConfig(
                type = EdgeItemType.parse(rawType),
                rawType = rawType,
                packageName = json.optString("package", ""),
                activity = json.optString("activity", ""),
                label = json.optString("label", ""),
                provider = json.optString("provider", ""),
                pluginId = json.optString("pluginId", ""),
                raw = json,
            )
        }
    }
}

data class EdgeBoxConfig(
    val id: String,
    val name: String,
    val edge: EdgePosition,
    val direction: EdgeDirection,
    val visible: Boolean,
    val showTitle: Boolean,
    val compact: Boolean,
    val showExpandButton: Boolean,
    val compactItem: Int,
    val color: String,
    val items: List<EdgeItemConfig>,
    val raw: JSONObject,
) {
    companion object {
        fun parse(json: JSONObject, index: Int): EdgeBoxConfig {
            val itemsJson = json.optJSONArray("items") ?: JSONArray()
            val items = buildList {
                for (itemIndex in 0 until itemsJson.length()) {
                    val item = itemsJson.optJSONObject(itemIndex) ?: continue
                    add(EdgeItemConfig.parse(item))
                }
            }
            return EdgeBoxConfig(
                id = json.optString("id", ""),
                name = json.optString("name").ifBlank { "Caja ${index + 1}" },
                edge = EdgePosition.parse(json.optString("edge", "bottom")),
                direction = EdgeDirection.parse(json.optString("direction", "horizontal")),
                visible = json.optBoolean("visible", true),
                showTitle = json.optBoolean("showTitle", true),
                compact = json.optBoolean("compact", false),
                showExpandButton = when {
                    json.has("showExpandButton") -> json.optBoolean("showExpandButton", true)
                    json.has("showToggle") -> json.optBoolean("showToggle", true)
                    else -> true
                },
                compactItem = json.optInt("compactItem", 0).coerceAtLeast(0),
                color = json.optString("color", "#66E0FF"),
                items = items,
                raw = json,
            )
        }
    }
}