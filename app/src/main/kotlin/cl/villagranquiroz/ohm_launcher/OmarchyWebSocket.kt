package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** RFC 6455 transport for the real-time Omarchy protocol. */
internal class OmarchyWebSocketHub(
    private val adapter: OmarchyApiAdapter?,
) {
    private val sessions = ConcurrentHashMap.newKeySet<Session>()

    val isSupported: Boolean
        get() = adapter != null

    fun handle(key: String, input: InputStream, output: OutputStream) {
        val activeAdapter = adapter ?: return
        val discover = activeAdapter.handle(OmarchyRestRequest("GET", OmarchyRestRoute.DISCOVER.path))
        require(discover.statusCode in 200..299) { "Omarchy discover failed with ${discover.statusCode}" }
        writeUpgrade(output, key)
        val session = Session(input, output, activeAdapter, ::broadcast, sessions::remove)
        try {
            session.activate(JSONObject(discover.body.toString()).put("type", "peer_hello")) { sessions += session }
            session.run()
        } finally {
            sessions -= session
        }
    }

    fun broadcast(event: JSONObject) {
        val bytes = event.toString().toByteArray(StandardCharsets.UTF_8)
        for (session in sessions) {
            if (!session.send(OPCODE_TEXT, bytes)) {
                sessions -= session
                session.closeTransport()
            }
        }
    }

    fun closeAll() {
        sessions.forEach { it.shutdown() }
        sessions.clear()
    }

    private class Session(
        private val input: InputStream,
        private val output: OutputStream,
        private val adapter: OmarchyApiAdapter,
        private val broadcast: (JSONObject) -> Unit,
        private val remove: (Session) -> Boolean,
    ) {
        private val writeLock = Any()
        @Volatile
        private var closed = false

        fun activate(hello: JSONObject, register: () -> Unit) = synchronized(writeLock) {
            register()
            writeFrame(output, OPCODE_TEXT, hello.toString().toByteArray(StandardCharsets.UTF_8))
        }

        fun run() {
            try {
                while (!closed) {
                    val frame = readFrame(input) ?: return
                    when (frame.opcode) {
                        OPCODE_TEXT -> handleText(decodeUtf8(frame.payload))
                        OPCODE_BINARY -> throw WebSocketProtocolException(CLOSE_UNSUPPORTED_DATA)
                        OPCODE_PING -> send(OPCODE_PONG, frame.payload)
                        OPCODE_PONG -> Unit
                        OPCODE_CLOSE -> {
                            validateClosePayload(frame.payload)
                            send(OPCODE_CLOSE, frame.payload)
                            closed = true
                        }
                        else -> throw WebSocketProtocolException(CLOSE_PROTOCOL_ERROR)
                    }
                }
            } catch (error: WebSocketProtocolException) {
                sendClose(error.closeCode)
            } catch (_: EOFException) {
                // A peer may drop the TCP connection without a closing frame.
            } catch (_: Exception) {
                sendClose(CLOSE_INTERNAL_ERROR)
            } finally {
                closed = true
                remove(this)
            }
        }

        private fun handleText(text: String) {
            val message = try {
                JSONObject(text)
            } catch (_: Exception) {
                throw WebSocketProtocolException(CLOSE_INVALID_PAYLOAD)
            }
            when (message.opt("type") as? String) {
                "ping" -> sendJson(JSONObject().put("type", "pong"))
                "input" -> {
                    val response = try {
                        adapter.handle(OmarchyRestRequest("POST", OmarchyRestRoute.INPUT.path, body = message))
                    } catch (error: Exception) {
                        sendJson(JSONObject().put("type", "input_result").put("ok", false).put("error", error.toString()))
                        return
                    }
                    val result = JSONObject(response.body.toString())
                    if (response.statusCode !in 200..299 && !result.has("ok")) result.put("ok", false)
                    result.put("type", "input_result")
                    sendJson(result)
                }
                "screen_start" -> dispatchScreen(OmarchyRestRoute.SCREEN_START, "screen_started", message)
                "screen_stop" -> dispatchScreen(OmarchyRestRoute.SCREEN_STOP, "screen_stopped", message)
            }
        }

        private fun dispatchScreen(route: OmarchyRestRoute, eventType: String, body: JSONObject) {
            val response = adapter.handle(OmarchyRestRequest("POST", route.path, body = body))
            if (response.statusCode in 200..299) broadcast(JSONObject().put("type", eventType))
        }

        fun sendJson(value: JSONObject): Boolean = send(OPCODE_TEXT, value.toString().toByteArray(StandardCharsets.UTF_8))

        fun send(opcode: Int, payload: ByteArray): Boolean = synchronized(writeLock) {
            if (closed && opcode != OPCODE_CLOSE) return@synchronized false
            try {
                writeFrame(output, opcode, payload)
                true
            } catch (_: Exception) {
                closed = true
                false
            }
        }

        fun shutdown() {
            sendClose(CLOSE_GOING_AWAY)
            closeTransport()
        }

        fun closeTransport() {
            closed = true
            runCatching { output.close() }
            runCatching { input.close() }
        }

        private fun sendClose(code: Int) {
            if (closed) return
            val payload = byteArrayOf((code ushr 8).toByte(), code.toByte())
            send(OPCODE_CLOSE, payload)
            closed = true
        }
    }

    companion object {
        const val PATH = "/omarchy/ws"
        const val VERSION = "13"
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_MESSAGE_BYTES = 1_048_576L
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_BINARY = 0x2
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA
        private const val CLOSE_PROTOCOL_ERROR = 1002
        private const val CLOSE_UNSUPPORTED_DATA = 1003
        private const val CLOSE_INVALID_PAYLOAD = 1007
        private const val CLOSE_TOO_LARGE = 1009
        private const val CLOSE_INTERNAL_ERROR = 1011
        private const val CLOSE_GOING_AWAY = 1001
        private val KEY_PATTERN = Regex("[A-Za-z0-9+/]{22}==")

        fun validateUpgrade(method: String, httpVersion: String, headers: Map<String, String>): UpgradeValidation {
            val isUpgradeRequest = method == "GET" && httpVersion == "HTTP/1.1" &&
                headers.hasToken("upgrade", "websocket") && headers.hasToken("connection", "upgrade")
            if (!isUpgradeRequest) return UpgradeValidation.BAD_REQUEST
            if (headers["sec-websocket-version"] != VERSION) return UpgradeValidation.BAD_VERSION
            val key = headers["sec-websocket-key"]
            val validKey = key != null && KEY_PATTERN.matches(key) &&
                runCatching { Base64.getDecoder().decode(key).size == 16 }.getOrDefault(false)
            return if (validKey) UpgradeValidation.Valid(key!!) else UpgradeValidation.BAD_REQUEST
        }

        private fun writeUpgrade(output: OutputStream, key: String) {
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(StandardCharsets.US_ASCII)),
            )
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(StandardCharsets.US_ASCII),
            )
            output.flush()
        }

        private fun readFrame(input: InputStream): Frame? {
            val first = input.read()
            if (first < 0) return null
            val second = input.read()
            if (second < 0) throw EOFException("WebSocket frame header")
            val fin = first and 0x80 != 0
            val rsv = first and 0x70
            val opcode = first and 0x0f
            val masked = second and 0x80 != 0
            val lengthMarker = second and 0x7f
            if (!fin || rsv != 0 || !masked) throw WebSocketProtocolException(CLOSE_PROTOCOL_ERROR)
            val control = opcode and 0x8 != 0
            if (control && lengthMarker > 125) throw WebSocketProtocolException(CLOSE_PROTOCOL_ERROR)
            val length = when (lengthMarker) {
                126 -> readUnsigned(input, 2).also {
                    if (it < 126) throw WebSocketProtocolException(CLOSE_PROTOCOL_ERROR)
                }
                127 -> readUnsigned(input, 8).also {
                    if (it <= 0xffff) throw WebSocketProtocolException(CLOSE_PROTOCOL_ERROR)
                }
                else -> lengthMarker.toLong()
            }
            if (!control && length > MAX_MESSAGE_BYTES) throw WebSocketProtocolException(CLOSE_TOO_LARGE)
            val mask = readExactly(input, 4)
            val payload = readExactly(input, length.toInt())
            for (index in payload.indices) payload[index] = payload[index].toInt().xor(mask[index % 4].toInt()).toByte()
            return Frame(opcode, payload)
        }

        private fun readUnsigned(input: InputStream, byteCount: Int): Long {
            var value = 0L
            repeat(byteCount) {
                val next = input.read()
                if (next < 0) throw EOFException("WebSocket frame length")
                if (it == 0 && byteCount == 8 && next and 0x80 != 0) {
                    throw WebSocketProtocolException(CLOSE_PROTOCOL_ERROR)
                }
                value = (value shl 8) or next.toLong()
            }
            return value
        }

        private fun readExactly(input: InputStream, length: Int): ByteArray {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(bytes, offset, length - offset)
                if (count < 0) throw EOFException("WebSocket frame payload")
                offset += count
            }
            return bytes
        }

        private fun decodeUtf8(payload: ByteArray): String = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload)).toString()
        } catch (_: Exception) {
            throw WebSocketProtocolException(CLOSE_INVALID_PAYLOAD)
        }

        private fun validateClosePayload(payload: ByteArray) {
            if (payload.size == 1) throw WebSocketProtocolException(CLOSE_PROTOCOL_ERROR)
            if (payload.size >= 2) {
                val code = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
                val validCode = code in 1000..1014 && code !in setOf(1004, 1005, 1006) || code in 3000..4999
                if (!validCode) throw WebSocketProtocolException(CLOSE_PROTOCOL_ERROR)
                decodeUtf8(payload.copyOfRange(2, payload.size))
            }
        }

        private fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
            output.write(0x80 or opcode)
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size <= 0xffff -> {
                    output.write(126)
                    output.write(payload.size ushr 8)
                    output.write(payload.size)
                }
                else -> {
                    output.write(127)
                    repeat(4) { output.write(0) }
                    output.write(payload.size ushr 24)
                    output.write(payload.size ushr 16)
                    output.write(payload.size ushr 8)
                    output.write(payload.size)
                }
            }
            output.write(payload)
            output.flush()
        }

        private fun Map<String, String>.hasToken(header: String, token: String): Boolean =
            this[header]?.split(',')?.any { it.trim().equals(token, ignoreCase = true) } == true
    }

    sealed class UpgradeValidation {
        data class Valid(val key: String) : UpgradeValidation()
        data object BAD_REQUEST : UpgradeValidation()
        data object BAD_VERSION : UpgradeValidation()
    }

    private data class Frame(val opcode: Int, val payload: ByteArray)
    private class WebSocketProtocolException(val closeCode: Int) : Exception()
}
