package cl.villagranquiroz.ohm_launcher

import java.io.File
import java.io.IOException

/** Canonical-path guard for file browsing, downloads and uploads. */
class OmarchyPathConfinement(roots: Collection<File>) {
    private val allowedRoots = roots.mapNotNull { it.canonicalOrNull() }.distinctBy { it.path }

    init {
        require(allowedRoots.isNotEmpty()) { "At least one allowed root is required" }
        require(allowedRoots.all(File::isAbsolute)) { "Allowed roots must be absolute" }
    }

    /** Returns a canonical file only when [path] remains inside an allowed root. */
    fun resolve(path: String): File? {
        if (path.isBlank()) return null
        val requested = File(path)
        if (!requested.isAbsolute) return null
        val candidate = requested.canonicalOrNull() ?: return null
        return candidate.takeIf(::isAllowed)
    }

    /** Resolves an upload without permitting a multipart filename to select directories. */
    fun resolveUpload(directory: File, fileName: String): File? {
        if (!isSafeFileName(fileName)) return null
        val parent = resolve(directory.path) ?: return null
        val candidate = parent.resolve(fileName).canonicalOrNull() ?: return null
        if (candidate.parentFile != parent || !isAllowed(candidate)) return null
        return candidate
    }

    private fun isAllowed(candidate: File): Boolean = allowedRoots.any { root ->
        candidate == root || candidate.path.startsWith(root.path.withTrailingSeparator())
    }

    companion object {
        fun isSafeFileName(value: String): Boolean =
            value.isNotEmpty() &&
                value != "." &&
                value != ".." &&
                value.none { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }
    }
}

private fun File.canonicalOrNull(): File? = try {
    canonicalFile
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

private fun String.withTrailingSeparator(): String = if (endsWith(File.separator)) this else this + File.separator
