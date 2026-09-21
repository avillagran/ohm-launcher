package cl.villagranquiroz.ohm_launcher

/**
 * Pure Omarchy-to-Android color mapping. Framework-free so it can be unit
 * tested on the JVM. All color values are opaque ARGB ints (alpha stripped)
 * because Android wallpaper color roles only accept opaque colors.
 */
data class OmarchySystemThemeColors(
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
    val background: Int,
    val darkTheme: Boolean,
) {
    val fingerprint: String
        get() = listOf(primary, secondary, tertiary, background, darkTheme).joinToString(":")
}

object OmarchySystemTheme {
    private val fallbackAccent = 0xFF66E0FF.toInt()
    private val fallbackBackground = 0xFF1A1B26.toInt()

    fun fromPalette(palette: OmarchyThemePalette?): OmarchySystemThemeColors {
        val colors = palette?.colors ?: emptyMap()
        fun color(vararg roles: String): Int? {
            for (role in roles) {
                val parsed = parseColor(colors[role.lowercase()])
                if (parsed != null) return parsed
            }
            return null
        }

        val primary = color("accent", "foreground") ?: fallbackAccent
        val secondary = color("lighter_background", "bright_foreground", "foreground") ?: primary
        val tertiary = color("muted", "dark_background", "background") ?: secondary
        val background = color("background", "dark_background") ?: fallbackBackground

        return OmarchySystemThemeColors(
            primary = primary,
            secondary = secondary,
            tertiary = tertiary,
            background = background,
            darkTheme = palette?.mode != OmarchyThemeMode.LIGHT,
        )
    }

    /**
     * Parses #RGB, #ARGB, #RRGGBB, #AARRGGBB into an opaque ARGB int.
     * Returns null for anything else; never throws.
     */
    internal fun parseColor(raw: String?): Int? {
        val value = raw?.trim().orEmpty()
        if (!value.startsWith("#")) return null
        val hex = value.substring(1)
        return when (hex.length) {
            3, 4 -> {
                if (!hex.all(::isHexDigitChar)) return null
                val offset = if (hex.length == 4) 1 else 0
                fun channel(index: Int): Int {
                    val digit = hex[index + offset].digitToInt(16)
                    return digit shl 4 or digit
                }
                0xFF000000.toInt() or
                    (channel(0) shl 16) or
                    (channel(1) shl 8) or
                    channel(2)
            }
            6, 8 -> {
                val rgbHex = if (hex.length == 8) hex.substring(2) else hex
                if (!rgbHex.all(::isHexDigitChar)) return null
                0xFF000000.toInt() or rgbHex.toInt(16)
            }
            else -> null
        }
    }

    private fun isHexDigitChar(char: Char): Boolean =
        char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
}
