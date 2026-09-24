package cl.villagranquiroz.ohm_launcher

import androidx.core.graphics.toColorInt

/** Persist a palette role rather than a color, so changing themes keeps the selection. */
object OmarchyWidgetTextColorPolicy {
    fun normalize(value: String?, palette: OmarchyThemePalette?): String? =
        value?.takeIf { it in palette?.colors.orEmpty() }
}

/** Shared color source for launcher chrome, floating tools, and dialog windows. */
object OmarchyUiTheme {
    private var palette: OmarchyThemePalette? = null
    private var widgetTextRole: String? = null

    val currentPalette: OmarchyThemePalette? get() = palette

    fun apply(settings: LauncherSettings) {
        palette = OmarchyThemePalette.fromSettings(settings.raw)
        widgetTextRole = OmarchyWidgetTextColorPolicy.normalize(settings.widgetTextColorRole, palette)
    }

    fun widgetTextColor(fallback: Int): Int =
        widgetTextRole?.let { role -> palette?.color(role)?.let { runCatching { it.toColorInt() }.getOrNull() } }
            ?: fallback

    fun color(role: String, fallback: Int): Int {
        return palette?.color(role)?.let { runCatching { it.toColorInt() }.getOrNull() } ?: fallback
    }
}