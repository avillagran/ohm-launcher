package cl.villagranquiroz.ohm_launcher

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinStoreTest {
    @Test
    fun installsListsAndRemovesOnlySafeNames() {
        val root = Files.createTempDirectory("ohm-bins").toFile()
        val store = BinStore(root)

        assertFalse(store.install("../escape", byteArrayOf(1))["ok"] as Boolean)
        val installed = store.install("hello-tool", "#!/system/bin/sh\n".toByteArray())
        assertTrue(installed["ok"] as Boolean)
        assertEquals("hello-tool", installed["name"])
        assertEquals(listOf("hello-tool"), store.list().map { it["name"] })
        assertTrue(store.remove("hello-tool")["removed"] as Boolean)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun streamingInstallWritesAllBytes() {
        val root = Files.createTempDirectory("ohm-bins-stream").toFile()
        val store = BinStore(root)
        val bytes = ByteArray(8193) { (it % 251).toByte() }

        val result = store.install("payload", ByteArrayInputStream(bytes))

        assertEquals(bytes.size.toLong(), result["size"])
        assertTrue(root.resolve("payload").readBytes().contentEquals(bytes))
    }
}
