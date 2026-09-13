package cl.villagranquiroz.ohm_launcher.qml

internal object QmlTextTypefacePolicy {
    fun requiresIconFont(text: String): Boolean = text.codePoints().anyMatch { codePoint ->
        codePoint in 0xE000..0xF8FF ||
            codePoint in 0xF0000..0xFFFFD ||
            codePoint in 0x100000..0x10FFFD
    }
}
