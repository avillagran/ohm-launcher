package cl.villagranquiroz.ohm_launcher

/** Read at UI delivery, not before posting: a queued snapshot can predate a later write. */
internal class SettingsUiDelivery(
    private val postToUi: (() -> Unit) -> Unit,
    private val readLatest: () -> LauncherSettings,
) {
    fun post(eligible: () -> Boolean, apply: (LauncherSettings) -> Unit) {
        postToUi {
            if (!eligible()) return@postToUi
            val current = runCatching(readLatest).getOrNull() ?: return@postToUi
            if (eligible()) apply(current)
        }
    }
}
