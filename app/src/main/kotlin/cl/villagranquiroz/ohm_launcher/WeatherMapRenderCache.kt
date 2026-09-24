package cl.villagranquiroz.ohm_launcher

import java.io.File
import org.json.JSONObject

/** Small, expiring disk cache for Rust-rendered PNG chart frames. */
internal class WeatherMapRenderCache(
    private val directory: File,
    private val ttlMillis: Long = TTL_MILLIS,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxBytes: Long = MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun get(key: String): String? {
        if (!isValidKey(key)) return null
        val entry = File(directory, key)
        val metadata = File(entry, METADATA_FILE)
        val chart = runCatching { JSONObject(metadata.readText()) }.getOrNull()
            ?: return null.also { entry.deleteRecursively() }
        val cachedAt = chart.optLong(CACHED_AT_FIELD, Long.MIN_VALUE)
        val age = clock() - cachedAt
        if (cachedAt == Long.MIN_VALUE || age !in 0 until ttlMillis) {
            entry.deleteRecursively()
            return null
        }
        val frames = chart.optJSONArray("frames")
            ?: return null.also { entry.deleteRecursively() }
        if (frames.length() == 0) {
            entry.deleteRecursively()
            return null
        }
        val rootPath = runCatching { entry.canonicalPath }.getOrNull()
            ?: return null.also { entry.deleteRecursively() }
        for (index in 0 until frames.length()) {
            val frame = frames.optJSONObject(index)
            val path = frame?.optString("path").orEmpty()
            val file = if (path.isNotBlank()) runCatching { File(path).canonicalFile }.getOrNull() else null
            if (file == null || file.parent != rootPath || !file.isFile) {
                entry.deleteRecursively()
                return null
            }
        }
        chart.remove(CACHED_AT_FIELD)
        entry.setLastModified(clock())
        return chart.toString()
    }

    /** Copies Rust output frames into an atomic cache entry; the caller retains its originals on failure. */
    fun put(key: String, chartJson: String): Boolean {
        if (!isValidKey(key) || ttlMillis <= 0 || maxBytes <= 0) return false
        val chart = runCatching { JSONObject(chartJson) }.getOrNull() ?: return false
        val frames = chart.optJSONArray("frames") ?: return false
        if (frames.length() == 0) return false

        val now = clock()
        if (!directory.exists() && !directory.mkdirs()) return false
        val finalEntry = File(directory, key)
        val tempEntry = File(directory, ".$key-${System.nanoTime()}.tmp")
        if (!tempEntry.mkdirs()) return false
        val frameNames = ArrayList<String>(frames.length())
        for (index in 0 until frames.length()) {
            val frame = frames.optJSONObject(index) ?: run {
                tempEntry.deleteRecursively()
                return false
            }
            val sourcePath = frame.optString("path")
            val source = sourcePath.takeIf(String::isNotBlank)?.let(::File)
            if (source == null || !source.isFile) {
                tempEntry.deleteRecursively()
                return false
            }
            val frameName = "frame-${index.toString().padStart(2, '0')}.png"
            val target = File(tempEntry, frameName)
            if (runCatching { source.copyTo(target, overwrite = true) }.isFailure) {
                tempEntry.deleteRecursively()
                return false
            }
            frameNames += frameName
            frame.put("path", File(finalEntry, frameName).absolutePath)
        }
        chart.put(CACHED_AT_FIELD, now)
        if (runCatching { File(tempEntry, METADATA_FILE).writeText(chart.toString()) }.isFailure ||
            entrySize(tempEntry) > maxBytes
        ) {
            tempEntry.deleteRecursively()
            return false
        }
        if (finalEntry.exists() && !finalEntry.deleteRecursively()) {
            tempEntry.deleteRecursively()
            return false
        }
        if (!tempEntry.renameTo(finalEntry)) {
            tempEntry.deleteRecursively()
            return false
        }
        prune(now, protectedKey = key)
        return frameNames.isNotEmpty()
    }

    private fun prune(now: Long, protectedKey: String) {
        val entries = directory.listFiles()?.filter(File::isDirectory).orEmpty()
        val valid = entries.filter { entry ->
            if (entry.name == protectedKey) return@filter true
            val metadata = runCatching { JSONObject(File(entry, METADATA_FILE).readText()) }.getOrNull()
            val cachedAt = metadata?.optLong(CACHED_AT_FIELD, Long.MIN_VALUE) ?: Long.MIN_VALUE
            val age = now - cachedAt
            if (cachedAt == Long.MIN_VALUE || age !in 0 until ttlMillis) {
                entry.deleteRecursively()
                false
            } else {
                true
            }
        }.sortedBy(File::lastModified).toMutableList()
        var size = valid.sumOf(::entrySize)
        while (valid.size > maxEntries.coerceAtLeast(1) || size > maxBytes) {
            val oldest = valid.firstOrNull { it.name != protectedKey } ?: break
            size -= entrySize(oldest)
            oldest.deleteRecursively()
            valid.remove(oldest)
        }
    }

    private fun entrySize(entry: File): Long =
        entry.walkTopDown().filter(File::isFile).sumOf(File::length)

    private fun isValidKey(key: String): Boolean = key.matches(KEY_PATTERN)

    companion object {
        const val TTL_MILLIS = 10L * 60L * 1000L
        private const val MAX_ENTRIES = 8
        private const val MAX_BYTES = 24L * 1024L * 1024L
        private const val METADATA_FILE = "chart.json"
        private const val CACHED_AT_FIELD = "cachedAtMillis"
        private val KEY_PATTERN = Regex("[a-f0-9]{64}")
    }
}
