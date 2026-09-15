package cl.villagranquiroz.ohm_launcher

/** Coordinates live launcher-settings preview, commit, and rollback. */
class LauncherSettingsEditorSession(
    private val original: LauncherSettings,
    private val onPreview: (LauncherSettings) -> Unit,
) {
    private var current = original
    private var committed = false

    fun update(value: LauncherSettings) {
        if (value == current) return
        current = value
        onPreview(value)
    }

    fun commit(onSave: (LauncherSettings) -> Unit) {
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
