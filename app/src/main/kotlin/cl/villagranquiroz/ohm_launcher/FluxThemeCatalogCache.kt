package cl.villagranquiroz.ohm_launcher

import java.util.concurrent.ConcurrentHashMap

/** Ephemeral Flux-only catalogs. Session identity prevents stale reconnect callbacks crossing peers. */
internal class FluxThemeCatalogCache<Session : Any> {
    private val entries = ConcurrentHashMap<String, Pair<Session, FluxThemeCatalog>>()

    fun put(id: String, session: Session, catalog: FluxThemeCatalog) {
        entries[id] = session to catalog
    }

    fun get(id: String, session: Session): FluxThemeCatalog? =
        entries[id]?.takeIf { it.first === session }?.second

    fun remove(id: String, session: Session) {
        entries.computeIfPresent(id) { _, entry -> entry.takeUnless { it.first === session } }
    }

    fun clear() = entries.clear()
}