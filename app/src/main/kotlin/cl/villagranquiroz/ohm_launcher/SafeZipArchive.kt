package cl.villagranquiroz.ohm_launcher

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

class UnsafeArchiveException(message: String) : Exception(message)

/** Parses a ZIP into a bounded in-memory staging map without ever materializing archive paths. */
class SafeZipArchive(
    private val maxArchiveBytes: Long = 32L * 1024 * 1024,
    private val maxEntryBytes: Long = 8L * 1024 * 1024,
    private val maxExtractedBytes: Long = 64L * 1024 * 1024,
    private val maxEntries: Int = 2048,
) {
    fun read(archive: ByteArray): Map<String, ByteArray> {
        if (archive.size > maxArchiveBytes) reject("ZIP archive exceeds the size limit.")
        val centralEntries = inspectCentralDirectory(archive)
        val files = linkedMapOf<String, ByteArray>()
        var extracted = 0L
        var index = 0
        ZipInputStream(archive.inputStream(), StandardCharsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (index >= centralEntries.size || entry.name != centralEntries[index].name) {
                    reject("ZIP local and central directories do not match.")
                }
                val metadata = centralEntries[index++]
                if (!metadata.directory) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var entrySize = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entrySize += count
                        extracted += count
                        if (entrySize > maxEntryBytes || extracted > maxExtractedBytes) {
                            reject("ZIP extracted data exceeds the size limit.")
                        }
                        output.write(buffer, 0, count)
                    }
                    files[metadata.name] = output.toByteArray()
                }
                zip.closeEntry()
            }
        }
        if (index != centralEntries.size) reject("ZIP contains unreadable or inconsistent entries.")
        return files
    }

    private fun inspectCentralDirectory(bytes: ByteArray): List<CentralEntry> {
        val eocd = findEndOfCentralDirectory(bytes)
        if (u16(bytes, eocd + 4) != 0 || u16(bytes, eocd + 6) != 0) reject("Multi-disk ZIP files are unsupported.")
        val diskEntries = u16(bytes, eocd + 8)
        val entryCount = u16(bytes, eocd + 10)
        if (diskEntries != entryCount || entryCount > maxEntries) reject("ZIP has too many entries.")
        val centralSize = u32(bytes, eocd + 12)
        val centralOffset = u32(bytes, eocd + 16)
        if (centralSize == UINT32_MAX || centralOffset == UINT32_MAX) reject("ZIP64 archives are unsupported.")
        if (centralOffset + centralSize > eocd.toLong()) reject("Invalid ZIP central directory bounds.")

        val entries = ArrayList<CentralEntry>(entryCount)
        val names = hashSetOf<String>()
        var totalDeclared = 0L
        var cursor = centralOffset.toInt()
        repeat(entryCount) {
            if (cursor + CENTRAL_FIXED_SIZE > bytes.size || u32(bytes, cursor) != CENTRAL_SIGNATURE) {
                reject("Invalid ZIP central directory.")
            }
            val platform = bytes[cursor + 5].toInt() and 0xff
            val flags = u16(bytes, cursor + 8)
            if (flags and 1 != 0) reject("Encrypted ZIP entries are unsupported.")
            val compressedSize = u32(bytes, cursor + 20)
            val uncompressedSize = u32(bytes, cursor + 24)
            if (compressedSize == UINT32_MAX || uncompressedSize == UINT32_MAX) reject("ZIP64 entries are unsupported.")
            val nameLength = u16(bytes, cursor + 28)
            val extraLength = u16(bytes, cursor + 30)
            val commentLength = u16(bytes, cursor + 32)
            val end = cursor.toLong() + CENTRAL_FIXED_SIZE + nameLength + extraLength + commentLength
            if (end > bytes.size) reject("Truncated ZIP central directory entry.")
            val name = decodeUtf8(bytes, cursor + CENTRAL_FIXED_SIZE, nameLength)
            val directory = name.endsWith('/')
            val path = if (directory) name.dropLast(1) else name
            if (!PluginInstallPreparer.isSafeRelativePath(path)) reject("ZIP contains an unsafe path: $name")
            if (!names.add(name)) reject("ZIP contains duplicate paths: $name")
            val externalAttributes = u32(bytes, cursor + 38)
            val unixMode = if (platform == UNIX_PLATFORM) (externalAttributes ushr 16).toInt() else 0
            if (unixMode and UNIX_FILE_TYPE_MASK == UNIX_SYMLINK) reject("ZIP contains a symlink: $name")
            if (!directory) {
                if (uncompressedSize > maxEntryBytes) reject("ZIP entry exceeds the size limit: $name")
                totalDeclared += uncompressedSize
                if (totalDeclared > maxExtractedBytes) reject("ZIP extracted data exceeds the size limit.")
            }
            entries += CentralEntry(name, directory)
            cursor = end.toInt()
        }
        if (cursor.toLong() != centralOffset + centralSize) reject("ZIP central directory size is inconsistent.")
        return entries
    }

    private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
        val minimum = (bytes.size - MAX_EOCD_SEARCH).coerceAtLeast(0)
        for (offset in bytes.size - EOCD_MIN_SIZE downTo minimum) {
            if (u32OrNull(bytes, offset) == EOCD_SIGNATURE) {
                val commentLength = u16(bytes, offset + 20)
                if (offset + EOCD_MIN_SIZE + commentLength == bytes.size) return offset
            }
        }
        reject("Invalid ZIP: end of central directory not found.")
    }

    private fun decodeUtf8(bytes: ByteArray, offset: Int, length: Int): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, length))
            .toString()
    } catch (_: Exception) {
        reject("ZIP entry name is not valid UTF-8.")
    }

    private fun reject(message: String): Nothing = throw UnsafeArchiveException(message)

    private data class CentralEntry(val name: String, val directory: Boolean)

    companion object {
        private const val EOCD_SIGNATURE = 0x06054b50L
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val UINT32_MAX = 0xffffffffL
        private const val EOCD_MIN_SIZE = 22
        private const val MAX_EOCD_SEARCH = EOCD_MIN_SIZE + 65535
        private const val CENTRAL_FIXED_SIZE = 46
        private const val UNIX_PLATFORM = 3
        private const val UNIX_FILE_TYPE_MASK = 0xF000
        private const val UNIX_SYMLINK = 0xA000

        private fun u16(bytes: ByteArray, offset: Int): Int {
            if (offset < 0 || offset + 2 > bytes.size) throw UnsafeArchiveException("Truncated ZIP structure.")
            return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        }

        private fun u32(bytes: ByteArray, offset: Int): Long =
            u32OrNull(bytes, offset) ?: throw UnsafeArchiveException("Truncated ZIP structure.")

        private fun u32OrNull(bytes: ByteArray, offset: Int): Long? {
            if (offset < 0 || offset + 4 > bytes.size) return null
            return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and UINT32_MAX
        }
    }
}
