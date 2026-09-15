package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

fun interface CommandHandler {
    fun handle(command: String, args: List<String>?): ShellResult
}

fun interface WidgetHandler {
    fun handle(source: String, format: String)
}

data class AiMessage(val role: String, val content: String)

data class AiResponse(
    val text: String,
    val widgetSource: String? = null,
    val widgetFormat: String? = null,
)

fun interface ChatHandler {
    fun handle(prompt: String, history: List<AiMessage>?): AiResponse
}

fun interface InstallBinHandler {
    fun handle(name: String, bytes: ByteArray): Map<String, Any?>
}

fun interface InstallBinRawHandler {
    fun handle(name: String, bytes: InputStream): Map<String, Any?>
}

fun interface ListBinsHandler {
    fun handle(): List<Map<String, Any?>>
}

fun interface UninstallBinHandler {
    fun handle(name: String): Map<String, Any?>
}

fun interface QuakeHandler {
    fun handle(open: Boolean)
}

/** Small dependency-free HTTP API used by local and LAN Ohm integrations. */
class LocalApiServer(
    private val port: Int = 8753,
    private val onCommand: CommandHandler,
    private val onInjectWidget: WidgetHandler,
    private val onChat: ChatHandler? = null,
    private val onInstallBin: InstallBinHandler? = null,
    private val onInstallBinRaw: InstallBinRawHandler? = null,
    private val onListBins: ListBinsHandler? = null,
    private val onUninstallBin: UninstallBinHandler? = null,
    private val onQuake: QuakeHandler? = null,
    private val omarchyAdapter: OmarchyApiAdapter? = null,
    private val screenFrames: LatestScreenFrameStore? = null,
    private val notificationChannel: OmarchyNotificationChannel? = null,
    private val lanMode: Boolean = false,
) : Closeable {
    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var boundPort: Int = port
        private set

    private var socket: ServerSocket? = null
    private var workers: ExecutorService? = null
    private var acceptThread: Thread? = null
    private val omarchyWebSockets = OmarchyWebSocketHub(omarchyAdapter)

    @Synchronized
    fun start() {
        if (isRunning) return
        try {
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(if (lanMode) "0.0.0.0" else "127.0.0.1"), port))
            }
            val executor = Executors.newCachedThreadPool { task ->
                Thread(task, "ohm-api-worker").apply { isDaemon = true }
            }
            socket = server
            workers = executor
            boundPort = server.localPort
            isRunning = true
            acceptThread = Thread({ acceptConnections(server, executor) }, "ohm-api-accept").apply {
                isDaemon = true
                start()
            }
        } catch (_: Exception) {
            isRunning = false
            socket = null
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {
            // Already closed.
        }
        socket = null
        omarchyWebSockets.closeAll()
        workers?.shutdownNow()
        workers = null
        acceptThread = null
    }

    override fun close() = stop()

    fun broadcastClipboardChanged(text: String) {
        require(text.length <= ClipboardPayload.MAX_TEXT_LENGTH)
        omarchyWebSockets.broadcast(JSONObject().put("type", "clipboard_changed").put("text", text))
    }

    fun broadcastScreenStarted() {
        omarchyWebSockets.broadcast(JSONObject().put("type", "screen_started"))
    }

    fun broadcastScreenStopped() {
        omarchyWebSockets.broadcast(JSONObject().put("type", "screen_stopped"))
    }

    private fun acceptConnections(server: ServerSocket, executor: ExecutorService) {
        while (isRunning) {
            try {
                val client = server.accept()
                executor.execute { client.use(::handleConnection) }
            } catch (_: Exception) {
                if (isRunning) continue
            }
        }
    }

    private fun omarchyBodyLimit(target: String): Long =
        when (target.substringBefore('?')) {
            OmarchyRestRoute.FILE_UPLOAD.path -> MAX_FILE_UPLOAD_BYTES.toLong()
            OmarchyRestRoute.CLIPBOARD_PUT.path -> MAX_CLIPBOARD_REQUEST_BYTES.toLong()
            "/omarchy/notify" -> MAX_NOTIFICATION_BODY_BYTES.toLong()
            else -> MAX_JSON_BODY_BYTES.toLong()
        }

    private fun handleConnection(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()
        try {
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size != 3 || !parts[1].startsWith('/') || parts[2] !in SUPPORTED_HTTP_VERSIONS) {
                writeJson(output, 400, mapOf("error" to "bad_request"))
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]
            val headers = linkedMapOf<String, String>()
            var headerBytes = 0
            while (true) {
                val line = readLine(input) ?: throw EOFException("headers")
                if (line.isEmpty()) break
                headerBytes += line.length
                if (headers.size >= MAX_HEADER_COUNT || headerBytes > MAX_HEADER_BYTES) {
                    writeJson(output, 400, mapOf("error" to "headers_too_large"))
                    return
                }
                val colon = line.indexOf(':')
                if (colon <= 0) {
                    writeJson(output, 400, mapOf("error" to "bad_header"))
                    return
                }
                val name = line.substring(0, colon).trim().lowercase()
                if (!SAFE_HEADER_NAME.matches(name) || name in headers) {
                    writeJson(output, 400, mapOf("error" to "bad_header"))
                    return
                }
                headers[name] = line.substring(colon + 1).trim()
            }
            if (target == OmarchyWebSocketHub.PATH) {
                handleWebSocket(method, parts[2], headers, input, output)
                return
            }
            val transferEncoding = headers["transfer-encoding"]
            val contentLengthHeader = headers["content-length"]
            if (transferEncoding != null && contentLengthHeader != null) {
                writeJson(output, 400, mapOf("error" to "ambiguous_body_length"))
                return
            }
            if (transferEncoding != null && !transferEncoding.equals("chunked", ignoreCase = true)) {
                writeJson(output, 400, mapOf("error" to "unsupported_transfer_encoding"))
                return
            }
            val contentLength = if (contentLengthHeader == null) 0L else contentLengthHeader.toLongOrNull()
            if (contentLength == null || contentLength < 0) {
                writeJson(output, 400, mapOf("error" to "invalid_content_length"))
                return
            }
            if (target.startsWith("/omarchy/") && contentLength > omarchyBodyLimit(target)) {
                // Drain a bounded oversized body before closing. Closing a TCP
                // socket with unread request bytes can reset the connection and
                // discard the 413 response on Android/HttpURLConnection.
                client.soTimeout = REJECT_DRAIN_TIMEOUT_MS
                runCatching { discard(input, contentLength.coerceAtMost(MAX_REJECT_DRAIN_BYTES)) }
                writeJson(output, 413, mapOf("error" to "payload_too_large"))
                return
            }
            val body: InputStream = if (transferEncoding != null) {
                ChunkedInputStream(input)
            } else {
                LimitedInputStream(input, contentLength)
            }
            route(method, target, headers, body, output)
        } catch (error: Exception) {
            writeJson(output, 500, mapOf("error" to "server_error", "detail" to error.toString()))
        }
    }

    private fun handleWebSocket(
        method: String,
        httpVersion: String,
        headers: Map<String, String>,
        input: InputStream,
        output: java.io.OutputStream,
    ) {
        when (val validation = OmarchyWebSocketHub.validateUpgrade(method, httpVersion, headers)) {
            OmarchyWebSocketHub.UpgradeValidation.BAD_REQUEST ->
                writeJson(output, 400, mapOf("error" to "bad_websocket_upgrade"))
            OmarchyWebSocketHub.UpgradeValidation.BAD_VERSION ->
                writeResponse(
                    output,
                    426,
                    JSONObject(mapOf("error" to "unsupported_websocket_version")).toString().toByteArray(StandardCharsets.UTF_8),
                    "application/json; charset=utf-8",
                    mapOf("Sec-WebSocket-Version" to OmarchyWebSocketHub.VERSION),
                )
            is OmarchyWebSocketHub.UpgradeValidation.Valid -> {
                if (!omarchyWebSockets.isSupported) {
                    writeJson(output, 501, OmarchyApiResponse.unsupported("websocket").body)
                } else {
                    omarchyWebSockets.handle(validation.key, input, output)
                }
            }
        }
    }

    private fun route(
        method: String,
        target: String,
        headers: Map<String, String>,
        bodyStream: InputStream,
        output: java.io.OutputStream,
    ) {
        val question = target.indexOf('?')
        val path = if (question < 0) target else target.substring(0, question)
        val query = if (question < 0) "" else target.substring(question + 1)
        if (method == "OPTIONS") {
            writeResponse(output, 204, ByteArray(0), null)
            return
        }
        if (method == "GET" && path == "/health") {
            writeJson(output, 200, mapOf("ok" to true, "name" to "OhmLauncher"))
            return
        }
        if (method == "GET" && path == "/bins") {
            val handler = onListBins
            if (handler == null) writeJson(output, 501, mapOf("error" to "install_not_supported"))
            else writeJson(output, 200, mapOf("bins" to handler.handle()))
            return
        }
        if (path == "/omarchy/notify") {
            routeNotificationChannel(method, bodyStream, output)
            return
        }
        if (path.startsWith("/omarchy/")) {
            routeOmarchy(method, target, headers, bodyStream, output)
            return
        }
        if (method != "POST") {
            writeJson(output, 405, mapOf("error" to "method_not_allowed"))
            return
        }
        if (path == "/install-bin-raw") {
            installRaw(query, bodyStream, output)
            return
        }

        val body = parseObject(bodyStream)
        when (path) {
            "/command" -> {
                val command = body.stringOrEmpty("command")
                if (command.isEmpty()) {
                    writeJson(output, 400, mapOf("error" to "missing_command"))
                    return
                }
                val rawArgs = body.opt("args")
                val args = if (rawArgs is JSONArray) {
                    (0 until rawArgs.length()).map { rawArgs.get(it).toString() }
                } else {
                    null
                }
                writeJson(output, 200, onCommand.handle(command, args).toJson())
            }
            "/widget" -> {
                val source = body.stringOrEmpty("source")
                if (source.isEmpty()) {
                    writeJson(output, 400, mapOf("error" to "missing_source"))
                    return
                }
                val format = body.stringOrEmpty("format").ifEmpty { "json" }.lowercase()
                onInjectWidget.handle(source, format)
                writeJson(output, 200, mapOf("ok" to true))
            }
            "/ai" -> {
                val handler = onChat
                if (handler == null) {
                    writeJson(output, 501, mapOf("error" to "ai_not_configured"))
                    return
                }
                val prompt = body.stringOrEmpty("prompt")
                if (prompt.isEmpty()) {
                    writeJson(output, 400, mapOf("error" to "missing_prompt"))
                    return
                }
                val history = body.optJSONArray("history")?.let { raw ->
                    buildList {
                        repeat(raw.length()) { index ->
                            val item = raw.optJSONObject(index) ?: return@repeat
                            val role = item.opt("role") as? String ?: return@repeat
                            val content = item.opt("content") as? String ?: return@repeat
                            add(AiMessage(role, content))
                        }
                    }.ifEmpty { null }
                }
                val response = handler.handle(prompt, history)
                writeJson(
                    output,
                    200,
                    mapOf(
                        "text" to response.text,
                        "widgetSource" to response.widgetSource,
                        "widgetFormat" to response.widgetFormat,
                    ),
                )
            }
            "/install-bin" -> installBase64(body, output)
            "/bins" -> {
                val handler = onListBins
                if (handler == null) writeJson(output, 501, mapOf("error" to "install_not_supported"))
                else writeJson(output, 200, mapOf("bins" to handler.handle()))
            }
            "/uninstall-bin" -> {
                val handler = onUninstallBin
                if (handler == null) {
                    writeJson(output, 501, mapOf("error" to "install_not_supported"))
                    return
                }
                val name = body.stringOrEmpty("name").trim()
                if (!validBinName(name)) writeJson(output, 400, mapOf("error" to "invalid_name"))
                else writeJson(output, 200, handler.handle(name))
            }
            "/quake" -> {
                val handler = onQuake
                if (handler == null) {
                    writeJson(output, 501, mapOf("error" to "quake_not_supported"))
                    return
                }
                val open = if (body.opt("open") is Boolean) body.getBoolean("open") else true
                handler.handle(open)
                writeJson(output, 200, mapOf("ok" to true, "open" to open))
            }
            else -> writeJson(output, 404, mapOf("error" to "not_found"))
        }
    }

    private fun routeNotificationChannel(
        method: String,
        bodyStream: InputStream,
        output: java.io.OutputStream,
    ) {
        val channel = notificationChannel
        if (channel == null) {
            writeJson(output, 501, mapOf("error" to "notifications_not_supported"))
            return
        }
        when (method) {
            "GET" -> writeJson(
                output,
                200,
                mapOf("messages" to channel.load().map(OmarchyNotification::toJson)),
            )
            "POST" -> {
                val body = parseBoundedObject(bodyStream, MAX_NOTIFICATION_BODY_BYTES)
                if (body == null) {
                    writeJson(output, 400, mapOf("error" to "invalid_notification"))
                    return
                }
                val notification = runCatching { channel.receive(body) }.getOrElse {
                    writeJson(output, 400, mapOf("error" to (it.message ?: "invalid_notification")))
                    return
                }
                writeJson(output, 200, mapOf("ok" to true, "id" to notification.id))
            }
            else -> writeJson(output, 405, mapOf("error" to "method_not_allowed"))
        }
    }

    private fun routeOmarchy(
        method: String,
        target: String,
        headers: Map<String, String>,
        bodyStream: InputStream,
        output: java.io.OutputStream,
    ) {
        val directPath = target.substringBefore('?')
        if (method == "GET" && directPath == "/omarchy/screen/status") {
            val status = screenFrames?.status() ?: ScreenFrameStatus(0, 0, 0, 0)
            writeJson(
                output,
                200,
                mapOf("frames" to status.frames, "last" to status.last, "w" to status.width, "h" to status.height),
            )
            return
        }
        if (method == "GET" && directPath == "/omarchy/screen/frame") {
            val frame = screenFrames?.snapshot()
            if (frame == null) {
                writeJson(output, 404, mapOf("error" to "screen_frame_unavailable"))
            } else {
                writeResponse(
                    output,
                    200,
                    frame.jpeg,
                    "image/jpeg",
                    mapOf(
                        "Cache-Control" to "no-store",
                        "X-Screen-Width" to frame.width.toString(),
                        "X-Screen-Height" to frame.height.toString(),
                        "X-Screen-Sequence" to frame.sequence.toString(),
                    ),
                )
            }
            return
        }
        val preliminary = OmarchyRestRequest.fromUri(method, target)
        if (preliminary == null) {
            writeJson(output, 400, mapOf("error" to "invalid_request"))
            return
        }
        val route = preliminary.route
        if (route == null) {
            writeJson(output, 404, OmarchyApiResponse.notFound(preliminary.path).body)
            return
        }
        val normalized = if (route == OmarchyRestRoute.FILES_LIST && "path" !in preliminary.query) {
            preliminary.copy(query = mapOf("path" to "/sdcard"))
        } else {
            preliminary
        }
        val body = if (route in JSON_BODY_ROUTES) {
            parseBoundedObject(bodyStream, jsonBodyLimit(route)) ?: run {
                writeJson(output, 400, mapOf("error" to "invalid_json"))
                return
            }
        } else {
            JSONObject()
        }
        if (route == OmarchyRestRoute.CLIPBOARD_PUT && ClipboardPayload.parse(body) == null) {
            writeJson(output, 400, mapOf("error" to "invalid_clipboard"))
            return
        }
        val request = normalized.copy(body = body)
        val adapter = omarchyAdapter
        if (adapter == null) {
            writeJson(output, 501, OmarchyApiResponse.unsupported(route.capability ?: "discover").body)
            return
        }
        if (route == OmarchyRestRoute.FILE_UPLOAD) {
            val uploaded = parseMultipartFile(headers["content-type"], bodyStream)
            if (uploaded == null) {
                writeJson(output, 400, mapOf("error" to "bad_multipart"))
                return
            }
            val response = adapter.uploadFile(uploaded.first, uploaded.second)
            writeJson(output, response.statusCode, response.body)
            return
        }
        if (route == OmarchyRestRoute.FILE_DOWNLOAD) {
            val path = request.query["path"].orEmpty()
            if (path.isEmpty()) {
                writeJson(output, 400, mapOf("error" to "missing_path"))
                return
            }
            val download = adapter.downloadFile(path)
            if (download == null) {
                writeJson(output, 501, OmarchyApiResponse.unsupported("file").body)
                return
            }
            val responseHeaders = download.fileName
                ?.takeIf(OmarchyPathConfinement::isSafeFileName)
                ?.let { mapOf("Content-Disposition" to "attachment; filename=\"$it\"") }
                .orEmpty()
            writeResponse(output, 200, download.bytes, download.contentType, responseHeaders)
            return
        }
        val response = adapter.handle(request)
        writeJson(output, response.statusCode, response.body)
        if (response.statusCode in 200..299) {
            when (route) {
                OmarchyRestRoute.CLIPBOARD_PUT -> broadcastClipboardChanged(body.getString("text"))
                OmarchyRestRoute.SCREEN_START -> broadcastScreenStarted()
                OmarchyRestRoute.SCREEN_STOP -> broadcastScreenStopped()
                else -> Unit
            }
        }
    }

    private fun parseMultipartFile(contentType: String?, input: InputStream): Pair<String, ByteArray>? {
        if (contentType?.substringBefore(';')?.trim()?.equals("multipart/form-data", ignoreCase = true) != true) return null
        val boundaryMatch = contentType?.let { MULTIPART_BOUNDARY.find(it) } ?: return null
        val boundary = boundaryMatch.groups[1]?.value ?: boundaryMatch.groups[2]?.value ?: return null
        if (!MULTIPART_BOUNDARY_VALUE.matches(boundary)) return null
        val raw = readBounded(input, MAX_FILE_UPLOAD_BYTES) ?: return null
        val opening = "--$boundary\r\n".toByteArray(StandardCharsets.US_ASCII)
        if (!raw.startsWith(opening)) return null
        val headerEnd = raw.indexOf(HEADER_SEPARATOR, opening.size)
        if (headerEnd < 0 || headerEnd - opening.size > MAX_MULTIPART_HEADER_BYTES) return null
        val headerText = String(raw, opening.size, headerEnd - opening.size, StandardCharsets.ISO_8859_1)
        val disposition = headerText.split("\r\n")
            .firstOrNull { it.startsWith("Content-Disposition:", ignoreCase = true) }
            ?: return null
        if (!FORM_FILE_FIELD.containsMatchIn(disposition)) return null
        val fileName = FILE_NAME.find(disposition)?.groupValues?.get(1) ?: return null
        if (!OmarchyPathConfinement.isSafeFileName(fileName)) return null
        val contentStart = headerEnd + HEADER_SEPARATOR.size
        val closing = "\r\n--$boundary--".toByteArray(StandardCharsets.US_ASCII)
        val contentEnd = raw.indexOf(closing, contentStart)
        if (contentEnd < contentStart) return null
        val trailerEnd = contentEnd + closing.size
        if (trailerEnd != raw.size &&
            !(trailerEnd + 2 == raw.size && raw[trailerEnd] == '\r'.code.toByte() && raw[trailerEnd + 1] == '\n'.code.toByte())
        ) return null
        return fileName to raw.copyOfRange(contentStart, contentEnd)
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun jsonBodyLimit(route: OmarchyRestRoute): Int =
        if (route == OmarchyRestRoute.CLIPBOARD_PUT) MAX_CLIPBOARD_REQUEST_BYTES else MAX_JSON_BODY_BYTES

    private fun parseBoundedObject(input: InputStream, limit: Int): JSONObject? {
        return try {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) return null
                output.write(buffer, 0, count)
            }
            val text = output.toString(StandardCharsets.UTF_8.name())
            if (text.isEmpty()) JSONObject() else JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun installBase64(body: JSONObject, output: java.io.OutputStream) {
        val handler = onInstallBin
        if (handler == null) {
            writeJson(output, 501, mapOf("error" to "install_not_supported"))
            return
        }
        val name = body.stringOrEmpty("name").trim()
        val encoded = body.stringOrEmpty("base64").trim()
        if (!validBinName(name)) {
            writeJson(output, 400, mapOf("error" to "invalid_name"))
            return
        }
        if (encoded.isEmpty()) {
            writeJson(output, 400, mapOf("error" to "missing_base64"))
            return
        }
        val bytes = try {
            decodeBase64(encoded)
        } catch (_: IllegalArgumentException) {
            writeJson(output, 400, mapOf("error" to "bad_base64"))
            return
        }
        writeJson(output, 200, handler.handle(name, bytes))
    }

    private fun installRaw(query: String, body: InputStream, output: java.io.OutputStream) {
        val name = parseQuery(query)["name"].orEmpty().trim()
        if (!validBinName(name)) {
            writeJson(output, 400, mapOf("error" to "invalid_name"))
            return
        }
        val handler = onInstallBinRaw
        if (handler == null) {
            writeJson(output, 501, mapOf("error" to "install_not_supported"))
            return
        }
        try {
            writeJson(output, 200, handler.handle(name, body))
        } catch (error: Exception) {
            writeJson(output, 500, mapOf("error" to "install_failed", "detail" to error.toString()))
        }
    }

    private fun parseObject(input: InputStream): JSONObject = try {
        val text = input.readBytes().toString(StandardCharsets.UTF_8)
        if (text.isEmpty()) JSONObject() else JSONObject(text)
    } catch (_: Exception) {
        JSONObject()
    }

    private fun writeJson(output: java.io.OutputStream, code: Int, data: Map<String, Any?>) {
        writeResponse(output, code, JSONObject(data).toString().toByteArray(StandardCharsets.UTF_8), "application/json; charset=utf-8")
    }

    private fun writeJson(output: java.io.OutputStream, code: Int, data: JSONObject) {
        writeResponse(output, code, data.toString().toByteArray(StandardCharsets.UTF_8), "application/json; charset=utf-8")
    }

    private fun writeResponse(
        output: java.io.OutputStream,
        code: Int,
        body: ByteArray,
        contentType: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val safeContentType = contentType?.takeIf { SAFE_CONTENT_TYPE.matches(it) }
        val reason = when (code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            426 -> "Upgrade Required"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            else -> "Response"
        }
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, PUT, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            safeContentType?.let { append("Content-Type: $it\r\n") }
            for ((name, value) in extraHeaders) {
                require(SAFE_HEADER_NAME.matches(name) && value.none { it == '\r' || it == '\n' })
                append("$name: $value\r\n")
            }
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(head)
        output.write(body)
        output.flush()
    }

    companion object {
        private const val MAX_JSON_BODY_BYTES = 1_048_576
        private const val MAX_NOTIFICATION_BODY_BYTES = 16 * 1024

        private const val REJECT_DRAIN_TIMEOUT_MS = 1_000
        private const val MAX_REJECT_DRAIN_BYTES = 2L * 1024 * 1024
        private const val MAX_CLIPBOARD_REQUEST_BYTES = ClipboardPayload.MAX_TEXT_LENGTH * 4 + 65_536
        private const val MAX_FILE_UPLOAD_BYTES = 64 * 1024 * 1024
        private const val MAX_MULTIPART_HEADER_BYTES = 16 * 1024
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_HEADER_COUNT = 100
        private val SUPPORTED_HTTP_VERSIONS = setOf("HTTP/1.0", "HTTP/1.1")
        private val HEADER_SEPARATOR = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        private val MULTIPART_BOUNDARY = Regex("""(?i)(?:^|;)\s*boundary=(?:"([^"]+)"|([^;\s]+))""")
        private val MULTIPART_BOUNDARY_VALUE = Regex("""[0-9A-Za-z'()+_,./:=?-]{1,70}""")
        private val FORM_FILE_FIELD = Regex("""(?i)(?:^|;)\s*name\s*=\s*"file"(?:;|$)""")
        private val FILE_NAME = Regex("""(?i)(?:^|;)\s*filename\s*=\s*"([^"]*)"""")
        private val SAFE_CONTENT_TYPE = Regex("""[A-Za-z0-9!#$&^_.+*/=-]+(?:;\s*[A-Za-z0-9!#$&^_.+-]+=[A-Za-z0-9!#$&^_.+-]+)*""")
        private val SAFE_HEADER_NAME = Regex("[A-Za-z0-9-]+")
        private val JSON_BODY_ROUTES = setOf(
            OmarchyRestRoute.CLIPBOARD_PUT,
            OmarchyRestRoute.THEME_PUT,
            OmarchyRestRoute.INPUT,
            OmarchyRestRoute.SCREEN_START,
            OmarchyRestRoute.SCREEN_STOP,
        )
        private val binName = Regex("^[A-Za-z0-9._-]+$")

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

        private fun ByteArray.indexOf(needle: ByteArray, startIndex: Int): Int {
            if (needle.isEmpty()) return startIndex.coerceAtMost(size)
            for (index in startIndex..size - needle.size) {
                if (needle.indices.all { this[index + it] == needle[it] }) return index
            }
            return -1
        }

        internal fun validBinName(name: String): Boolean =
            name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && !name.contains("..") && binName.matches(name)

        private fun JSONObject.stringOrEmpty(key: String): String = opt(key) as? String ?: ""

        private fun readLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            var previous = -1
            while (true) {
                val current = input.read()
                if (current < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
                if (previous == '\r'.code && current == '\n'.code) {
                    val value = bytes.toByteArray()
                    return String(value, 0, (value.size - 1).coerceAtLeast(0), StandardCharsets.ISO_8859_1)
                }
                bytes.write(current)
                previous = current
                require(bytes.size() <= 64 * 1024) { "HTTP line too long" }
            }
        }

        private fun discard(input: InputStream, byteCount: Long) {
            var remaining = byteCount
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) return
                remaining -= count
            }
        }

        private fun parseQuery(query: String): Map<String, String> = query.split('&')
            .filter { it.isNotEmpty() }
            .associate { part ->
                val equals = part.indexOf('=')
                val key = if (equals < 0) part else part.substring(0, equals)
                val value = if (equals < 0) "" else part.substring(equals + 1)
                urlDecode(key) to urlDecode(value)
            }

        private fun urlDecode(value: String): String {
            val output = ByteArrayOutputStream()
            var index = 0
            while (index < value.length) {
                when (val char = value[index]) {
                    '+' -> output.write(' '.code)
                    '%' -> {
                        if (index + 2 >= value.length) return value
                        val decoded = value.substring(index + 1, index + 3).toIntOrNull(16) ?: return value
                        output.write(decoded)
                        index += 2
                    }
                    else -> output.write(char.code)
                }
                index++
            }
            return output.toByteArray().toString(StandardCharsets.UTF_8)
        }

        internal fun decodeBase64(value: String): ByteArray {
            val clean = value.filterNot(Char::isWhitespace).replace('-', '+').replace('_', '/')
            require(clean.length % 4 != 1)
            val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
            val output = ByteArrayOutputStream(padded.length * 3 / 4)
            for (offset in padded.indices step 4) {
                val group = padded.substring(offset, offset + 4)
                val padding = group.count { it == '=' }
                require(padding <= 2 && (padding == 0 || offset + 4 == padded.length))
                val values = IntArray(4) { index ->
                    val char = group[index]
                    if (char == '=') 0 else BASE64.indexOf(char).also { require(it >= 0) }
                }
                output.write((values[0] shl 2) or (values[1] shr 4))
                if (padding < 2) output.write((values[1] shl 4) or (values[2] shr 2))
                if (padding == 0) output.write((values[2] shl 6) or values[3])
            }
            return output.toByteArray()
        }

        private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    }

    private class ChunkedInputStream(
        private val delegate: InputStream,
    ) : InputStream() {
        private var chunkRemaining = 0L
        private var complete = false

        override fun read(): Int {
            if (!ensureChunk()) return -1
            val result = delegate.read()
            if (result < 0) throw EOFException("chunk data")
            chunkRemaining--
            return result
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!ensureChunk()) return -1
            val count = delegate.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
            if (count < 0) throw EOFException("chunk data")
            chunkRemaining -= count
            return count
        }

        private fun ensureChunk(): Boolean {
            if (complete) return false
            if (chunkRemaining > 0) return true
            if (chunkRemaining == 0L) {
                // Every chunk after the first is preceded by the previous chunk's CRLF.
                if (started) require(readLine(delegate).orEmpty().isEmpty()) { "invalid chunk delimiter" }
                started = true
            }
            val sizeLine = readLine(delegate) ?: throw EOFException("chunk size")
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                ?: throw IllegalArgumentException("invalid chunk size")
            require(size >= 0)
            if (size == 0L) {
                while (true) {
                    val trailer = readLine(delegate) ?: throw EOFException("chunk trailer")
                    if (trailer.isEmpty()) break
                }
                complete = true
                return false
            }
            chunkRemaining = size
            return true
        }

        private var started = false
    }

    private class LimitedInputStream(
        private val delegate: InputStream,
        length: Long,
    ) : InputStream() {
        private var remaining = length

        override fun read(): Int {
            if (remaining <= 0) return -1
            val result = delegate.read()
            if (result < 0) throw EOFException("request body")
            remaining--
            return result
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = delegate.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("request body")
            remaining -= count
            return count
        }
    }
}
