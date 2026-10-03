package cl.villagranquiroz.ohm_launcher

/** Stable identifiers shared by the launcher menu and the discovery/share action menu. */
internal object FluxMenuPolicy {
    fun <Session : Any> replacePeer(
        peers: java.util.concurrent.ConcurrentHashMap<String, Session>, id: String,
        session: Session, onReplaced: (Session) -> Unit,
    ) {
        peers.put(id, session)?.takeIf { it !== session }?.let(onReplaced)
    }

    fun showOrClose(closeAfter: Boolean, onCancelled: () -> Unit, show: () -> Boolean) {
        if (!show() && closeAfter) onCancelled()
    }
    enum class Action { SEND_TEXT, SEND_URL, SEND_CLIPBOARD, PING, BATTERY, FILE, NOTIFICATION, SCREEN_START, SCREEN_STOP, THEME, MEDIA, REMOTE_INPUT, FORGET }
    data class PeerRow(val peerId: String, val grouped: Boolean)

    fun actions(screenRunning: Boolean, hasTheme: Boolean, hasMedia: Boolean = false,
                hasInput: Boolean = false, inputEnabled: Boolean = false,
                canRequestInput: Boolean = false): List<Action> = buildList {
        addAll(listOf(Action.SEND_TEXT, Action.SEND_URL, Action.SEND_CLIPBOARD, Action.PING,
            Action.BATTERY, Action.FILE, Action.NOTIFICATION))
        add(if (screenRunning) Action.SCREEN_STOP else Action.SCREEN_START)
        if (hasTheme) add(Action.THEME)
        if (hasMedia) add(Action.MEDIA)
        if (hasInput && canRequestInput) add(Action.REMOTE_INPUT)
        add(Action.FORGET)
    }

    fun placement(peerIds: List<String>, playStore: Boolean): List<PeerRow> =
        if (playStore) emptyList() else peerIds.distinct().let { ids -> ids.map { PeerRow(it, ids.size > 1) } }

    fun authorized(expectedId: String, currentId: String?, paired: Boolean, open: Boolean,
                   playStore: Boolean, action: Action, hasTheme: Boolean = true,
                   screenRunning: Boolean = false, hasMedia: Boolean = false,
                   hasInput: Boolean = false, inputEnabled: Boolean = false,
                   canRequestInput: Boolean = false): Boolean =
        !playStore && expectedId == currentId && paired && open &&
            (action != Action.THEME || hasTheme) &&
            (action != Action.MEDIA || hasMedia) &&
            (action != Action.REMOTE_INPUT || (hasInput && canRequestInput)) &&
            (action != Action.SCREEN_START || !screenRunning) &&
            (action != Action.SCREEN_STOP || screenRunning)
}

/** Link row dispatches only to its current Link peer, never to a Flux fallback. */
internal object OmarchyLinkTextPolicy {
    fun <Peer : Any> send(peer: Peer?, allowed: Boolean, deliver: (Peer) -> Unit): Boolean {
        if (peer == null || !allowed) return false
        deliver(peer)
        return true
    }
}
