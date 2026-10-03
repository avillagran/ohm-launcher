package cl.villagranquiroz.ohm_launcher

/** Flux and Omarchy Link have separate identities, pairing, and capabilities. */

/** Never infer Flux capability from Omarchy Link's HTTP routes. */
internal enum class PeerTransport { OMARCHY_LINK, FLUX }
internal enum class PeerAction { SEND_TEXT, PING, SEND_CLIPBOARD, SHARE_URL, REPORT_BATTERY, SEND_NOTIFICATION, RECEIVE_THEME_CATALOG, THEME_SYNC, THEME_SELECT, BACKGROUND_SELECT, SCREEN_SHARE, CLIPBOARD_MONITOR }
internal object PeerActionPolicy {
    fun supports(transport: PeerTransport, action: PeerAction, playStore: Boolean): Boolean = when (transport) {
        PeerTransport.OMARCHY_LINK -> when (action) {
            PeerAction.THEME_SYNC, PeerAction.THEME_SELECT, PeerAction.BACKGROUND_SELECT -> true
            PeerAction.SEND_TEXT, PeerAction.SCREEN_SHARE, PeerAction.CLIPBOARD_MONITOR -> !playStore
            PeerAction.RECEIVE_THEME_CATALOG -> false
            PeerAction.PING, PeerAction.SEND_CLIPBOARD, PeerAction.SHARE_URL, PeerAction.REPORT_BATTERY, PeerAction.SEND_NOTIFICATION -> false
        }
        PeerTransport.FLUX -> !playStore && action in setOf(PeerAction.SEND_TEXT, PeerAction.PING,
            PeerAction.SEND_CLIPBOARD, PeerAction.SHARE_URL, PeerAction.REPORT_BATTERY, PeerAction.SEND_NOTIFICATION,
            PeerAction.RECEIVE_THEME_CATALOG)
    }
}
