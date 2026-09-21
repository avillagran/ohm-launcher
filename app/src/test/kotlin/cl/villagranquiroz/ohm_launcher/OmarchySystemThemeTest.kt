package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OmarchySystemThemeTest {

    private val fallbackAccent = 0xFF66E0FF.toInt()
    private val fallbackBackground = 0xFF1A1B26.toInt()

    private fun palette(
        mode: OmarchyThemeMode = OmarchyThemeMode.DARK,
        colors: Map<String, String>,
    ) = OmarchyThemePalette(name = "test", mode = mode, colors = colors)

    private val tokyoNightColors = mapOf(
        "background" to "#1a1b26",
        "foreground" to "#c0caf5",
        "accent" to "#7aa2f7",
        "lighter_background" to "#24283b",
        "bright_foreground" to "#a9b1d6",
        "dark_background" to "#16161e",
        "muted" to "#565f89",
    )

    @Test
    fun `tokyo night roles map to android colors`() {
        val colors = OmarchySystemTheme.fromPalette(palette(colors = tokyoNightColors))

        assertEquals(0xFF7AA2F7.toInt(), colors.primary)
        assertEquals(0xFF24283B.toInt(), colors.secondary)
        assertEquals(0xFF565F89.toInt(), colors.tertiary)
        assertEquals(0xFF1A1B26.toInt(), colors.background)
        assertEquals(true, colors.darkTheme)
    }

    @Test
    fun `light theme reports darkTheme false`() {
        val colors = OmarchySystemTheme.fromPalette(
            palette(mode = OmarchyThemeMode.LIGHT, colors = tokyoNightColors),
        )

        assertEquals(false, colors.darkTheme)
    }

    @Test
    fun `null palette resolves to stable fallbacks and dark theme`() {
        val colors = OmarchySystemTheme.fromPalette(null)

        assertEquals(fallbackAccent, colors.primary)
        assertEquals(fallbackAccent, colors.secondary)
        assertEquals(fallbackAccent, colors.tertiary)
        assertEquals(fallbackBackground, colors.background)
        assertEquals(true, colors.darkTheme)
    }

    @Test
    fun `missing optional roles fall back deterministically`() {
        val colors = OmarchySystemTheme.fromPalette(
            palette(
                colors = mapOf(
                    "accent" to "#112233",
                ),
            ),
        )

        assertEquals(0xFF112233.toInt(), colors.primary)
        // secondary: no lighter_background/bright_foreground/foreground -> resolved primary
        assertEquals(0xFF112233.toInt(), colors.secondary)
        // tertiary: no muted/dark_background/background -> resolved secondary
        assertEquals(0xFF112233.toInt(), colors.tertiary)
        assertEquals(fallbackBackground, colors.background)
    }

    @Test
    fun `invalid values are skipped without throwing`() {
        val colors = OmarchySystemTheme.fromPalette(
            palette(
                colors = mapOf(
                    "accent" to "not-a-color",
                    "foreground" to "#abcdef",
                    "background" to "",
                ),
            ),
        )

        assertEquals(0xFFABCDEF.toInt(), colors.primary)
        assertEquals(fallbackBackground, colors.background)
    }

    @Test
    fun `three digit hex expands to opaque argb`() {
        val colors = OmarchySystemTheme.fromPalette(
            palette(colors = mapOf("accent" to "#0f0")),
        )

        assertEquals(0xFF00FF00.toInt(), colors.primary)
    }

    @Test
    fun `four digit hex expands and normalizes to opaque argb`() {
        val colors = OmarchySystemTheme.fromPalette(
            palette(colors = mapOf("accent" to "#8f00")),
        )

        assertEquals(0xFFFF0000.toInt(), colors.primary)
    }

    @Test
    fun `alpha is stripped from wallpaper color roles`() {
        val colors = OmarchySystemTheme.fromPalette(
            palette(
                colors = mapOf(
                    "accent" to "#807aa2f7",
                    "background" to "#001a1b26",
                ),
            ),
        )

        assertEquals(0xFF7AA2F7.toInt(), colors.primary)
        assertEquals(0xFF1A1B26.toInt(), colors.background)
    }

    @Test
    fun `equal colors are equal and share fingerprint`() {
        val first = OmarchySystemTheme.fromPalette(palette(colors = tokyoNightColors))
        val second = OmarchySystemTheme.fromPalette(palette(colors = tokyoNightColors))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `different palettes produce different fingerprints`() {
        val dark = OmarchySystemTheme.fromPalette(palette(colors = tokyoNightColors))
        val light = OmarchySystemTheme.fromPalette(
            palette(mode = OmarchyThemeMode.LIGHT, colors = tokyoNightColors),
        )

        assertNotEquals(dark, light)
        assertNotEquals(dark.fingerprint, light.fingerprint)
    }

    @Test
    fun `fingerprint joins all fields`() {
        val colors = OmarchySystemThemeColors(
            primary = 1,
            secondary = 2,
            tertiary = 3,
            background = 4,
            darkTheme = true,
        )

        assertEquals("1:2:3:4:true", colors.fingerprint)
    }
}
