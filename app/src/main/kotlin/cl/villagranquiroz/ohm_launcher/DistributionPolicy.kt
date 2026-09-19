package cl.villagranquiroz.ohm_launcher

/** Capabilities permitted by the selected distribution channel. */
data class DistributionPolicy(
    val playStore: Boolean,
) {
    val allowLanIntegration: Boolean get() = !playStore
    val allowAllFilesAccess: Boolean get() = !playStore
    val allowAccessibilityControl: Boolean get() = !playStore
    val allowNotificationAccess: Boolean get() = !playStore
    val allowCompactSystemNavigation: Boolean get() = !playStore
}
