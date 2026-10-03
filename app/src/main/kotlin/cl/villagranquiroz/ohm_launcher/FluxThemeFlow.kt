package cl.villagranquiroz.ohm_launcher

import java.util.concurrent.atomic.AtomicBoolean

/** Session-owned palette delivery; no state monitor is held across persistence or callbacks. */
internal class FluxThemeFlow<S : Any>(
    private val allowed: (S) -> Boolean,
    private val schedule: (() -> Unit) -> Unit,
    private val apply: (OmarchyThemePalette, () -> Boolean) -> ThemeCommitResult,
    private val select: (S, String) -> Boolean,
    private val onApplied: (S, String, Boolean) -> Unit = { _, _, _ -> },
    private val onCommitted: (S, OmarchyThemePalette, () -> Boolean) -> Unit = { _, _, _ -> },
    private val onSuperseded: (S, String) -> Unit = { _, _ -> },
) {
    private class State<S>(val session: S, var catalog: FluxThemeCatalog,
                           val fingerprint: String, val generation: Long,
                           val palette: OmarchyThemePalette, val requestId: String? = null) {
        val live = AtomicBoolean(true)
        val ackEligible = AtomicBoolean(true)
    }
    private data class Pending<S>(val session: S, var currentChanged: Boolean = false,
                                  var accepted: State<S>? = null)
    private val states = mutableMapOf<String, State<S>>()
    private val pending = mutableMapOf<String, Pending<S>>()
    private var generation = 0L
    @Volatile private var desired: State<S>? = null
    // Revoke never waits on disk; a blocked rename can finish after revoke. The next live
    // desired theme is applied under this same gate after the old filesystem call returns.
    private val applyGate = Any()

    fun receive(peer: String, session: S, catalog: FluxThemeCatalog) {
        if (!allowed(session)) return
        val theme = catalog.themes.firstOrNull { it.id == catalog.current } ?: return
        val fingerprint = catalog.current + ":" + theme.palette.toJson()
        var displaced: Pair<S, String>? = null
        val next = synchronized(this) {
            val old = states[peer]
            if (old?.session === session && old.fingerprint == fingerprint) {
                // Keep the same identity/generation for a data-only catalog refresh.
                old.catalog = catalog
                return
            }
            if (old?.session === session && old.catalog.current != catalog.current) {
                pending[peer]?.takeIf { it.session === session }?.currentChanged = true
            }
            old?.live?.set(false)
            displaced = supersedeDesired()
            State(session, catalog, fingerprint, ++generation, theme.palette).also {
                states[peer] = it
                // A desktop poll can publish the selected theme before its correlated ACK.
                // Catalog refreshes keep that request live; replacing its session revokes it.
                if (pending[peer]?.session !== session) pending.remove(peer)
                desired = it
            }
        }
        displaced?.let { onSuperseded(it.first, it.second) }
        schedule { applyIfCurrent(next) }
    }

    fun revoke(peer: String, session: S) {
        val fallback = synchronized(this) {
            var next: State<S>? = null
            states[peer]?.takeIf { it.session === session }?.let {
                it.live.set(false)
                states.remove(peer)
                if (desired === it) {
                    next = states.values.maxByOrNull(State<S>::generation)
                    desired = next
                }
            }
            if (pending[peer]?.session === session) pending.remove(peer)
            next
        }
        fallback?.takeIf(::authority)?.let { schedule { applyIfCurrent(it) } }
    }

    fun request(peer: String, session: S, catalog: FluxThemeCatalog, id: String,
                wanted: () -> Boolean = { true }): Boolean {
        if (!wanted() || !allowed(session)) return false
        val token = Pending(session)
        synchronized(this) {
            if (states[peer]?.session !== session || states[peer]?.catalog !== catalog ||
                catalog.themes.none { it.id == id } || pending.containsKey(peer)) return false
            pending[peer] = token
        }
        val sent = if (wanted()) runCatching { select(session, id) }.getOrDefault(false) else false
        val stillWanted = wanted()
        return synchronized(this) {
            if (!sent || !stillWanted) pending.remove(peer, token)
            // The correlated ACK can consume this token before the socket write returns.
            // Timeout, replacement and revocation still invalidate its accepted state.
            val confirmed = token.accepted?.let { authority(it) && it.ackEligible.get() } == true
            sent && stillWanted && (pending[peer] === token || confirmed)
        }
    }

    @Synchronized fun timeout(peer: String, session: S) {
        if (pending[peer]?.session === session) pending.remove(peer)
        states[peer]?.takeIf { it.session === session && it.requestId != null }
            ?.ackEligible?.set(false)
    }

    fun ack(peer: String, session: S, result: FluxThemeSelection.Result): Boolean {
        if (!allowed(session)) return false
        var displaced: Pair<S, String>? = null
        val next = synchronized(this) {
            val waiting = pending[peer] ?: return false
            if (waiting.session !== session) return false
            pending.remove(peer)
            val state = states[peer] ?: return false
            if (!result.ok || state.session !== session) return false
            // The transport already matched requestId and the exact live session. Resolve
            // its effective theme against the newest catalog, including refreshed palettes.
            val effective = state.catalog.themes.firstOrNull { it.id == result.current } ?: return false
            if (waiting.currentChanged && state.catalog.current != result.current) {
                // A subsequent desktop selection won before this ACK arrived. Keep its
                // desired palette and finish this request through the superseded UI path.
                displaced = session to result.requestId
                return@synchronized null
            }
            state.live.set(false)
            displaced = supersedeDesired()
            State(session, state.catalog, result.current + ":" + effective.palette.toJson(),
                ++generation, effective.palette, result.requestId).also {
                states[peer] = it
                desired = it
                waiting.accepted = it
            }
        }
        displaced?.let { onSuperseded(it.first, it.second) }
        next?.let { schedule { applyIfCurrent(it) } }
        return true
    }

    /** Called under the state monitor; notify the UI only after releasing it. */
    private fun supersedeDesired(): Pair<S, String>? = desired?.let { previous ->
        previous.requestId?.takeIf { previous.ackEligible.getAndSet(false) }
            ?.let { previous.session to it }
    }

    private fun authority(state: State<S>): Boolean =
        state.live.get() && desired === state && allowed(state.session)

    private fun applyIfCurrent(state: State<S>) {
        var result: ThemeCommitResult? = null
        synchronized(applyGate) {
            if (authority(state)) {
                result = runCatching { apply(state.palette) { authority(state) } }
                    .getOrElse { ThemeCommitResult(ThemeCommitStatus.NOT_COMMITTED, it as? Exception) }
            }
        }
        val outcome = result ?: return
        val current = authority(state)
        if (!current) {
            // Even an indeterminate directory sync can have replaced the pathname. Reconcile
            // the newest still-live catalog without requiring a second network packet.
            if (outcome.status != ThemeCommitStatus.NOT_COMMITTED) {
                desired?.takeIf(::authority)?.let { newest -> schedule { applyIfCurrent(newest) } }
            }
            return
        }
        if (outcome.status == ThemeCommitStatus.COMMITTED && authority(state)) {
            onCommitted(state.session, state.palette) { authority(state) }
        }
        state.requestId?.takeIf { state.ackEligible.get() && authority(state) }?.let { requestId ->
            onApplied(state.session, requestId, outcome.status == ThemeCommitStatus.COMMITTED)
        }
    }
}
