package cl.villagranquiroz.ohm_launcher

import java.io.InterruptedIOException
import java.io.Reader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuakeTerminalLogicTest {
    @get:Rule
    val temporary = TemporaryFolder()
    @Test
    fun historyReturnsNewestCommandsFirstAndRestoresDraft() {
        val history = TerminalCommandHistory()
        history.record("pwd")
        history.record("cd /tmp")

        assertEquals("cd /tmp", history.previous("unfinished"))
        assertEquals("pwd", history.previous("ignored"))
        assertEquals("cd /tmp", history.next())
        assertEquals("unfinished", history.next())
    }

    @Test
    fun specialKeysProduceTerminalControlSequences() {
        assertEquals("\u001b", TerminalControlSequences.forKey(TerminalControl.ESCAPE))
        assertEquals("\t", TerminalControlSequences.forKey(TerminalControl.TAB))
        assertEquals("\u001b[A", TerminalControlSequences.forKey(TerminalControl.UP))
        assertEquals("\u001b[B", TerminalControlSequences.forKey(TerminalControl.DOWN))
        assertEquals("\u001b[D", TerminalControlSequences.forKey(TerminalControl.LEFT))
        assertEquals("\u001b[C", TerminalControlSequences.forKey(TerminalControl.RIGHT))
    }

    @Test
    fun modifiersEncodeOnePrintableCharacterAndReset() {
        val modifiers = TerminalModifierState()
        modifiers.toggleCtrl()
        assertEquals("\u0003", modifiers.consume('c'))
        assertEquals(null, modifiers.consume('x'))

        modifiers.toggleAlt()
        assertEquals("\u001bx", modifiers.consume('X'))
        assertEquals(null, modifiers.consume('x'))
    }

    @Test
    fun ctrlTakesPrecedenceWhenBothModifiersAreActive() {
        val modifiers = TerminalModifierState()
        modifiers.toggleCtrl()
        modifiers.toggleAlt()

        assertEquals("\u001a", modifiers.consume('z'))
        assertEquals(false, modifiers.ctrlActive)
        assertEquals(false, modifiers.altActive)
    }

    @Test
    fun commandFrameReportsExitWithoutStartingANewShell() {
        val framed = TerminalCommandProtocol.frame("cd '/tmp/space here'", 42)

        assertTrue(framed.startsWith("cd '/tmp/space here'\n"))
        assertTrue(framed.contains("__ohm_status=${'$'}?"))
        assertTrue(framed.contains("OHM_DONE:42:%s"))
        assertFalse(framed.contains("sh -c"))
    }

    @Test
    fun streamDecoderHandlesMarkersSplitAcrossReads() {
        val decoder = TerminalStreamDecoder()
        val first = decoder.accept("hello\n\u001eOHM_DO")
        val second = decoder.accept("NE:7:13\u001f\nnext")

        assertEquals("hello\n", first.text)
        assertEquals(emptyList<TerminalCommandCompletion>(), first.completions)
        assertEquals("\nnext", second.text)
        assertEquals(listOf(TerminalCommandCompletion(7, 13)), second.completions)
    }

    @Test
    fun bootstrapUsesShellForScriptsAndLinkerForAppDataElfBinaries() {
        val bin = temporary.newFolder("bin with space")
        bin.resolve("script_tool").writeText("#!/system/bin/sh\nprintf ok\n")
        bin.resolve("plain_tool").writeText("printf ok\n")
        bin.resolve("native_tool").writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
        bin.resolve("not.a.function").writeText("#!/system/bin/sh\n")

        val bootstrap = NoExecShellBootstrap.render(
            binDir = bin,
            homeDir = temporary.newFolder("home"),
            shellPath = "/system/bin/sh",
            linkerPath = "/system/bin/linker64",
        )

        assertTrue(bootstrap.contains("script_tool() { '/system/bin/sh' '${bin.resolve("script_tool").path}' \"${'$'}@\"; }"))
        assertTrue(bootstrap.contains("plain_tool() { '/system/bin/sh' '${bin.resolve("plain_tool").path}' \"${'$'}@\"; }"))
        assertTrue(bootstrap.contains("native_tool() { '/system/bin/linker64' '${bin.resolve("native_tool").path}' \"${'$'}@\"; }"))
        assertFalse(bootstrap.contains("not.a.function()"))
    }

    @Test
    fun persistentShellKeepsWorkingDirectoryAndEnvironmentAcrossCommands() {
        val home = temporary.newFolder("session-home")
        val destination = temporary.newFolder("destination")
        val output = StringBuilder()
        val completed = CountDownLatch(3)
        val session = PersistentShellSession(
            shellPath = "/bin/sh",
            linkerPath = "/lib64/ld-linux-x86-64.so.2",
            workingDirectory = home,
            homeDir = home,
            binDir = null,
            onOutput = { synchronized(output) { output.append(it) } },
            onCommandFinished = { _, _ -> completed.countDown() },
        )

        try {
            session.start()
            session.execute("cd '${destination.path}'")
            session.execute("export OHM_PERSISTED=yes")
            session.execute("printf '%s|%s' \"${'$'}PWD\" \"${'$'}OHM_PERSISTED\"")

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertTrue(output.toString().contains("${destination.path}|yes"))
        } finally {
            session.close()
        }
    }

    @Test
    fun terminalReaderTreatsStreamInterruptionDuringCloseAsEndOfStream() {
        val interruptedReader = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int =
                throw InterruptedIOException("closed")

            override fun close() = Unit
        }

        TerminalStreamReader.consume(interruptedReader) { error("unexpected output") }
    }

    @Test
    fun selectsNativePtyOnlyOnAndroidRuntime() {
        assertTrue(TerminalBackendPolicy.useNativePty("Android Runtime"))
        assertFalse(TerminalBackendPolicy.useNativePty("OpenJDK Runtime Environment"))
    }

    @Test
    fun bootstrapFilterSuppressesEchoEvenWhenMarkerIsSplitAcrossReads() {
        val filter = TerminalBootstrapFilter()

        assertEquals("", filter.accept("export HOME=x\n\u001eOHM_BOOT"))
        assertEquals("\n$ ", filter.accept("STRAP_DONE\u001f\n$ "))
        assertEquals("ready", filter.accept("ready"))
    }

    @Test
    fun quakeClosesOnlyForACommittedUpwardSwipe() {
        assertTrue(QuakeSwipePolicy.shouldClose(deltaX = 20f, deltaY = -180f, velocityY = -900f))
        assertFalse(QuakeSwipePolicy.shouldClose(deltaX = 180f, deltaY = -80f, velocityY = -900f))
        assertFalse(QuakeSwipePolicy.shouldClose(deltaX = 10f, deltaY = 180f, velocityY = 900f))
    }
}
