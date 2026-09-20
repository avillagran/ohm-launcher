package cl.villagranquiroz.ohm_launcher

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemWallpaperSyncTest {
    @Test
    fun identifiesVideoBackgroundsCaseInsensitively() {
        assertTrue(SystemWallpaperSync.isVideo(File("theme.MP4")))
        assertTrue(SystemWallpaperSync.isVideo(File("theme.webm")))
        assertFalse(SystemWallpaperSync.isVideo(File("theme.png")))
        assertFalse(SystemWallpaperSync.isVideo(File("theme")))
    }

    @Test
    fun createsStableKeysForColorBackgrounds() {
        assertTrue(SystemWallpaperSync.desiredKey("", "#AABBCC") == "color:#aabbcc")
    }

    @Test
    fun recordsColorKeyWhenImageApplicationFallsBackToColor() {
        assertTrue(
            SystemWallpaperSync.appliedKey(
                backgroundImage = "/missing/wallpaper.webp",
                fallbackColor = "#AABBCC",
                imageApplied = false,
            ) == "color:#aabbcc",
        )
    }
}
