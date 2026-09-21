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

    @Test
    fun recordsFileKeyWhenImageApplicationSucceeds() {
        val file = File.createTempFile("ohm-wallpaper", ".png")
        try {
            assertTrue(SystemWallpaperSync.desiredKey(file.absolutePath, "#AABBCC") == SystemWallpaperSync.fileKey(file))
            assertTrue(
                SystemWallpaperSync.appliedKey(
                    backgroundImage = file.absolutePath,
                    fallbackColor = "#AABBCC",
                    imageApplied = true,
                ) == SystemWallpaperSync.fileKey(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun recordsColorKeyWhenExistingImageFailsToApply() {
        val file = File.createTempFile("ohm-wallpaper", ".png")
        try {
            assertTrue(
                SystemWallpaperSync.appliedKey(
                    backgroundImage = file.absolutePath,
                    fallbackColor = "#AABBCC",
                    imageApplied = false,
                ) == "color:#aabbcc",
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun policyBlocksSyncWhenPreferenceDisabled() {
        assertFalse(SystemWallpaperSyncPolicy.shouldSync(enabled = false, desiredKey = "key:a", storedKey = null))
        assertFalse(SystemWallpaperSyncPolicy.shouldSync(enabled = false, desiredKey = "key:a", storedKey = "key:b"))
    }

    @Test
    fun policyAllowsSyncWhenEnabledAndNoWallpaperRecorded() {
        assertTrue(SystemWallpaperSyncPolicy.shouldSync(enabled = true, desiredKey = "key:a", storedKey = null))
    }

    @Test
    fun policySkipsSyncWhenKeyUnchanged() {
        assertFalse(SystemWallpaperSyncPolicy.shouldSync(enabled = true, desiredKey = "key:a", storedKey = "key:a"))
    }

    @Test
    fun policyAllowsSyncWhenKeyChanged() {
        assertTrue(SystemWallpaperSyncPolicy.shouldSync(enabled = true, desiredKey = "key:a", storedKey = "key:b"))
    }
}
