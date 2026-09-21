package cl.villagranquiroz.ohm_launcher

/**
 * Pure decision for reusing a previously materialized bundled background.
 *
 * Bundled assets are immutable for a given APK version, so a byte-identical
 * materialized file is reused instead of rewritten. Keeping the file's mtime
 * stable keeps the system-wallpaper fingerprint (path:size:mtime) stable,
 * which suppresses duplicate Android wallpaper writes when the same theme is
 * applied again. Content comparison (not size alone) keeps APK updates that
 * change a background under the same name working.
 */
object OmarchyBundledBackground {
    fun shouldReuse(existing: ByteArray?, asset: ByteArray): Boolean =
        existing != null && existing.size == asset.size && existing.contentEquals(asset)
}
