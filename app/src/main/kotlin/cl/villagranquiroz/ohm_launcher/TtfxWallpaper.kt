package cl.villagranquiroz.ohm_launcher

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Helpers to enable the TTFX live wallpaper across stock Android and MIUI,
 *  which lacks the ACTION_CHANGE_LIVE_WALLPAPER activity. */
object TtfxWallpaper {
    private const val EXTRA_LIVE_WALLPAPER_COMPONENT =
        "android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT"

    fun component(context: Context): ComponentName =
        ComponentName(context, TtfxWallpaperService::class.java)

    /** Direct set via the hidden (but reachable) WallpaperManager API; false if blocked. */
    fun setDirectly(context: Context): Boolean = try {
        val manager = WallpaperManager.getInstance(context)
        WallpaperManager::class.java
            .getMethod("setWallpaperComponent", ComponentName::class.java)
            .invoke(manager, component(context))
        true
    } catch (throwable: Throwable) {
        android.util.Log.w("OhmLauncher", "setWallpaperComponent directo no disponible", throwable)
        false
    }

    /** Opens the live wallpaper preview with a Set button (AOSP live picker on MIUI). */
    fun showPicker(context: Context): Boolean = runCatching {
        context.startActivity(pickerIntent(context))
        true
    }.getOrDefault(false)

    fun pickerIntent(context: Context): Intent {
        val component = component(context)
        val explicit = Intent()
            .setComponent(
                ComponentName("com.android.wallpaper.livepicker", "com.android.wallpaper.livepicker.LiveWallpaperActivity"),
            )
            .putExtra(EXTRA_LIVE_WALLPAPER_COMPONENT, component)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        val resolved = context.packageManager.resolveActivity(explicit, 0) != null
        return if (resolved) {
            explicit
        } else {
            Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
        }
    }

    fun enable(context: Context): Boolean = setDirectly(context) || showPicker(context)
}
