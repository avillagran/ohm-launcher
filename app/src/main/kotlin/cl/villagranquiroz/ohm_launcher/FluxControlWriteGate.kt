package cl.villagranquiroz.ohm_launcher

/** A queued action must recheck its authority after acquiring the control socket serializer. */
internal object FluxControlWriteGate {
    fun write(serializer: Any, authorized: () -> Boolean, send: () -> Unit) {
        synchronized(serializer) {
            check(authorized()) { "Flux control session is no longer authorized" }
            send()
        }
    }
}
