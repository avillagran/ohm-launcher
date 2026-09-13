package cl.villagranquiroz.ohm_launcher

/** Coordinates lossless live preview, commit, and rollback for the TTFX editor. */
class TtfxEditorSession(
    private val original: TtfxConfig,
    private val onPreview: (TtfxConfig) -> Unit,
) {
    private var current = original
    private var committed = false

    fun update(value: TtfxConfig) {
        if (value == current) return
        current = value
        onPreview(value)
    }

    fun commit(onSave: (TtfxConfig) -> Unit) {
        committed = true
        onSave(current)
    }

    fun cancel() {
        if (!committed && current != original) {
            current = original
            onPreview(original)
        }
    }
}
