package cl.villagranquiroz.ohm_launcher

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLContext

/** Session-owned cancellation: closing the control link closes the payload listener and streams. */
internal class FluxTransferGate(private val authorized: () -> Boolean) {
    private val resources = linkedSetOf<Closeable>()
    @Volatile private var revoked = false

    fun checkNotRevoked() { check(!revoked) { "Flux control session closed" } }

    fun checkActive() {
        check(!revoked && authorized()) { "Flux control session is no longer authorized" }
    }

    fun <T : Closeable> track(resource: T): T {
        synchronized(resources) {
            if (!revoked) {
                resources.add(resource)
                return resource
            }
        }
        runCatching { resource.close() }
        error("Flux control session closed")
    }

    fun untrack(resource: Closeable) { synchronized(resources) { resources.remove(resource) } }

    fun revoke() {
        val closing = takeResources()
        closing.asReversed().forEach { runCatching { it.close() } }
    }

    fun revokeAsync() {
        val closing = takeResources()
        Thread({ closing.asReversed().forEach { runCatching { it.close() } } },
            "ohm-flux-revoke").apply { isDaemon = true }.start()
    }

    private fun takeResources(): List<Closeable> = synchronized(resources) {
        revoked = true
        resources.toList().also { resources.clear() }
    }
}

/** A single outbound KDE Connect v8 payload. Called on a background thread. */
internal object FluxFileTransfer {
    const val MAX_BYTES = 256L * 1024 * 1024
    private const val TRANSFER_TIMEOUT_MS = 10 * 60 * 1000L
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "ohm-flux-payload-deadline").apply { isDaemon = true }
    }

    /** Deadline begins before the provider query/open, off the shared IO executor. */
    fun submit(gate: FluxTransferGate, timeoutMs: Long = TRANSFER_TIMEOUT_MS,
               work: () -> Unit, finished: (Result<Unit>) -> Unit) {
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val scheduled = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<*>?>()
        val worker = Thread({
            val result = runCatching { gate.checkActive(); work(); gate.checkActive() }
            scheduled.get()?.cancel(false)
            if (completed.compareAndSet(false, true)) finished(result)
        }, "ohm-flux-file").apply { isDaemon = true }
        scheduled.set(deadlines.schedule({
            gate.revokeAsync()
            worker.interrupt()
            if (completed.compareAndSet(false, true))
                finished(Result.failure(java.util.concurrent.TimeoutException("Flux file transfer timed out")))
        }, timeoutMs, TimeUnit.MILLISECONDS))
        worker.start()
    }

    fun body(name: String, size: Long): JSONObject {
        require(size in 1..MAX_BYTES) { "File must be 1 byte to 256 MiB" }
        require(name.isNotBlank() && name != "." && name != ".." && name.toByteArray(Charsets.UTF_8).size <= 255 &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Unsafe file name" }
        return JSONObject().put("filename", name).put("open", false)
            .put("numberOfFiles", 1).put("totalPayloadSize", size)
    }

    /** A SAF document with a known, positive size; unknown sizes are rejected rather than guessed. */
    fun metadata(resolver: ContentResolver, uri: Uri, name: String?, size: Long?): Pair<String, Long> {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Only content documents may be shared" }
        var display = name
        var length = size
        if (display == null || length == null) {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    if (display == null) cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { display = cursor.getString(it) }
                    if (length == null) cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { length = cursor.getLong(it) }
                }
            }
        }
        val safeName = requireNotNull(display) { "Document name unavailable" }
        val knownSize = requireNotNull(length) { "Document size unavailable" }
        body(safeName, knownSize)
        return safeName to knownSize
    }

    /** Checks both premature EOF and unexpected trailing bytes, without buffering the file. */
    fun copyExact(input: InputStream, output: OutputStream, size: Long, checkActive: () -> Unit = {}) {
        require(size in 1..MAX_BYTES)
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            checkActive()
            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            checkActive()
            if (count < 0) throw EOFException("Document ended before declared size")
            if (count == 0) continue
            output.write(buffer, 0, count)
            remaining -= count
        }
        checkActive()
        require(input.read() == -1) { "Document grew beyond declared size" }
        checkActive()
        output.flush()
        checkActive()
    }

    /** The desktop fetches from our listener, acting as TLS client; no reverse tunnel is needed. */
    fun send(
        resolver: ContentResolver, uri: Uri, name: String?, size: Long?,
        gate: FluxTransferGate, expected: X509Certificate,
        tlsContext: () -> SSLContext, announce: (String) -> Unit,
    ) {
        gate.checkActive()
        val (fileName, length) = metadata(resolver, uri, name, size)
        gate.checkActive()
        val source = gate.track(requireNotNull(resolver.openInputStream(uri)) { "Cannot open document" })
        source.use { input ->
            try {
                val server = (1739..1764).firstNotNullOfOrNull { port ->
                    val candidate = ServerSocket()
                    try {
                        candidate.reuseAddress = true
                        candidate.bind(InetSocketAddress(port), 1)
                        candidate.soTimeout = 20_000
                        candidate
                    } catch (_: java.io.IOException) {
                        candidate.close()
                        null
                    }
                } ?: error("No free Flux payload port")
                gate.track(server).use { listener ->
                    try {
                        gate.checkActive()
                        announce(FluxWire.payloadPacket(FluxWire.SHARE, body(fileName, length), length, listener.localPort))
                        gate.checkActive()
                        val raw = gate.track(listener.accept())
                        raw.use { conn ->
                            try {
                                gate.checkActive()
                                conn.soTimeout = 15_000
                                val ssl = tlsContext().socketFactory.createSocket(conn,
                                    conn.inetAddress.hostAddress, conn.port, false) as SSLSocket
                                gate.track(ssl).use { secure ->
                                    try {
                                        gate.checkActive()
                                        secure.useClientMode = false
                                        secure.needClientAuth = true
                                        secure.enabledProtocols = arrayOf("TLSv1.2")
                                        secure.startHandshake()
                                        val cert = secure.session.peerCertificates.firstOrNull() as? X509Certificate
                                        check(cert != null && cert.encoded.contentEquals(expected.encoded)) {
                                            "Payload TLS peer is not the paired desktop"
                                        }
                                        gate.checkActive()
                                        secure.soTimeout = 30_000
                                        gate.checkActive()
                                        copyExact(input, secure.outputStream, length, gate::checkActive)
                                    } finally { gate.untrack(secure) }
                                }
                            } finally { gate.untrack(conn) }
                        }
                    } finally { gate.untrack(listener) }
                }
            } finally { gate.untrack(input) }
        }
    }
}
