package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmarchyThemeTest {
    @Test
    fun parsesOfficialOmarchyPaletteAndIgnoresInvalidColors() {
        val palette = OmarchyThemePalette.parse(
            JSONObject(
                """{
                  "name":"Tokyo Night","mode":"dark","source":"omarchy",
                  "colors":{
                    "accent":"#7aa2f7","background":"#1a1b26",
                    "dark_background":"#16161e","lighter_background":"#24283b",
                    "foreground":"#c0caf5","muted":"#565f89","red":"#f7768e",
                    "invalid":"not-a-color"
                  }
                }""",
            ),
        )

        assertEquals("Tokyo Night", palette.name)
        assertEquals(OmarchyThemeMode.DARK, palette.mode)
        assertEquals("#7aa2f7", palette.color("accent"))
        assertEquals("#1a1b26", palette.color("background"))
        assertFalse(palette.colors.containsKey("invalid"))
        assertFalse(palette.useDarkSystemIcons)
    }

    @Test
    fun lightThemeRequestsDarkSystemIconsAndRoundTripsItsFullPalette() {
        val source = JSONObject(
            """{"name":"White","mode":"light","colors":{"accent":"#111111","background":"#ffffff","foreground":"#222222","custom_role":"#abcdef"}}""",
        )
        val palette = OmarchyThemePalette.parse(source)

        assertTrue(palette.useDarkSystemIcons)
        assertEquals("#abcdef", palette.toJson().getJSONObject("colors").getString("custom_role"))
        assertEquals(palette, OmarchyThemePalette.parse(palette.toJson()))
    }

    @Test
    fun readsThemeFromLauncherSettingsWithoutConfusingLegacyFields() {
        val settings = JSONObject()
            .put("accent", "legacy")
            .put("omarchyTheme", JSONObject("""{"name":"Nord","mode":"dark","colors":{"accent":"#81a1c1"}}"""))

        assertEquals("Nord", OmarchyThemePalette.fromSettings(settings)?.name)
        assertEquals(null, OmarchyThemePalette.fromSettings(JSONObject().put("accent", "legacy")))
    }

    @Test
    fun tintsTerminalCellsWithThemeAccentWhilePreservingBrightness() {
        val accent = 0xFF7AA2F7.toInt()
        assertEquals(accent, OmarchyThemeColor.tint(0xFFFFFFFF.toInt(), accent))
        assertEquals(0xFF3D517C.toInt(), OmarchyThemeColor.tint(0xFF808080.toInt(), accent))
        assertEquals(0x003D517C, OmarchyThemeColor.tint(0x00808080, accent))
    }

    @Test
    fun animatesOnlyRealThemeChangesOnALaidOutLauncher() {
        val nord = OmarchyThemePalette.parse(
            JSONObject("""{"name":"Nord","colors":{"accent":"#81a1c1"}}"""),
        )
        assertTrue(OmarchyThemeTransitionPolicy.shouldAnimate(null, nord, 1080, 2400))
        assertFalse(OmarchyThemeTransitionPolicy.shouldAnimate(nord, nord, 1080, 2400))
        assertFalse(OmarchyThemeTransitionPolicy.shouldAnimate(null, nord, 0, 2400))
    }
}
