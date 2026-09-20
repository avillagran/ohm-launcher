package cl.villagranquiroz.ohm_launcher

/** Immutable configuration shared by LAN and Bluetooth discovery components. */
data class OhmDiscoveryConfig(
    val apiPort: Int,
    val serviceName: String = "OhmLauncher",
) {
    init {
        require(apiPort in 1..65535) { "API port must be between 1 and 65535" }
        require(serviceName.isNotBlank()) { "Service name must not be blank" }
    }

    fun fallbackUri(host: String, token: String = ""): String {
        require(
            host.isNotBlank() && host.none {
                it.isISOControl() || it.isWhitespace() || it == '[' || it == ']' ||
                    it == '/' || it == '?' || it == '#' || it == '@'
            },
        ) { "Host is not a valid URI authority host" }
        require(token.length <= 128 && token.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Token is not URL-safe"
        }
        val authority = if (host.contains(':')) "[$host]" else host
        val query = token.takeIf(String::isNotEmpty)?.let { "?token=$it" }.orEmpty()
        return "ohm://$authority:$apiPort$query"
    }
}
