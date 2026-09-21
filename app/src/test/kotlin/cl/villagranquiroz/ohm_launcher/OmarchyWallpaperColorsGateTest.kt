package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM guard test: the pre-27 null path must be decidable from a constant
 * alone, with no android.os.Build access and no framework mocking.
 */
class OmarchyWallpaperColorsGateTest {
    @Test
    fun sdkGateConstantIsFixedAtApi27() {
        assertEquals(27, OmarchyWallpaperColors.WALLPAPER_COLORS_MIN_SDK)
    }
}
