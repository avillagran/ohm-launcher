package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeZipArchiveTest {
    @Test
    fun readsRegularNestedFilesWithinLimits() {
        val archive = zipOf("plugin/manifest.json" to "{}".toByteArray(), "plugin/ui/Main.qml" to "Item {}".toByteArray())

        val files = SafeZipArchive(maxArchiveBytes = 1024, maxEntryBytes = 64, maxExtractedBytes = 128).read(archive)

        assertEquals(setOf("plugin/manifest.json", "plugin/ui/Main.qml"), files.keys)
        assertTrue(files.getValue("plugin/ui/Main.qml").contentEquals("Item {}".toByteArray()))
    }

    @Test
    fun rejectsTraversalAbsoluteAndWindowsPaths() {
        listOf("../escape", "/absolute", "C:/windows", "safe/../../escape", "safe\\escape").forEach { path ->
            val error = runCatching { SafeZipArchive().read(zipOf(path to byteArrayOf(1))) }.exceptionOrNull()
            assertTrue("Expected rejection for $path", error is UnsafeArchiveException)
        }
    }

    @Test
    fun rejectsExtractedDataOverEntryOrAggregateLimits() {
        val tooLargeEntry = zipOf("large" to ByteArray(9))
        assertTrue(
            runCatching { SafeZipArchive(maxEntryBytes = 8).read(tooLargeEntry) }.exceptionOrNull()
                is UnsafeArchiveException,
        )
        val aggregate = zipOf("one" to ByteArray(6), "two" to ByteArray(6))
        assertTrue(
            runCatching { SafeZipArchive(maxEntryBytes = 8, maxExtractedBytes = 10).read(aggregate) }.exceptionOrNull()
                is UnsafeArchiveException,
        )
    }

    @Test
    fun rejectsUnixSymlinkEntriesFromCentralDirectoryMetadata() {
        val archive = zipOf("linked" to "target".toByteArray())
        markFirstEntryAsUnixSymlink(archive)

        val error = runCatching { SafeZipArchive().read(archive) }.exceptionOrNull()

        assertTrue(error is UnsafeArchiveException)
        assertTrue(error?.message.orEmpty().contains("symlink", ignoreCase = true))
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun markFirstEntryAsUnixSymlink(bytes: ByteArray) {
        val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val offset = bytes.indices.first { index ->
            index + signature.size <= bytes.size && signature.indices.all { bytes[index + it] == signature[it] }
        }
        bytes[offset + 5] = 3 // "version made by" platform: Unix.
        val externalAttributes = (0xA000 or 0x1FF) shl 16
        repeat(4) { index -> bytes[offset + 38 + index] = (externalAttributes ushr (8 * index)).toByte() }
    }
}
