package cl.villagranquiroz.ohm_launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShellExecutorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun executor(binDir: String? = null, homeDir: String? = null) = ShellExecutor(
        shellPath = "/bin/sh",
        linkerPath = "/lib64/ld-linux-x86-64.so.2",
        systemPath = "/usr/bin:/bin",
        binDir = binDir,
        homeDir = homeDir,
    )

    @Test
    fun runsCommandThroughEmbeddedShellAndQuotesArguments() {
        val result = executor().run("printf", listOf("%s", "hello world", "it's safe"))

        assertEquals(0, result.exitCode)
        assertEquals("hello worldit's safe", result.stdout)
        assertEquals("", result.stderr)
        assertEquals("embedded", result.via)
        assertEquals(0, result.toJson()["exitCode"])
    }

    @Test
    fun returnsExitCodeAndStandardError() {
        val result = executor().run("printf failure >&2; exit 7")

        assertEquals(7, result.exitCode)
        assertEquals("failure", result.stderr)
    }

    @Test
    fun suppliesConfiguredHomeWorkingDirectoryAndBinPath() {
        val home = temporary.newFolder("home")
        val work = temporary.newFolder("work")
        val bin = temporary.newFolder("bin")

        val result = executor(bin.absolutePath, home.absolutePath)
            .run("printf '%s|%s|%s' \"${'$'}HOME\" \"${'$'}PWD\" \"${'$'}PATH\"", workingDirectory = work)

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.startsWith("${home.absolutePath}|${work.absolutePath}|${bin.absolutePath}:"))
    }

    @Test
    fun invokesInstalledScriptsThroughShellToAvoidNoExecFilesystems() {
        val bin = temporary.newFolder("bin")
        bin.resolve("greet").writeText("#!/bin/sh\nprintf 'hello:%s' \"${'$'}1\"\n")
        bin.resolve("greet").setExecutable(false)

        val result = executor(bin.absolutePath).run("greet", listOf("Ohm user"))

        assertEquals(0, result.exitCode)
        assertEquals("hello:Ohm user", result.stdout)
    }

    @Test(timeout = 5_000)
    fun drainsStandardOutputAndErrorConcurrently() {
        val result = executor().run(
            "python3 -c 'import sys; sys.stderr.write(\"x\" * 262144); sys.stdout.write(\"done\")'",
        )

        assertEquals(0, result.exitCode)
        assertEquals("done", result.stdout)
        assertEquals(262_144, result.stderr.length)
    }

    @Test(timeout = 1_000)
    fun timesOutOptionalTermuxAndFallsBackToEmbedded() {
        val interrupted = CountDownLatch(1)
        val result = ShellExecutor(
            shellPath = "/bin/sh",
            systemPath = "/usr/bin:/bin",
            termuxTimeoutMillis = 50,
            termuxRunner = TermuxRunner { _, _ ->
                try {
                    Thread.sleep(10_000)
                } finally {
                    interrupted.countDown()
                }
                null
            },
        ).run("printf fallback", useTermux = true)

        assertEquals("fallback", result.stdout)
        assertTrue(interrupted.await(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun fallsBackToEmbeddedWhenOptionalTermuxReturnsNothing() {
        var calls = 0
        val result = ShellExecutor(
            shellPath = "/bin/sh",
            systemPath = "/usr/bin:/bin",
            termuxRunner = TermuxRunner { _, _ ->
                calls += 1
                null
            },
        ).run("printf fallback", useTermux = true)

        assertEquals(1, calls)
        assertEquals("fallback", result.stdout)
        assertEquals("embedded", result.via)
    }
}
