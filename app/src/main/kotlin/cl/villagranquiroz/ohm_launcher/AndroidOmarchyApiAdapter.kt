package cl.villagranquiroz.ohm_launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bounded filesystem implementation for Omarchy file browsing and sharing. */
class OmarchyFileRepository(
    allowedRoot: File,
    private val sharedRoot: File,
) {
    private val root = allowedRoot.canonicalFile

    fun list(path: String?): FilesResponse? {
        val directory = resolve(path?.takeIf { it.isNotBlank() } ?: root.absolutePath) ?: return null
        if (!directory.isDirectory) return null
        val entries = directory.listFiles()?.asSequence()
            ?.take(MAX_ENTRIES)
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            ?.map {
                OmarchyFileEntry(
                    name = it.name,
                    path = it.absolutePath,
                    isDirectory = it.isDirectory,
                    size = if (it.isFile) it.length().coerceAtLeast(0) else 0,
                    modified = it.lastModified().coerceAtLeast(0),
                )
            }
            ?.toList()
            .orEmpty()
        val parent = directory.parentFile?.takeIf { isInside(it) }?.absolutePath ?: directory.absolutePath
        return FilesResponse(directory.absolutePath, parent, entries)
    }

    fun upload(name: String, bytes: ByteArray): File? {
        if (bytes.size > MAX_UPLOAD_BYTES) return null
        val safeName = File(name).name
        if (safeName.isBlank() || safeName.any(Char::isISOControl) || safeName in setOf(".", "..")) return null
        return runCatching {
            sharedRoot.mkdirs()
            val target = sharedRoot.resolve(safeName).canonicalFile
            if (!isInside(sharedRoot.canonicalFile, target)) return null
            val temporary = sharedRoot.resolve(".$safeName.tmp-${System.nanoTime()}")
            try {
                temporary.writeBytes(bytes)
                check(temporary.renameTo(target) || temporary.copyTo(target, overwrite = true).let { temporary.delete() })
            } finally {
                temporary.delete()
            }
            target
        }.getOrNull()
    }

    fun download(path: String): ByteArray? {
        val file = resolve(path) ?: return null
        if (!file.isFile || file.length() > MAX_DOWNLOAD_BYTES) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun photos(): List<OmarchyFileEntry> {
        val extensions = setOf("jpg", "jpeg", "png", "heic", "webp", "mp4", "mov")
        val roots = listOf(root.resolve("DCIM"), root.resolve("Pictures"))
        return roots.asSequence()
            .filter(File::isDirectory)
            .flatMap { it.walkTopDown().onEnter { directory -> !java.nio.file.Files.isSymbolicLink(directory.toPath()) } }
            .filter { it.isFile && it.extension.lowercase() in extensions }
            .take(MAX_PHOTOS)
            .map { OmarchyFileEntry(it.name, it.absolutePath, false, it.length().coerceAtLeast(0), it.lastModified().coerceAtLeast(0)) }
            .toList()
    }

    private fun resolve(path: String): File? = runCatching {
        val translated = when {
            path == "/sdcard" -> root
            path.startsWith("/sdcard/") -> root.resolve(path.removePrefix("/sdcard/"))
            File(path).isAbsolute -> File(path)
            else -> root.resolve(path)
        }.canonicalFile
        translated.takeIf(::isInside)
    }.getOrNull()

    private fun isInside(file: File): Boolean = isInside(root, file)

    private fun isInside(parent: File, child: File): Boolean =
        child.path == parent.path || child.path.startsWith(parent.path + File.separator)

    companion object {
        private const val MAX_ENTRIES = 2_000
        private const val MAX_PHOTOS = 10_000
        private const val MAX_UPLOAD_BYTES = 256 * 1024 * 1024
        private const val MAX_DOWNLOAD_BYTES = 512 * 1024 * 1024
    }
}

/** Android implementation of all transport-neutral Omarchy REST handlers. */
class AndroidOmarchyApiAdapter(
    private val context: Context,
    private val files: OmarchyFileRepository,
    private val settingsFile: File,
    private val lanIp: () -> String,
    private val apiPort: Int = 8753,
    private val onScreenStart: () -> Boolean,
    private val onScreenStop: () -> Unit,
    private val onThemeGet: (() -> JSONObject)? = null,
    private val onThemePut: ((JSONObject) -> Unit)? = null,
    /** Fired when a remote input arrives but the accessibility service that
     *  performs gestures is disabled — the UI prompts from there (lazily),
     *  so starting screen share only takes ONE confirmation. */
    private val onInputAccessibilityBlocked: (() -> Unit)? = null,
) : OmarchyApiAdapter {
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun handle(request: OmarchyRestRequest): OmarchyApiResponse {
        return when (request.route) {
        OmarchyRestRoute.DISCOVER -> OmarchyApiResponse.ok(
            DiscoverResponse(
                name = "OhmLauncher",
                model = Build.MODEL.ifBlank { "Android" },
                version = 1,
                lanIp = lanIp(),
                port = apiPort,
                capabilities = listOf("clipboard", "file", "files", "theme", "screen", "photos", "input", "notifications"),
            ).toJson(),
        )
        OmarchyRestRoute.CLIPBOARD_GET -> OmarchyApiResponse.ok(
            ClipboardPayload(clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()).toJson(),
        )
        OmarchyRestRoute.CLIPBOARD_PUT -> {
            val payload = ClipboardPayload.parse(request.body)
                ?: return OmarchyApiResponse.badRequest("invalid_clipboard")
            clipboard.setPrimaryClip(ClipData.newPlainText("Omarchy", payload.text))
            OmarchyApiResponse.ok()
        }
        OmarchyRestRoute.THEME_GET -> OmarchyApiResponse.ok(onThemeGet?.invoke() ?: themeSnapshot())
        OmarchyRestRoute.THEME_PUT -> {
            onThemePut?.invoke(request.body) ?: updateSettings(request.body)
            OmarchyApiResponse.ok()
        }
        OmarchyRestRoute.FILES_LIST -> files.list(request.query["path"])
            ?.let { OmarchyApiResponse.ok(it.toJson()) }
            ?: OmarchyApiResponse.badRequest("invalid_path")
        OmarchyRestRoute.INPUT -> input(request.body)
        OmarchyRestRoute.SCREEN_START -> OmarchyApiResponse.ok(
            JSONObject().put("status", if (onScreenStart()) "started" else "denied"),
        )
        OmarchyRestRoute.SCREEN_STOP -> {
            onScreenStop()
            OmarchyApiResponse.ok()
        }
        OmarchyRestRoute.PHOTOS_BACKUP -> {
            val photos = files.photos()
            OmarchyApiResponse.ok(
                JSONObject()
                    .put("status", "ok")
                    .put("count", photos.size)
                    .put("photos", JSONArray(photos.map(OmarchyFileEntry::toJson))),
            )
        }
        OmarchyRestRoute.FILE_UPLOAD,
        OmarchyRestRoute.FILE_DOWNLOAD -> OmarchyApiResponse.unsupported("file")
            null -> OmarchyApiResponse.notFound(request.path)
        }
    }

    override fun uploadFile(name: String, bytes: ByteArray): OmarchyApiResponse {
        val file = files.upload(name, bytes) ?: return OmarchyApiResponse.badRequest("invalid_file")
        return OmarchyApiResponse.ok(
            JSONObject().put("ok", true).put("path", file.absolutePath).put("bytes", file.length()),
        )
    }

    override fun downloadFile(path: String): OmarchyFileDownload? =
        files.download(path)?.let { OmarchyFileDownload(it, fileName = File(path).name) }

    private fun input(body: JSONObject): OmarchyApiResponse {
        val service = OhmGestureAccessibilityService.instance
            ?: return OmarchyApiResponse.ok(
                JSONObject().put("ok", false).put("error", "accessibility_disabled")
                    .also { onInputAccessibilityBlocked?.invoke() },
            )
        val accepted = when (val action = body.optString("action")) {
            "tap" -> {
                var value = false
                service.remoteTap(body.number("x")?.toFloat() ?: return invalidInput(), body.number("y")?.toFloat() ?: return invalidInput()) { value = it }
                value
            }
            "swipe" -> {
                var value = false
                service.remoteSwipe(
                    body.number("x1")?.toFloat() ?: return invalidInput(),
                    body.number("y1")?.toFloat() ?: return invalidInput(),
                    body.number("x2")?.toFloat() ?: return invalidInput(),
                    body.number("y2")?.toFloat() ?: return invalidInput(),
                    body.optLong("durationMs", 300).coerceIn(50, 5_000),
                ) { value = it }
                value
            }
            "key" -> service.remoteKey(body.optString("key"))
            else -> return OmarchyApiResponse.ok(
                JSONObject().put("ok", false).put("error", "unknown_action").put("action", action),
            )
        }
        return OmarchyApiResponse.ok(JSONObject().put("ok", accepted))
    }

    private fun invalidInput() = OmarchyApiResponse.badRequest("invalid_input")

    @Synchronized
    private fun updateSettings(changes: JSONObject) {
        val settings = runCatching { if (settingsFile.isFile) JSONObject(settingsFile.readText()) else JSONObject() }
            .getOrElse { JSONObject() }
        changes.keys().forEach { key -> settings.put(key, changes.get(key)) }
        val parent = requireNotNull(settingsFile.parentFile)
        parent.mkdirs()
        val temporary = parent.resolve("${settingsFile.name}.tmp")
        temporary.writeText(settings.toString(2))
        check(temporary.renameTo(settingsFile) || temporary.copyTo(settingsFile, overwrite = true).let { temporary.delete() })
    }

    private fun themeSnapshot(): JSONObject {
        val settings = runCatching { if (settingsFile.isFile) JSONObject(settingsFile.readText()) else JSONObject() }
            .getOrElse { JSONObject() }
        val colors = JSONObject()
        settings.keys().forEach { key ->
            val normalized = key.lowercase()
            if (listOf("color", "tema", "theme", "background", "accent").any(normalized::contains)) {
                colors.put(key, settings.get(key))
            }
        }
        return JSONObject().put("colors", colors)
    }

    private fun JSONObject.number(key: String): Number? = opt(key) as? Number
}
