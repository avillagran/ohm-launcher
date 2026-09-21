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
 * API 31+ devices must observe the exact ARGB role mapping through the public
 * three-color constructor; API 27-30 devices exercise the bitmap path
 * (quantized roles, primary still reported); every device exercises the
 * public entry point so class-loading of android.app.WallpaperColors via the
 * method signature is proven safe on the running API level.
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
        if (Build.VERSION.SDK_INT >= 31) {
            assertEquals(theme.primary, wallpaperColors.primaryColor.toArgb())
            assertEquals(theme.secondary, wallpaperColors.secondaryColor!!.toArgb())
            assertEquals(theme.tertiary, wallpaperColors.tertiaryColor!!.toArgb())
        }
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
