package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject

/** Resolves the wallpaper's TTFX config from the launcher config document:
 *  mirrors desktop 0, forces audio off (a wallpaper must not touch the mic). */
internal object TtfxWallpaperConfig {
    fun resolve(configJson: String): TtfxConfig =
        (
            runCatching { LauncherConfig.parse(configJson).desktops.firstOrNull()?.ttfx }
                .getOrNull() ?: TtfxConfig.parse(JSONObject())
            )
            .copy(audio = false)
}
