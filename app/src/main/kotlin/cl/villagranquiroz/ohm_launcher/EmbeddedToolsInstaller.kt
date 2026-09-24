package cl.villagranquiroz.ohm_launcher

import java.io.File
import java.io.InputStream

/** Installs the bundled Android command-line tools into the app-private noexec store. */
class EmbeddedToolsInstaller(
    private val filesRoot: File,
    private val openAsset: (String) -> InputStream,
) {
    fun install(supportedAbis: List<String>): Boolean {
        val assetDirectory = when {
            supportedAbis.any { it == "arm64-v8a" } -> "bin/android-arm64"
            else -> return false
        }
        val bin = filesRoot.resolve("bin").apply { mkdirs() }
        val required = TOOL_FILES + DROPBEAR_ALIASES
        val marker = filesRoot.resolve(MARKER)
        if (marker.readTextOrNull() == VERSION && required.all { bin.resolve(it).isFile }) {
            ensureTerminfo()
            return true
        }

        TOOL_FILES.forEach { name ->
            installAsset("$assetDirectory/$name", bin.resolve(name))
        }
        val dropbear = bin.resolve("dropbearmulti")
        DROPBEAR_ALIASES.forEach { name ->
            copyAtomically(dropbear, bin.resolve(name))
        }
        ensureTerminfo()
        marker.writeText(VERSION)
        return true
    }

    /** Remove only binaries previously seeded by the direct edition on a Play upgrade. */
    fun removeInstalledTools() {
        val marker = filesRoot.resolve(MARKER)
        if (!marker.isFile) return
        val bin = filesRoot.resolve("bin")
        (TOOL_FILES + DROPBEAR_ALIASES).forEach { name ->
            val file = bin.resolve(name)
            check(!file.exists() || file.delete()) { "Unable to remove bundled tool: $name" }
        }
        check(marker.delete()) { "Unable to clear embedded tools marker" }
    }

    private fun ensureTerminfo() {
        val target = filesRoot.resolve(".terminfo/x/xterm-256color")
        if (!target.isFile) installAsset("terminfo/x/xterm-256color", target)
    }

    private fun installAsset(assetPath: String, target: File) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.installing")
        openAsset(assetPath).use { input ->
            temporary.outputStream().use(input::copyTo)
        }
        replaceAtomically(temporary, target)
    }

    private fun copyAtomically(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.installing")
        source.inputStream().use { input -> temporary.outputStream().use(input::copyTo) }
        replaceAtomically(temporary, target)
    }

    private fun replaceAtomically(temporary: File, target: File) {
        target.delete()
        check(temporary.renameTo(target) || temporary.copyTo(target, overwrite = true).let { temporary.delete(); true })
        target.setReadable(true, true)
        target.setWritable(true, true)
    }

    private fun File.readTextOrNull(): String? = runCatching { takeIf(File::isFile)?.readText() }.getOrNull()

    companion object {
        const val VERSION = "termux-2026.94-tmux-3.7c-herdr-0.9.0-v2"
        const val MARKER = ".embedded-tools-version"

        private val TOOL_FILES = listOf(
            "tmux",
            "dropbearmulti",
            "herdr",
            "libandroid-support.so",
            "libandroid-glob.so",
            "libncursesw.so.6",
            "libevent_core-2.1.so",
            "libtermux-auth.so",
            "libz.so.1",
            "libcrypto.so.3",
        )
        private val DROPBEAR_ALIASES = listOf("ssh", "dbclient", "scp", "dropbearkey")
    }
}
