package cl.villagranquiroz.ohm_launcher

import java.io.File
import java.io.InputStream

/** Private executable store used by the embedded shell and local API. */
class BinStore(private val root: File) {
    init {
        root.mkdirs()
    }

    fun install(name: String, bytes: ByteArray): Map<String, Any?> =
        install(name, bytes.inputStream())

    fun install(name: String, input: InputStream): Map<String, Any?> {
        if (!validName(name)) return mapOf("ok" to false, "error" to "invalid_name")
        return runCatching {
            root.mkdirs()
            val target = root.resolve(name)
            val temporary = root.resolve(".$name.tmp-${System.nanoTime()}")
            var size = 0L
            try {
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        size += count
                    }
                }
                check(temporary.renameTo(target) || temporary.copyTo(target, overwrite = true).let { temporary.delete() })
                target.setReadable(true, true)
                target.setExecutable(true, true)
                mapOf("ok" to true, "name" to name, "size" to size, "path" to target.absolutePath)
            } finally {
                temporary.delete()
            }
        }.getOrElse { mapOf("ok" to false, "error" to "write_failed", "detail" to it.toString()) }
    }

    fun list(): List<Map<String, Any?>> = root.listFiles()
        ?.asSequence()
        ?.filter { it.isFile && !it.name.startsWith('.') }
        ?.sortedBy { it.name }
        ?.map { mapOf("name" to it.name, "size" to it.length(), "path" to it.absolutePath) }
        ?.toList()
        .orEmpty()

    fun remove(name: String): Map<String, Any?> {
        if (!validName(name)) return mapOf("ok" to false, "error" to "invalid_name")
        val target = root.resolve(name)
        val existed = target.isFile
        val removed = !existed || target.delete()
        return if (removed) mapOf("ok" to true, "name" to name, "removed" to existed)
        else mapOf("ok" to false, "error" to "delete_failed", "name" to name)
    }

    companion object {
        private val namePattern = Regex("^[A-Za-z0-9._-]+$")

        fun validName(name: String): Boolean =
            name.isNotEmpty() && !name.contains("..") && namePattern.matches(name)
    }
}
