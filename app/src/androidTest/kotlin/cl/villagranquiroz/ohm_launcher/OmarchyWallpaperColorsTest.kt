package cl.villagranquiroz.ohm_launcher

import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation coverage for the WallpaperColors adapter.
 *
 * On every API 27+ device the public three-color WallpaperColors constructor
 * is used (it has been public since API 27; only the four-color hints variant
 * is API 31+), so all three roles must round-trip as exact opaque ARGB. On
 * API < 27 the entry point must return null and the framework class must
 * never be loaded outside the guarded branch.
 */
@RunWith(AndroidJUnit4::class)
class OmarchyWallpaperColorsTest {

    private val theme = OmarchySystemThemeColors(
        primary = 0xFF102030.toInt(),
        secondary = 0xFF405060.toInt(),
        tertiary = 0xFF708090.toInt(),
        background = 0xFF1A1B26.toInt(),
        darkTheme = true,
    )

    @Test
    fun fromThemeMapsOpaqueArgbRoles() {
        val wallpaperColors = OmarchyWallpaperColors.fromTheme(theme)
        if (Build.VERSION.SDK_INT < OmarchyWallpaperColors.WALLPAPER_COLORS_MIN_SDK) {
            assertNull(wallpaperColors)
            return
        }
        assertNotNull(wallpaperColors)
        assertEquals(0xFF, Color.alpha(wallpaperColors!!.primaryColor.toArgb()))
        assertEquals(theme.primary, wallpaperColors.primaryColor.toArgb())
        assertEquals(theme.secondary, wallpaperColors.secondaryColor!!.toArgb())
        assertEquals(theme.tertiary, wallpaperColors.tertiaryColor!!.toArgb())
    }

    @Test
    fun builderIsIsolatedBehindTheSdkGate() {
        // The adapter object must load and expose the gate on every API level;
        // on 27+ the guarded branch must produce a real WallpaperColors value.
        assertTrue(OmarchyWallpaperColors.WALLPAPER_COLORS_MIN_SDK >= 27)
        if (Build.VERSION.SDK_INT >= OmarchyWallpaperColors.WALLPAPER_COLORS_MIN_SDK) {
            val built = OmarchyWallpaperColors.fromTheme(theme)
            assertNotNull(built)
            assertEquals(theme.primary, built!!.primaryColor.toArgb())
        }
    }
}
