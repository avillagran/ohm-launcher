package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherSettingsTest {
    @Test
    fun movingLauncherBarsChangesOnlyTheSelectedBarEdge() {
        val initial = LauncherSettings.parse("{}")

        val favorites = LauncherBarPlacement.move(initial, LauncherBarKind.FAVORITES, LauncherEdge.LEFT)
        val search = LauncherBarPlacement.move(favorites, LauncherBarKind.SEARCH, LauncherEdge.RIGHT)

        assertEquals(LauncherEdge.LEFT, search.favoritesBarPosition)
        assertEquals(LauncherEdge.RIGHT, search.bottomBarPosition)
    }

    @Test
    fun missingFileShapeUsesEveryContractDefault() {
        val settings = LauncherSettings.parse("{}")

        assertEquals("Predeterminada", settings.fontFamily)
        assertEquals(1.0, settings.textScale, 0.0)
        assertEquals(1.0, settings.boxSpacing, 0.0)
        assertEquals(14.0, settings.boxRadius, 0.0)
        assertEquals(18.0, settings.barRadius, 0.0)
        assertEquals(0.86, settings.settingsPanelOpacity, 0.0)
        assertEquals(LauncherLanguage.AUTO, settings.language)
        assertTrue(settings.favoritesBarVisible)
        assertEquals(LauncherEdge.BOTTOM, settings.favoritesBarPosition)
        assertNull(settings.favoritesBarMode)
        assertTrue(settings.bottomBarVisible)
        assertEquals(LauncherEdge.TOP, settings.bottomBarPosition)
        assertFalse(settings.gestureNavigationEnabled)
        assertFalse(settings.showTapBoxes)
        assertTrue(settings.apiServerEnabled)
        assertEquals(8753, settings.apiServerPort)
        assertFalse(settings.shellPreferTermux)
        assertTrue(settings.quakeTerminal)
        assertEquals("", settings.aiBaseUrl)
        assertEquals("", settings.aiApiKey)
        assertEquals("", settings.aiModel)
        assertEquals("", settings.aiSystemPrompt)
        assertEquals(8.0, settings.omarchyControlPosition.dx, 0.0)
        assertEquals(80.0, settings.omarchyControlPosition.dy, 0.0)
        assertNull(settings.omarchyPeer)
    }

    @Test
    fun parsesEveryContractField() {
        val settings = LauncherSettings.parse(
            """{
              "fontFamily":"Inter","textScale":1.2,"boxSpacing":0.5,
              "boxRadius":4,"barRadius":7,"settingsPanelOpacity":0.72,"language":"default",
              "favoritesBarVisible":false,"favoritesBarPosition":"right",
              "favoritesBarMode":"grid","bottomBarVisible":false,
              "bottomBarPosition":"left","gestureNavigationEnabled":true,
              "showTapBoxes":true,"apiServerEnabled":false,"apiServerPort":9000,
              "shellPreferTermux":true,"quakeTerminal":false,
              "aiBaseUrl":"https://ai.example","aiApiKey":"secret",
              "aiModel":"model","aiSystemPrompt":"prompt",
              "omarchyControlPos":{"dx":12.5,"dy":42},
              "omarchyPeer":{"ip":"192.168.1.20","port":9443,"id":"desk"}
            }""",
        )

        assertEquals("Inter", settings.fontFamily)
        assertEquals(1.2, settings.textScale, 0.0)
        assertEquals(0.5, settings.boxSpacing, 0.0)
        assertEquals(4.0, settings.boxRadius, 0.0)
        assertEquals(7.0, settings.barRadius, 0.0)
        assertEquals(0.72, settings.settingsPanelOpacity, 0.0)
        assertEquals(LauncherLanguage.DEFAULT, settings.language)
        assertFalse(settings.favoritesBarVisible)
        assertEquals(LauncherEdge.RIGHT, settings.favoritesBarPosition)
        assertEquals(FavoritesBarMode.GRID, settings.favoritesBarMode)
        assertFalse(settings.bottomBarVisible)
        assertEquals(LauncherEdge.LEFT, settings.bottomBarPosition)
        assertTrue(settings.gestureNavigationEnabled)
        assertTrue(settings.showTapBoxes)
        assertFalse(settings.apiServerEnabled)
        assertEquals(9000, settings.apiServerPort)
        assertTrue(settings.shellPreferTermux)
        assertFalse(settings.quakeTerminal)
        assertEquals("https://ai.example", settings.aiBaseUrl)
        assertEquals("secret", settings.aiApiKey)
        assertEquals("model", settings.aiModel)
        assertEquals("prompt", settings.aiSystemPrompt)
        assertEquals(ControlPosition(12.5, 42.0), settings.omarchyControlPosition)
        assertEquals(OmarchyPeer("192.168.1.20", 9443, "desk"), settings.omarchyPeer)
    }

    @Test
    fun nullWrongTypesAndInvalidEnumsUseDefaultsWhileNumbersClampToSchemaRanges() {
        val settings = LauncherSettings.parse(
            """{
              "fontFamily":null,"textScale":99,"boxSpacing":-2,
              "boxRadius":29,"barRadius":-1,"settingsPanelOpacity":0.1,"language":"es",
              "favoritesBarVisible":"yes","favoritesBarPosition":"center",
              "favoritesBarMode":"auto","bottomBarVisible":null,
              "bottomBarPosition":7,"gestureNavigationEnabled":null,
              "showTapBoxes":1,"apiServerEnabled":null,"apiServerPort":8.5,
              "shellPreferTermux":null,"quakeTerminal":null,
              "aiBaseUrl":null,"aiApiKey":false,"aiModel":null,
              "aiSystemPrompt":7,"omarchyControlPos":{"dx":null,"dy":"80"}
            }""",
        )

        assertEquals("Predeterminada", settings.fontFamily)
        assertEquals(1.4, settings.textScale, 0.0)
        assertEquals(0.0, settings.boxSpacing, 0.0)
        assertEquals(28.0, settings.boxRadius, 0.0)
        assertEquals(0.0, settings.barRadius, 0.0)
        assertEquals(0.5, settings.settingsPanelOpacity, 0.0)
        assertEquals(LauncherLanguage.AUTO, settings.language)
        assertTrue(settings.favoritesBarVisible)
        assertEquals(LauncherEdge.BOTTOM, settings.favoritesBarPosition)
        assertNull(settings.favoritesBarMode)
        assertTrue(settings.bottomBarVisible)
        assertEquals(LauncherEdge.TOP, settings.bottomBarPosition)
        assertFalse(settings.gestureNavigationEnabled)
        assertFalse(settings.showTapBoxes)
        assertTrue(settings.apiServerEnabled)
        assertEquals(8753, settings.apiServerPort)
        assertFalse(settings.shellPreferTermux)
        assertTrue(settings.quakeTerminal)
        assertEquals("", settings.aiBaseUrl)
        assertEquals("", settings.aiApiKey)
        assertEquals("", settings.aiModel)
        assertEquals("", settings.aiSystemPrompt)
        assertEquals(ControlPosition(8.0, 80.0), settings.omarchyControlPosition)
    }

    @Test
    fun missingAndExplicitNullNullableValuesHaveTheSameEffectiveMeaning() {
        assertNull(LauncherSettings.parse("{}").favoritesBarMode)
        assertNull(LauncherSettings.parse("""{"favoritesBarMode":null}""").favoritesBarMode)
        assertNull(LauncherSettings.parse("{}").omarchyPeer)
        assertNull(LauncherSettings.parse("""{"omarchyPeer":null}""").omarchyPeer)
    }

    @Test
    fun nullFavoritesModeResolvesFromItsEdgeWhileExplicitModeWins() {
        assertEquals(
            FavoritesBarMode.HORIZONTAL,
            LauncherSettings.parse("""{"favoritesBarPosition":"bottom"}""").effectiveFavoritesBarMode,
        )
        assertEquals(
            FavoritesBarMode.VERTICAL,
            LauncherSettings.parse("""{"favoritesBarPosition":"left"}""").effectiveFavoritesBarMode,
        )
        assertEquals(
            FavoritesBarMode.LIST,
            LauncherSettings.parse(
                """{"favoritesBarPosition":"left","favoritesBarMode":"list"}""",
            ).effectiveFavoritesBarMode,
        )
    }

    @Test
    fun peerObjectDefaultsOptionalFieldsAndRejectsMissingRequiredIp() {
        assertEquals(
            OmarchyPeer("desktop.local", 8753, "omarchy-pc"),
            LauncherSettings.parse("""{"omarchyPeer":{"ip":"desktop.local"}}""").omarchyPeer,
        )
        assertNull(LauncherSettings.parse("""{"omarchyPeer":{"port":8753}}""").omarchyPeer)
    }

    @Test
    fun serializationPreservesUnknownRootAndNestedFieldsAndPersistsNullableFields() {
        val parsed = LauncherSettings.parse(
            """{
              "future":{"enabled":true},
              "omarchyControlPos":{"dx":9,"dy":10,"anchor":"top"},
              "omarchyPeer":{"ip":"host","unknownPeer":42}
            }""",
        )

        val json = parsed.toJson()

        assertTrue(json.getJSONObject("future").getBoolean("enabled"))
        assertEquals("top", json.getJSONObject("omarchyControlPos").getString("anchor"))
        assertEquals(42, json.getJSONObject("omarchyPeer").getInt("unknownPeer"))
        assertEquals(8753, json.getJSONObject("omarchyPeer").getInt("port"))
        assertTrue(json.has("favoritesBarMode"))
        assertTrue(json.isNull("favoritesBarMode"))
        assertEquals(0.86, json.getDouble("settingsPanelOpacity"), 0.0)
        assertTrue(JSONObject(LauncherSettings.parse("{}").toJson().toString()).isNull("omarchyPeer"))
    }
}
