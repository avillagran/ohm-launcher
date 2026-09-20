package cl.villagranquiroz.ohm_launcher

object CompactNavigationPolicy {
    fun shouldRequestPermission(compact: Boolean, serviceAvailable: Boolean): Boolean =
        compact && !serviceAvailable

    fun shouldHideSystemNavigation(compact: Boolean, serviceConnected: Boolean): Boolean =
        compact && serviceConnected
}