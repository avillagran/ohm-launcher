package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject

/** Values supported by the launcher's locale selector. */
enum class LauncherLanguage(val wireValue: String) {
    AUTO("auto"),
    DEFAULT("default");

    companion object {
        fun fromWireValue(value: Any?): LauncherLanguage? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Screen edges accepted by both launcher bars. */
enum class LauncherEdge(val wireValue: String) {
    TOP("top"),
    BOTTOM("bottom"),
    LEFT("left"),
    RIGHT("right");

    companion object {
        fun fromWireValue(value: Any?): LauncherEdge? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class LauncherBarKind { FAVORITES, SEARCH }

object LauncherBarPlacement {
    fun move(settings: LauncherSettings, bar: LauncherBarKind, edge: LauncherEdge): LauncherSettings =
        when (bar) {
            LauncherBarKind.FAVORITES -> settings.copy(favoritesBarPosition = edge)
            LauncherBarKind.SEARCH -> settings.copy(bottomBarPosition = edge)
        }
}

/** Explicit favorites layouts. A null value means edge-dependent automatic layout. */
enum class FavoritesBarMode(val wireValue: String) {
    HORIZONTAL("horizontal"),
    VERTICAL("vertical"),
    GRID("grid"),
    LIST("list");

    companion object {
        fun fromWireValue(value: Any?): FavoritesBarMode? = entries.firstOrNull { it.wireValue == value }
    }
}

data class ControlPosition(val dx: Double, val dy: Double)

/** Typed effective settings backed by the original JSON for lossless extension-field updates. */
data class LauncherSettings(
    val fontFamily: String,
    val textScale: Double,
    val boxSpacing: Double,
    val boxRadius: Double,
    val boxBorderVisible: Boolean,
    val boxBorderWidth: Double,
    val boxItemSize: Double,
    val barRadius: Double,
    val settingsPanelOpacity: Double,
    val language: LauncherLanguage,
    val favoritesBarVisible: Boolean,
    val favoritesBarPosition: LauncherEdge,
    val favoritesBarMode: FavoritesBarMode?,
    val bottomBarVisible: Boolean,
    val bottomBarPosition: LauncherEdge,
    val omarchyBarMode: Boolean,
    val applyOmarchyThemeToSystem: Boolean,
    val gestureNavigationEnabled: Boolean,
    val showTapBoxes: Boolean,
    val apiServerEnabled: Boolean,
    val apiServerPort: Int,
    val shellPreferTermux: Boolean,
    val quakeTerminal: Boolean,
    val aiBaseUrl: String,
    val aiApiKey: String,
    val aiModel: String,
    val aiSystemPrompt: String,
    val omarchyControlPosition: ControlPosition,
    val omarchyPeer: OmarchyPeer?,
    val raw: JSONObject,
) {
    val effectiveFavoritesBarMode: FavoritesBarMode
        get() = favoritesBarMode ?: when (favoritesBarPosition) {
            LauncherEdge.LEFT, LauncherEdge.RIGHT -> FavoritesBarMode.VERTICAL
            LauncherEdge.TOP, LauncherEdge.BOTTOM -> FavoritesBarMode.HORIZONTAL
        }

    fun toJson(): JSONObject {
        val result = JSONObject(raw.toString())
        result.put("fontFamily", fontFamily)
        result.put("textScale", textScale)
        result.put("boxSpacing", boxSpacing)
        result.put("boxRadius", boxRadius)
        result.put("boxBorderVisible", boxBorderVisible)
        result.put("boxBorderWidth", boxBorderWidth)
        result.put("boxItemSize", boxItemSize)
        result.put("barRadius", barRadius)
        result.put("settingsPanelOpacity", settingsPanelOpacity)
        result.put("language", language.wireValue)
        result.put("favoritesBarVisible", favoritesBarVisible)
        result.put("favoritesBarPosition", favoritesBarPosition.wireValue)
        result.put("favoritesBarMode", favoritesBarMode?.wireValue ?: JSONObject.NULL)
        result.put("bottomBarVisible", bottomBarVisible)
        result.put("bottomBarPosition", bottomBarPosition.wireValue)
        result.put("omarchyBarMode", omarchyBarMode)
        result.put("applyOmarchyThemeToSystem", applyOmarchyThemeToSystem)
        result.put("gestureNavigationEnabled", gestureNavigationEnabled)
        result.put("showTapBoxes", showTapBoxes)
        result.put("apiServerEnabled", apiServerEnabled)
        result.put("apiServerPort", apiServerPort)
        result.put("shellPreferTermux", shellPreferTermux)
        result.put("quakeTerminal", quakeTerminal)
        result.put("aiBaseUrl", aiBaseUrl)
        result.put("aiApiKey", aiApiKey)
        result.put("aiModel", aiModel)
        result.put("aiSystemPrompt", aiSystemPrompt)

        val position = result.optJSONObject("omarchyControlPos") ?: JSONObject()
        position.put("dx", omarchyControlPosition.dx)
        position.put("dy", omarchyControlPosition.dy)
        result.put("omarchyControlPos", position)

        val peer = omarchyPeer
        if (peer == null) {
            result.put("omarchyPeer", JSONObject.NULL)
        } else {
            val peerJson = result.optJSONObject("omarchyPeer") ?: JSONObject()
            peerJson.put("ip", peer.host)
            peerJson.put("port", peer.port)
            peerJson.put("id", peer.id)
            result.put("omarchyPeer", peerJson)
        }
        return result
    }

    companion object {
        fun parse(source: String): LauncherSettings = parse(JSONObject(source))

        fun parse(root: JSONObject): LauncherSettings {
            val position = root.optJSONObject("omarchyControlPos")
            return LauncherSettings(
                fontFamily = root.string("fontFamily") ?: "Predeterminada",
                textScale = (root.finiteNumber("textScale") ?: 1.0).coerceIn(0.8, 1.4),
                boxSpacing = (root.finiteNumber("boxSpacing") ?: 1.0).coerceIn(0.0, 2.0),
                boxRadius = (root.finiteNumber("boxRadius") ?: 14.0).coerceIn(0.0, 28.0),
                boxBorderVisible = root.boolean("boxBorderVisible") ?: true,
                boxBorderWidth = (root.finiteNumber("boxBorderWidth") ?: 1.0).coerceIn(0.0, 8.0),
                boxItemSize = (root.finiteNumber("boxItemSize") ?: 48.0).coerceIn(36.0, 72.0),
                barRadius = (root.finiteNumber("barRadius") ?: 18.0).coerceIn(0.0, 28.0),
                settingsPanelOpacity = (root.finiteNumber("settingsPanelOpacity") ?: 0.86).coerceIn(0.5, 1.0),
                language = LauncherLanguage.fromWireValue(root.opt("language")) ?: LauncherLanguage.AUTO,
                favoritesBarVisible = root.boolean("favoritesBarVisible") ?: true,
                favoritesBarPosition = LauncherEdge.fromWireValue(root.opt("favoritesBarPosition")) ?: LauncherEdge.BOTTOM,
                favoritesBarMode = FavoritesBarMode.fromWireValue(root.opt("favoritesBarMode")),
                bottomBarVisible = root.boolean("bottomBarVisible") ?: true,
                bottomBarPosition = LauncherEdge.fromWireValue(root.opt("bottomBarPosition")) ?: LauncherEdge.TOP,
                omarchyBarMode = root.boolean("omarchyBarMode") ?: true,
                applyOmarchyThemeToSystem = root.boolean("applyOmarchyThemeToSystem") ?: true,
                gestureNavigationEnabled = root.boolean("gestureNavigationEnabled") ?: false,
                showTapBoxes = root.boolean("showTapBoxes") ?: false,
                apiServerEnabled = root.boolean("apiServerEnabled") ?: true,
                apiServerPort = root.integer("apiServerPort") ?: 8753,
                shellPreferTermux = root.boolean("shellPreferTermux") ?: false,
                quakeTerminal = root.boolean("quakeTerminal") ?: true,
                aiBaseUrl = root.string("aiBaseUrl") ?: "",
                aiApiKey = root.string("aiApiKey") ?: "",
                aiModel = root.string("aiModel") ?: "",
                aiSystemPrompt = root.string("aiSystemPrompt") ?: "",
                omarchyControlPosition = ControlPosition(
                    dx = position?.finiteNumber("dx") ?: 8.0,
                    dy = position?.finiteNumber("dy") ?: 80.0,
                ),
                omarchyPeer = parsePeer(root.optJSONObject("omarchyPeer")),
                raw = JSONObject(root.toString()),
            )
        }

        private fun parsePeer(json: JSONObject?): OmarchyPeer? {
            if (json == null) return null
            val host = json.string("ip") ?: return null
            val port = json.integer("port") ?: 8753
            val id = json.string("id") ?: "omarchy-pc"
            val token = json.string("token") ?: ""
            return runCatching { OmarchyPeer(host, port, id, token) }.getOrNull()
        }

        private fun JSONObject.string(key: String): String? = opt(key) as? String

        private fun JSONObject.boolean(key: String): Boolean? = opt(key) as? Boolean

        private fun JSONObject.finiteNumber(key: String): Double? =
            (opt(key) as? Number)?.toDouble()?.takeIf(Double::isFinite)

        private fun JSONObject.integer(key: String): Int? {
            val number = opt(key) as? Number ?: return null
            val double = number.toDouble()
            if (!double.isFinite() || double < Int.MIN_VALUE || double > Int.MAX_VALUE) return null
            val integer = double.toInt()
            return integer.takeIf { it.toDouble() == double }
        }
    }
}
