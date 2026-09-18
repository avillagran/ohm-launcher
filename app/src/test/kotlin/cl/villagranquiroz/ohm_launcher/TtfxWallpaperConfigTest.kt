package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtfxWallpaperConfigTest {
    @Test
    fun mirrorsDesktopZeroAndForcesAudioOff() {
        val config = TtfxWallpaperConfig.resolve(ConfigStorage.DEFAULT_CONFIG)

        assertEquals(LauncherConfig.parse(ConfigStorage.DEFAULT_CONFIG).desktops[0].ttfx.text, config.text)
        assertEquals(LauncherConfig.parse(ConfigStorage.DEFAULT_CONFIG).desktops[0].ttfx.effect, config.effect)
        assertFalse(config.audio)
    }

    @Test
    fun fallsBackToDefaultsWithAudioOffForInvalidJson() {
        val config = TtfxWallpaperConfig.resolve("{not json")

        assertTrue(config.enabled)
        assertFalse(config.audio)
    }
}
