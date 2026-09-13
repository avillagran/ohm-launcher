package cl.villagranquiroz.ohm_launcher

/** BLE advertisement details exposed without leaking Android scanner types. */
data class OhmBlePeer(
    val address: String,
    val name: String,
    val rssi: Int,
)

/** Applies the Flutter discovery name contract and removes repeated scan callbacks. */
class OmarchyBlePeerCollector {
    private val found = linkedMapOf<String, OhmBlePeer>()

    @Synchronized
    fun record(peer: OhmBlePeer): Boolean {
        if (!isOmarchyPeerName(peer.name) || peer.address.isBlank()) return false
        found[peer.address] = peer
        return true
    }

    @Synchronized
    fun peers(): List<OhmBlePeer> = found.values.toList()

    @Synchronized
    fun clear() = found.clear()

    companion object {
        fun isOmarchyPeerName(name: String): Boolean {
            val normalized = name.lowercase()
            return "omarchy" in normalized || "ohm" in normalized
        }
    }
}
