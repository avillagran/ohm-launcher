package cl.villagranquiroz.ohm_launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import org.json.JSONObject

/** Independent wire subset: one control packet and one raw H.264 Annex-B TLS stream. */
internal object FluxScreenProtocol {
    const val TYPE = "flux.screen"
    const val FPS = 15
    enum class Reply { Live, Stop, Error }

    fun start(port: Int, width: Int, height: Int): String {
        require(port in 1739..1764 && width > 0 && height > 0)
        return FluxWire.packet(TYPE, JSONObject().put("state", "start").put("port", port)
            .put("width", width).put("height", height).put("codec", "h264"))
    }
    fun stop(): String = FluxWire.packet(TYPE, JSONObject().put("state", "stop"))
    fun reply(type: String, body: JSONObject): Reply? = if (type != TYPE) null else when (body.optString("state")) {
        "live" -> Reply.Live
        "stop" -> Reply.Stop
        "error" -> Reply.Error
        else -> null
    }
    fun authorized(port: Int, paired: Boolean, connected: Boolean, pinnedCertificateMatches: Boolean): Boolean =
        port in 1739..1764 && paired && connected && pinnedCertificateMatches
    fun samePeer(controlCert: ByteArray, storedPin: ByteArray?, streamCert: ByteArray): Boolean =
        controlCert.contentEquals(streamCert) && storedPin?.contentEquals(streamCert) == true

    /** Keep aspect ratio, cap longest edge, align both edges for AVC hardware. */
    fun fit(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = minOf(1.0, 1080.0 / maxOf(width, height))
        fun edge(value: Int) = maxOf(16, (value * scale).toInt() / 16 * 16)
        return edge(width) to edge(height)
    }

    /** Encoders may output Annex-B or length-prefixed NALs; normalize both. */
    class Framer {
        private var parameterSets: ByteArray? = null
        private var started = false
        fun config(data: ByteArray) { parameterSets = normalize(data) }
        fun frame(data: ByteArray, keyFrame: Boolean): ByteArray? {
            val normalized = normalize(data)
            val types = nalTypes(normalized)
            val idr = keyFrame || 5 in types
            if (!idr && !started) return null
            if (idr) started = true
            return if (idr && !(7 in types && 8 in types)) parameterSets?.plus(normalized) ?: normalized else normalized
        }
        private fun nalTypes(data: ByteArray): Set<Int> {
            val types = mutableSetOf<Int>()
            var i = 0
            while (i + 3 < data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    (data[i + 2] == 1.toByte() || (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()))) {
                    val nal = i + if (data[i + 2] == 1.toByte()) 3 else 4
                    if (nal < data.size) types.add(data[nal].toInt() and 31)
                    i = nal
                } else i++
            }
            return types
        }
        private fun normalize(data: ByteArray): ByteArray {
            if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                (data[2] == 1.toByte() || (data.size >= 4 && data[2] == 0.toByte() && data[3] == 1.toByte()))) return data
            // AVCC: repeated four-byte big-endian length and NAL bytes.
            val result = java.io.ByteArrayOutputStream()
            var at = 0
            while (at + 4 <= data.size) {
                val length = ((data[at].toInt() and 255) shl 24) or ((data[at + 1].toInt() and 255) shl 16) or
                    ((data[at + 2].toInt() and 255) shl 8) or (data[at + 3].toInt() and 255)
                if (length <= 0 || length > data.size - at - 4) break
                result.write(byteArrayOf(0, 0, 0, 1))
                result.write(data, at + 4, length)
                at += length + 4
            }
            return if (at == data.size && at > 0) result.toByteArray() else byteArrayOf(0, 0, 0, 1) + data
        }
    }
}

/** Mutual TLS secondary channel: accept only the certificate pinned on the control link. */
internal object FluxScreenTransport {
    fun accept(raw: Socket, context: SSLContext, controlCert: ByteArray, storedPin: ByteArray?): SSLSocket {
        try {
            raw.soTimeout = 15_000
            raw.tcpNoDelay = true
            val tls = context.socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
            try {
                tls.useClientMode = false
                tls.needClientAuth = true
                tls.enabledProtocols = arrayOf("TLSv1.2")
                tls.startHandshake()
                val cert = tls.session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate
                if (cert == null || !FluxScreenProtocol.samePeer(controlCert, storedPin, cert.encoded))
                    throw SecurityException("Screen stream peer is not the paired desktop")
                return tls
            } catch (error: Exception) { tls.close(); throw error }
        } catch (error: Exception) { raw.close(); throw error }
    }
}

/** A live mapping check is mandatory in addition to the certificate and socket state. */
internal class FluxScreenAuthorization<S : Any>(
    private val session: S,
    private val currentSession: () -> S?,
    private val pairedOpen: () -> Boolean,
) {
    fun active(): Boolean = pairedOpen() && currentSession() === session
}

/** Never wait for a stalled TLS close; cap even permanently blocked daemon close workers. */
internal object FluxScreenSocketCloser {
    private val slots = Semaphore(4)

    /** False means at least one close could not be scheduled; callers must revoke the stream. */
    fun close(vararg sockets: Closeable?): Boolean {
        var scheduled = true
        sockets.filterNotNull().forEach { socket ->
            if (!slots.tryAcquire()) {
                scheduled = false
            } else {
                try {
                    Thread({
                        try { runCatching { socket.close() } }
                        finally { slots.release() }
                    }, "ohm-flux-screen-socket-close").apply { isDaemon = true; start() }
                } catch (error: Throwable) {
                    slots.release()
                    scheduled = false
                }
            }
        }
        return scheduled
    }
}

/** One Activity-owned, explicitly consented mirror. Never routes remote input. */
internal class FluxScreenMirror(
    private val context: Context,
    private val identity: FluxIdentity,
    private val session: FluxSession,
    private val currentSession: () -> FluxSession? = { null },
    private val onEnded: (String?) -> Unit = {},
) : Closeable {
    private val authority = FluxScreenAuthorization(session, currentSession) {
        !closed.get() && stopId?.let(FluxScreenStops::isCurrent) != false &&
            session.paired && session.isOpen()
    }
    private val main = Handler(Looper.getMainLooper())
    private val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val closed = AtomicBoolean(false)
    @Volatile private var stopId: Long? = null
    private var foregroundRequestId: Long? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var surface: android.view.Surface? = null
    private var output: SSLSocket? = null
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var connecting: Socket? = null
    private var drain: Thread? = null
    private var started = false
    private var foregroundRequested = false
    @Volatile private var announced = false

    fun consentIntent(): Intent {
        check(!BuildConfig.PLAY_STORE_DISTRIBUTION && authority.active())
        check(Looper.myLooper() == Looper.getMainLooper())
        if (stopId == null) stopId = FluxScreenStops.register(this, ::close)
        check(stopId != null) { "Another Flux screen capture is active" }
        return projectionManager.createScreenCaptureIntent()
    }

    /** Invoke only with the result of this instance's OS capture-consent intent, on the main thread. */
    fun start(resultCode: Int, consent: Intent): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (started || BuildConfig.PLAY_STORE_DISTRIBUTION ||
            resultCode != Activity.RESULT_OK || !authority.active()) return false
        val captureId = stopId ?: return false
        if (!FluxScreenStops.isCurrent(captureId)) return false
        started = true
        var requestId = 0L
        val request = ScreenCaptureForegroundRequests.createRequest {
            if (foregroundRequestId != requestId || !authority.active()) {
                if (foregroundRequestId == requestId) end(null)
                return@createRequest
            }
            foregroundRequestId = null
            runCatching { begin(resultCode, consent) }.onFailure { end(it.message) }
        }
        requestId = request.id
        foregroundRequestId = requestId
        return runCatching {
            androidx.core.content.ContextCompat.startForegroundService(context,
                Intent(context, ScreenCaptureService::class.java)
                    .putExtra(ScreenCaptureService.EXTRA_TARGET, "flux")
                    .putExtra(ScreenCaptureService.EXTRA_FLUX_STOP_ID, captureId)
                    .putExtra(ScreenCaptureForegroundRequests.EXTRA_REQUEST_ID, requestId))
            foregroundRequested = true
            main.postDelayed({ if (foregroundRequestId == requestId) end("Screen foreground service did not start") }, 3000)
            true
        }.getOrElse { end(it.message); false }
    }

    private fun begin(resultCode: Int, consent: Intent) {
        check(authority.active())
        val capture = projectionManager.getMediaProjection(resultCode, Intent(consent))
            ?: error("Android did not grant capture")
        if (!authority.active()) { capture.stop(); error("Flux screen session replaced") }
        projection = capture
        capture.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { end(null) } }
        }, main)
        val dimensions = Point()
        @Suppress("DEPRECATION")
        displayManager.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(dimensions)
        val (width, height) = FluxScreenProtocol.fit(dimensions.x, dimensions.y)
        Thread({
            try {
                val server = (1739..1764).firstNotNullOfOrNull { port ->
                    val candidate = ServerSocket()
                    try {
                        candidate.bind(InetSocketAddress(port), 1)
                        candidate.soTimeout = 10_000
                        candidate
                    } catch (_: java.io.IOException) {
                        if (!FluxScreenSocketCloser.close(candidate)) error("Screen socket close capacity exhausted")
                        null
                    }
                } ?: error("No free Flux screen port")
                server.use { bound ->
                    listener = bound
                    check(authority.active())
                    session.sendScreen(FluxScreenProtocol.start(bound.localPort, width, height), authority::active)
                    announced = true
                    check(authority.active())
                    bound.accept().use { raw ->
                        connecting = raw
                        check(authority.active())
                        FluxScreenTransport.accept(raw, identity.tlsContext(), session.certificate.encoded,
                            identity.pinned(session.id)).use { tls ->
                            check(FluxScreenProtocol.authorized(bound.localPort, session.paired, session.isOpen(), true)
                                && authority.active())
                            listener = null
                            output = tls
                            connecting = null
                            if (!authority.active()) return@use
                            main.post {
                                if (authority.active()) runCatching { beginEncoder(tls, width, height) }.onFailure { end(it.message) }
                                else end(null)
                            }
                            // The encoder owns writes; this thread waits for the remote stream closure.
                            tls.soTimeout = 1000
                            while (authority.active()) {
                                try { if (tls.inputStream.read() < 0) break }
                                catch (_: java.net.SocketTimeoutException) { /* Poll control link and stop state. */ }
                            }
                        }
                    }
                }
                main.post { end(null) }
            } catch (e: Exception) {
                if (!closed.get()) main.post { end(e.message) }
            } finally { listener = null; connecting = null }
        }, "ohm-flux-screen-accept").apply { isDaemon = true }.start()
    }

    private fun beginEncoder(tls: SSLSocket, width: Int, height: Int) {
        if (!authority.active()) { end(null); return }
        val projection = projection ?: return
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = encoder
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, maxOf(2_000_000, width * height * 4))
            setInteger(MediaFormat.KEY_FRAME_RATE, FluxScreenProtocol.FPS)
            if (android.os.Build.VERSION.SDK_INT >= 29)
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, FluxScreenProtocol.FPS.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        this.surface = surface
        if (!authority.active()) { end(null); return }
        encoder.start()
        if (!authority.active()) { end(null); return }
        display = projection.createVirtualDisplay("Ohm Flux mirror", width, height,
            context.resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, main)
        if (!authority.active()) { end(null); return }
        encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        drain = Thread({
            val info = MediaCodec.BufferInfo()
            val framer = FluxScreenProtocol.Framer()
            try {
                while (authority.active()) {
                    val index = encoder.dequeueOutputBuffer(info, 10_000)
                    if (index < 0) continue
                    val buffer = encoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) framer.config(bytes)
                        else framer.frame(bytes, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                            ?.let { if (authority.active()) tls.outputStream.write(it) }
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            } catch (e: Exception) {
                if (!closed.get()) { Log.i("OhmFluxScreen", "Screen stream ended: ${e.message}"); main.post { end(e.message) } }
            } finally {
                if (!authority.active()) main.post { end(null) }
            }
        }, "ohm-flux-screen-avc").apply { isDaemon = true; start() }
    }

    fun onReply(reply: FluxScreenProtocol.Reply) {
        if (reply != FluxScreenProtocol.Reply.Live) main.post { end(if (reply == FluxScreenProtocol.Reply.Error) "Desktop rejected screen" else null) }
    }

    private fun end(reason: String?) {
        if (!closed.compareAndSet(false, true)) return
        val captureId = stopId
        stopId = null
        captureId?.let { FluxScreenStops.unregister(this, it) }
        foregroundRequestId?.let(ScreenCaptureForegroundRequests::cancel)
        foregroundRequestId = null
        val socketsScheduled = FluxScreenSocketCloser.close(listener, connecting, output)
        listener = null
        connecting = null
        runCatching { display?.release() }
        display = null
        output = null
        val worker = drain
        drain = null
        // Do not block Android's main thread on a potentially blocked TLS writer.
        Thread({
            if (worker !== Thread.currentThread()) runCatching { worker?.join(500) }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { surface?.release() }
            surface = null
            codec = null
        }, "ohm-flux-screen-cleanup").apply { isDaemon = true; start() }
        val active = projection
        projection = null
        runCatching { active?.stop() }
        if (announced && captureId != null) Thread({ runCatching {
            session.sendScreen(FluxScreenProtocol.stop()) { FluxScreenStops.canSendStop(captureId) }
        } },
            "ohm-flux-screen-stop").apply { isDaemon = true; start() }
        if (foregroundRequested && captureId != null) runCatching {
            context.startService(ScreenCaptureService.fluxStopIntent(context, captureId, ended = true))
        }
        onEnded(reason ?: if (!socketsScheduled) "Screen socket close capacity exhausted" else null)
    }

    override fun close() { if (Looper.myLooper() == Looper.getMainLooper()) end(null) else main.post { end(null) } }
}
