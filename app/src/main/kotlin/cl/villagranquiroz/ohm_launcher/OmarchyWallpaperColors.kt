package cl.villagranquiroz.ohm_launcher

import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Adapter exposing Omarchy system colors to Android as [WallpaperColors].
 *
 * [WallpaperColors] only exists from API 27, so all construction is kept
 * behind [WALLPAPER_COLORS_MIN_SDK] and the public entry point returns null
 * below it. The class is framework-only and holds no [WallpaperColors] in
 * field or static state, so on API 24-26 ART never loads it outside the
 * guarded branch; a direct [WallpaperColors] return type in the method
 * signature is safe because ART resolves types lazily per method.
 *
 * Role fidelity by API level (public framework surface only, no hidden APIs
 * or reflection):
 * - API 31+: the three-color [WallpaperColors] constructor is public and the
 *   opaque Omarchy ARGB values are fed straight in, preserving exact roles.
 * - API 27-30: only [WallpaperColors.fromBitmap] is public for multi-color
 *   input; a 3x1 bitmap carrying primary/secondary/tertiary is used. The
 *   framework quantizes bitmap colors, so roles are approximate there.
 */
object OmarchyWallpaperColors {
    const val WALLPAPER_COLORS_MIN_SDK = 27
    private const val EXACT_COLOR_CONSTRUCTOR_MIN_SDK = 31

    /**
     * Returns Omarchy colors as [WallpaperColors], or null on API < 27 where
     * the framework class does not exist.
     */
    fun fromTheme(colors: OmarchySystemThemeColors): WallpaperColors? {
        if (Build.VERSION.SDK_INT < WALLPAPER_COLORS_MIN_SDK) return null
        return if (Build.VERSION.SDK_INT >= EXACT_COLOR_CONSTRUCTOR_MIN_SDK) {
            buildExact(colors)
        } else {
            buildFromBitmap(colors)
        }
    }

    @RequiresApi(31)
    private fun buildExact(colors: OmarchySystemThemeColors): WallpaperColors {
        fun opaque(argb: Int): Color = Color.valueOf(Color.argb(0xFF, Color.red(argb), Color.green(argb), Color.blue(argb)))
        return WallpaperColors(
            opaque(colors.primary),
            opaque(colors.secondary),
            opaque(colors.tertiary),
        )
    }

    @RequiresApi(27)
    private fun buildFromBitmap(colors: OmarchySystemThemeColors): WallpaperColors {
        fun opaque(argb: Int): Int = Color.argb(0xFF, Color.red(argb), Color.green(argb), Color.blue(argb))
        val strip = Bitmap.createBitmap(3, 1, Bitmap.Config.ARGB_8888)
        try {
            strip.setPixel(0, 0, opaque(colors.primary))
            strip.setPixel(1, 0, opaque(colors.secondary))
            strip.setPixel(2, 0, opaque(colors.tertiary))
            return WallpaperColors.fromBitmap(strip)
        } finally {
            strip.recycle()
        }
    }
}
