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
    fun squareThemeForcesEveryRequestedSurfaceRadiusToZero() {
        val palette = OmarchyThemePalette.parse(
            JSONObject("""{"name":"Square","geometry":{"cornerRadius":0},"colors":{"accent":"#ffffff"}}"""),
        )

        assertTrue(palette.hasSquareCorners)
        assertEquals(0f, OmarchyThemeShapePolicy.surfaceRadius(24f, palette), 0f)
        assertEquals(palette, OmarchyThemePalette.parse(palette.toJson()))
    }

    @Test
    fun roundedThemeCapsSurfaceRadiusToCanonicalThemeRadius() {
        val palette = OmarchyThemePalette.parse(
            JSONObject("""{"name":"Rounded","geometry":{"cornerRadius":7},"colors":{"accent":"#ffffff"}}"""),
        )

        assertFalse(palette.hasSquareCorners)
        assertEquals(7f, OmarchyThemeShapePolicy.surfaceRadius(24f, palette), 0f)
        assertEquals(4f, OmarchyThemeShapePolicy.surfaceRadius(4f, palette), 0f)
    }

    @Test
    fun roundTripsCanonicalOmarchyBackgroundMetadata() {
        val palette = OmarchyThemePalette.parse(
            JSONObject(
                """{"name":"Nord","colors":{"accent":"#81a1c1"},"background":{"name":"lake.mp4","mime":"video/mp4","sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}}""",
            ),
        )

        assertEquals("lake.mp4", palette.background?.name)
        assertEquals("video/mp4", palette.background?.mime)
        assertEquals(palette, OmarchyThemePalette.parse(palette.toJson()))
    }

    @Test
    fun parsesAndRoundTripsAudioDesktopBackgroundTtfxSettings() {
        val palette = OmarchyThemePalette.parse(
            JSONObject(
                """{
                  "name":"Audio","colors":{"accent":"#81a1c1"},
                  "desktopBackground":{"type":"audio","path":"bars","ttfx":{"enabled":true,"effect":"matrix","text":"Omarchy","textSize":7,"audio":true,"intensity":4,"speed":1.75,"resolution":3,"reactivity":5}}
                }""",
            ),
        )

        val background = palette.desktopBackground!!
        assertEquals("audio", background.type)
        assertEquals("bars", background.path)
        assertEquals("matrix", background.ttfx?.effect)
        assertEquals(7, background.ttfx?.textSize)
        assertEquals(1.75, background.ttfx?.speed)
        assertEquals(palette, OmarchyThemePalette.parse(palette.toJson()))
    }

    @Test
    fun audioDesktopBackgroundEnablesTtfxButPreservesLocalTextGeometry() {
        val desktop = JSONObject()
            .put("ttfxTextSize", 9)
            .put("ttfxTextX", 0.25)
            .put("ttfxTextY", 0.75)
        val background = OmarchyDesktopBackground.parse(
            JSONObject(
                """{"type":"audio","path":"crumble","ttfx":{"enabled":true,"effect":"matrix","text":"Hello","textSize":6,"audio":false,"intensity":8,"speed":2.5,"resolution":4,"reactivity":3}}""",
            ),
        )!!

        OmarchyDesktopBackgroundApplier.apply(desktop, background, "/tmp/fallback.png")

        assertEquals("/tmp/fallback.png", desktop.getString("backgroundImage"))
        assertTrue(desktop.getBoolean("ttfxBackground"))
        assertEquals("crumble", desktop.getString("ttfxEffect"))
        assertEquals("Hello", desktop.getString("ttfxText"))
        assertEquals(9, desktop.getInt("ttfxTextSize"))
        assertFalse(desktop.getBoolean("ttfxAudio"))
        assertEquals(8, desktop.getInt("ttfxIntensity"))
        assertEquals(2.5, desktop.getDouble("ttfxSpeed"), 0.0)
        assertEquals(4, desktop.getInt("ttfxResolution"))
        assertEquals(3, desktop.getInt("ttfxReactivity"))
        assertEquals(0.25, desktop.getDouble("ttfxTextX"), 0.0)
        assertEquals(0.75, desktop.getDouble("ttfxTextY"), 0.0)
    }

    @Test
    fun imageAndVideoDesktopBackgroundDisableTtfx() {
        listOf("image", "video").forEach { type ->
            val desktop = JSONObject().put("ttfxBackground", true)
            val background = OmarchyDesktopBackground(type, "/desktop/background")

            OmarchyDesktopBackgroundApplier.apply(desktop, background, "/tmp/fallback")

            assertFalse(desktop.getBoolean("ttfxBackground"))
            assertEquals("/tmp/fallback", desktop.getString("backgroundImage"))
        }
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

    @Test
    fun themeBackgroundSelectionResolvesCatalogBackgroundWithLocalPreview() {
        val backgrounds = listOf(
            OmarchyBackgroundChoice("a".repeat(64), "a", "image", "/preview", true, null),
            OmarchyBackgroundChoice("b".repeat(64), "b", "image", "/preview", true, "/local/b.jpg"),
        )

        assertEquals(
            "b".repeat(64),
            OmarchyThemeBackgroundSelection.resolve(backgrounds, "b".repeat(64))?.id,
        )
        assertEquals(
            null,
            OmarchyThemeBackgroundSelection.resolve(backgrounds, "a".repeat(64)),
        )
        assertEquals(null, OmarchyThemeBackgroundSelection.resolve(backgrounds, null))
        assertEquals(null, OmarchyThemeBackgroundSelection.resolve(backgrounds, ""))
        assertEquals(null, OmarchyThemeBackgroundSelection.resolve(emptyList(), "b".repeat(64)))
    }

    @Test
    fun selectedBackgroundBecomesCatalogCurrentForNextPickerOpen() {
        val original = OmarchyBackgroundChoice("a".repeat(64), "Old", "image", "/old.jpg", true, "/old.jpg")
        val selected = OmarchyBackgroundChoice("b".repeat(64), "New", "image", "/new.jpg", true, "/new.jpg")
        val catalog = OmarchyBackgroundCatalog(
            OmarchyBackgroundCurrent(original.id, original.type, original.preview),
            listOf(original, selected),
        )

        val updated = catalog.withCurrent(selected)

        assertEquals(selected.id, updated.current.id)
        assertEquals(selected.type, updated.current.type)
        assertEquals(selected.preview, updated.current.path)
    }

    @Test
    fun usesSecondaryDesktopTextWhenBackgroundIsMostlyPrimary() {
        val primary = 0xFFE68E0D.toInt()
        val nearPrimary = 0xFFDC8612.toInt()
        val unrelated = 0xFF102030.toInt()

        assertTrue(OmarchyDesktopTextPolicy.shouldUseSecondary(IntArray(80) { nearPrimary } + IntArray(20) { unrelated }, primary))
        assertFalse(OmarchyDesktopTextPolicy.shouldUseSecondary(IntArray(10) { nearPrimary } + IntArray(90) { unrelated }, primary))
        assertFalse(OmarchyDesktopTextPolicy.shouldUseSecondary(intArrayOf(), primary))
    }

    @Test
    fun desktopClockAndTtfxPreferTheSecondaryThemeColor() {
        assertEquals(
            "#d8dee9",
            OmarchyDesktopTextPolicy.preferredColor(
                mapOf("accent" to "#81a1c1", "foreground" to "#d8dee9"),
            ),
        )
        assertEquals(
            "#eceff4",
            OmarchyDesktopTextPolicy.preferredColor(
                mapOf("accent" to "#81a1c1", "bright_foreground" to "#eceff4"),
            ),
        )
    }

    @Test
    fun localStyleSelectionAppliesBeforeSchedulingOmarchySync() {
        val events = mutableListOf<String>()

        OmarchyLocalStyleSelection.apply(
            id = "tokyo-night",
            applyLocal = { events += "local" },
            publishSelection = { events += "pending:$it" },
            scheduleRemote = { events += "remote:$it" },
        )

        assertEquals(listOf("local", "pending:tokyo-night", "remote:tokyo-night"), events)
    }

    @Test
    fun localBackgroundPreviewUpdatesEveryDesktopWithoutMutatingTheSource() {
        val source = LauncherConfig.parse(
            """{"future":true,"desktops":[{"backgroundImage":"old-a.jpg","widgets":[]},{"backgroundImage":"old-b.jpg","widgets":[]}]}""",
        )

        val preview = OmarchyLocalBackgroundPreview.apply(source, "/tmp/preview.jpg")

        assertEquals(listOf("/tmp/preview.jpg", "/tmp/preview.jpg"), preview.desktops.map { it.backgroundImage })
        assertEquals(listOf("old-a.jpg", "old-b.jpg"), source.desktops.map { it.backgroundImage })
        assertTrue(preview.raw.getBoolean("future"))
    }
}
