package cl.villagranquiroz.ohm_launcher

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val via: String,
) {
    fun toJson(): Map<String, Any> = mapOf(
        "exitCode" to exitCode,
        "stdout" to stdout,
        "stderr" to stderr,
        "via" to via,
    )

    override fun toString(): String =
        "ShellResult(via=$via, exit=$exitCode, stdout=${stdout.length}b, stderr=${stderr.length}b)"
}

fun interface TermuxRunner {
    /** Returns null when Termux is unavailable or execution failed before producing a result. */
    fun run(command: String, args: List<String>?): ShellResult?
}

/** Executes commands inside the app process without depending on a terminal application. */
class ShellExecutor(
    private val shellPath: String = "/system/bin/sh",
    private val linkerPath: String = if (File("/system/bin/linker64").isFile) {
        "/system/bin/linker64"
    } else {
        "/system/bin/linker"
    },
    private val systemPath: String = listOf(
        "/system/bin",
        "/system/xbin",
        "/sbin",
        "/vendor/bin",
        "/odm/bin",
        "/product/bin",
    ).joinToString(":"),
    private val binDir: String? = null,
    private val homeDir: String? = null,
    private val termuxRunner: TermuxRunner? = null,
    private val termuxTimeoutMillis: Long = 5_000,
) {
    fun run(
        command: String,
        args: List<String>? = null,
        useTermux: Boolean = false,
        workingDirectory: File? = null,
    ): ShellResult {
        if (useTermux) {
            runInTermux(command, args)?.let { return it }
        }
        return runEmbedded(command, args, workingDirectory)
    }

    private fun runInTermux(command: String, args: List<String>?): ShellResult? {
        val runner = termuxRunner ?: return null
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ohm-termux-runner").apply { isDaemon = true }
        }
        return try {
            executor.submit<ShellResult?> { runner.run(command, args) }
                .get(termuxTimeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        } finally {
            executor.shutdownNow()
        }
    }

    private fun runEmbedded(
        command: String,
        args: List<String>?,
        workingDirectory: File?,
    ): ShellResult = try {
        val resolved = if (binDir.isNullOrEmpty()) command else resolveOwnBin(command, binDir)
        val fullCommand = if (args.isNullOrEmpty()) {
            resolved
        } else {
            "$resolved ${args.joinToString(" ") { quote(it) }}"
        }
        val process = ProcessBuilder(shellPath, "-c", fullCommand).apply {
            directory(workingDirectory)
            environment().apply {
                clear()
                put("PATH", listOfNotNull(binDir?.takeIf(String::isNotEmpty), systemPath).joinToString(":"))
                put("SHELL", shellPath)
                binDir?.takeIf(String::isNotEmpty)?.let { put("LD_LIBRARY_PATH", it) }
                homeDir?.takeIf(String::isNotEmpty)?.let { put("HOME", it) }
                put("TMPDIR", homeDir ?: workingDirectory?.absolutePath ?: "/data/local/tmp")
            }
        }.start()
        val streams = arrayOfNulls<ByteArray>(2)
        val streamErrors = arrayOfNulls<Exception>(2)
        val stdoutReader = Thread({
            try {
                streams[0] = process.inputStream.readBytes()
            } catch (error: Exception) {
                streamErrors[0] = error
            }
        }, "ohm-shell-stdout").apply { isDaemon = true }
        val stderrReader = Thread({
            try {
                streams[1] = process.errorStream.readBytes()
            } catch (error: Exception) {
                streamErrors[1] = error
            }
        }, "ohm-shell-stderr").apply { isDaemon = true }
        stdoutReader.start()
        stderrReader.start()
        val exitCode = process.waitFor()
        stdoutReader.join()
        stderrReader.join()
        streamErrors.firstOrNull { it != null }?.let { throw it }
        ShellResult(
            exitCode,
            (streams[0] ?: ByteArray(0)).toString(StandardCharsets.UTF_8),
            (streams[1] ?: ByteArray(0)).toString(StandardCharsets.UTF_8),
            "embedded",
        )
    } catch (error: Exception) {
        ShellResult(-1, "", "embedded_error: $error", "error")
    }

    private fun resolveOwnBin(command: String, directory: String): String {
        val separator = command.indexOf(' ')
        val token = if (separator < 0) command else command.substring(0, separator)
        val rest = if (separator < 0) "" else command.substring(separator)
        if (token.isEmpty() || token.contains('/')) return command
        val binary = File(directory, token)
        if (!binary.isFile) return command
        val header = try {
            binary.inputStream().use { input -> ByteArray(2).also { input.read(it) } }
        } catch (_: Exception) {
            return command
        }
        val interpreter = if (header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte()) {
            shellPath
        } else {
            linkerPath
        }
        return "$interpreter ${quote(binary.absolutePath)}$rest"
    }

    companion object {
        internal fun quote(argument: String): String {
            if (argument.none { it.isWhitespace() || it !in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_@%+=:,./-" }) {
                return argument
            }
            return "'${argument.replace("'", "'\\''")}'"
        }
    }
}
