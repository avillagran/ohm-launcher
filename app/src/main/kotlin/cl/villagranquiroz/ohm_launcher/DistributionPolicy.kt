package cl.villagranquiroz.ohm_launcher

/** Capabilities permitted by the selected distribution channel. */
data class DistributionPolicy(
    val playStore: Boolean,
) {
    val allowLanIntegration: Boolean get() = !playStore
    val allowOmarchyPeerConnection: Boolean get() = true
    val allowClipboardSync: Boolean get() = !playStore
    val allowAllFilesAccess: Boolean get() = !playStore
    val allowAccessibilityControl: Boolean get() = !playStore
    val allowNotificationAccess: Boolean get() = !playStore
    val allowCompactSystemNavigation: Boolean get() = true
    val allowCompactRecentsNavigation: Boolean get() = true
    val allowOmarchyBarMode: Boolean get() = true
    val allowPublicSystemThemeIntegration: Boolean get() = true
    val allowPrivilegedSystemThemeIntegration: Boolean get() = !playStore
}
