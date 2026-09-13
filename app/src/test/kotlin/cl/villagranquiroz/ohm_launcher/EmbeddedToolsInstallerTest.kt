package cl.villagranquiroz.ohm_launcher

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmbeddedToolsInstallerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun installsTmuxSshHerdrLibrariesAliasesAndTerminfoForArm64() {
        val root = temporary.newFolder("files")
        val requested = mutableListOf<String>()
        val installer = EmbeddedToolsInstaller(root) { path ->
            requested += path
            ByteArrayInputStream("asset:$path".toByteArray())
        }

        assertTrue(installer.install(listOf("arm64-v8a")))

        assertTrue(root.resolve("bin/tmux").isFile)
        assertTrue(root.resolve("bin/herdr").isFile)
        assertTrue(root.resolve("bin/dropbearmulti").isFile)
        assertArrayEquals(root.resolve("bin/dropbearmulti").readBytes(), root.resolve("bin/ssh").readBytes())
        assertArrayEquals(root.resolve("bin/dropbearmulti").readBytes(), root.resolve("bin/scp").readBytes())
        assertTrue(root.resolve("bin/libncursesw.so.6").isFile)
        assertTrue(root.resolve(".terminfo/x/xterm-256color").isFile)
        assertEquals(EmbeddedToolsInstaller.VERSION, root.resolve(EmbeddedToolsInstaller.MARKER).readText())
        assertTrue(requested.contains("bin/android-arm64/tmux"))
    }

    @Test
    fun skipsUnsupportedAbisWithoutCreatingAMisleadingMarker() {
        val root = temporary.newFolder("unsupported")
        val installer = EmbeddedToolsInstaller(root) { error("must not read assets") }

        assertFalse(installer.install(listOf("armeabi-v7a")))
        assertFalse(root.resolve(EmbeddedToolsInstaller.MARKER).exists())
    }
}
