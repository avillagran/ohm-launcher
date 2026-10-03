package cl.villagranquiroz.ohm_launcher

/** Explicit selection dispatch over one current paired session; catalog receipt is never an action. */
internal class FluxThemeSelectionChannel<S : Any>(
    directEdition: Boolean,
    private val current: (String) -> S?,
    private val paired: () -> Boolean,
    private val open: () -> Boolean,
    private val capable: () -> Boolean,
    private val consent: () -> Boolean,
    private val installed: () -> Set<String>,
    private val write: (String) -> Unit,
    private val result: (FluxThemeSelection.Result) -> Unit,
) {
    private val selection = FluxThemeSelection<S>(directEdition)

    fun bind(peer: String, session: S) = selection.bind(peer, session, paired())
    fun revoke(peer: String, session: S) = selection.revoke(peer, session)
    fun cancel(peer: String, session: S) = selection.cancel(peer, session)

    fun request(peer: String, session: S, id: String, requestId: String, packetId: Long): Boolean {
        if (!available(peer, session)) return false
        val frame = selection.begin(peer, session, paired(), installed(), id, requestId, packetId)
            ?: return false
        try {
            // Revalidate directly at the write boundary; a replacement can occur while queued.
            if (!available(peer, session)) { selection.cancel(peer, session); return false }
            write(frame)
            return true
        } catch (failure: Exception) {
            selection.cancel(peer, session)
            throw failure
        }
    }

    fun receive(peer: String, session: S, frame: String): Boolean {
        if (!available(peer, session)) return false
        val accepted = selection.accept(peer, session, paired(), installed(), frame) ?: return false
        if (!available(peer, session)) return false
        result(accepted)
        return true
    }

    private fun available(peer: String, session: S): Boolean =
        current(peer) === session && paired() && open() && capable() && consent()
}
