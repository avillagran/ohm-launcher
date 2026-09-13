package cl.villagranquiroz.ohm_launcher

/** Platform-neutral mDNS payload advertised by [AndroidNsdRegistrar]. */
data class OhmServiceAdvertisement(
    val name: String,
    val type: String,
    val port: Int,
    val txt: Map<String, ByteArray>,
) {
    companion object {
        const val SERVICE_TYPE = "_ohm._tcp"

        fun create(config: OhmDiscoveryConfig): OhmServiceAdvertisement = OhmServiceAdvertisement(
            name = config.serviceName,
            type = SERVICE_TYPE,
            port = config.apiPort,
            txt = mapOf(
                "ohm" to "1".toByteArray(Charsets.UTF_8),
                "port" to config.apiPort.toString().toByteArray(Charsets.UTF_8),
            ),
        )
    }
}
