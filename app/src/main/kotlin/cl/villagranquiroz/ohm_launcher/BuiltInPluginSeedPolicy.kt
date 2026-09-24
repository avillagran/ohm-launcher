package cl.villagranquiroz.ohm_launcher

import java.security.MessageDigest

/** Protects user-edited plugin files while allowing unchanged bundled assets to upgrade. */
internal object BuiltInPluginSeedPolicy {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    fun shouldReplace(
        existingHash: String?,
        previousSeedHash: String?,
        bundledHash: String,
        knownLegacyHashes: Set<String>,
    ): Boolean =
        existingHash == null ||
            (existingHash != bundledHash && (
                (previousSeedHash != null && existingHash == previousSeedHash) ||
                    existingHash in knownLegacyHashes
                ))
}
