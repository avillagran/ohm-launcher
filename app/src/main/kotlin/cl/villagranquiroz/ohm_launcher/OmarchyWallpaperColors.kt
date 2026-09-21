package cl.villagranquiroz.ohm_launcher

import android.app.WallpaperColors
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
 * Role fidelity (public framework surface only, no hidden APIs or
 * reflection): the three-color [WallpaperColors] constructor has been public
 * since API 27 and stores the exact opaque ARGB values fed into it,
 * preserving roles on every supported level. (Only the four-color variant
 * taking explicit hint flags is gated to API 31, and it is not needed here.)
 */
object OmarchyWallpaperColors {
    const val WALLPAPER_COLORS_MIN_SDK = 27

    /**
     * Returns Omarchy colors as [WallpaperColors], or null on API < 27 where
     * the framework class does not exist.
     */
    fun fromTheme(colors: OmarchySystemThemeColors): WallpaperColors? {
        if (Build.VERSION.SDK_INT < WALLPAPER_COLORS_MIN_SDK) return null
        return buildExact(colors)
    }

    @RequiresApi(27)
    private fun buildExact(colors: OmarchySystemThemeColors): WallpaperColors {
        fun opaque(argb: Int): Color = Color.valueOf(Color.argb(0xFF, Color.red(argb), Color.green(argb), Color.blue(argb)))
        return WallpaperColors(
            opaque(colors.primary),
            opaque(colors.secondary),
            opaque(colors.tertiary),
        )
    }
}
