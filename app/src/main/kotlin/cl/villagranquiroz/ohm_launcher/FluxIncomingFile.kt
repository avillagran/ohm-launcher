package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.json.JSONTokener
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** One single-file payload, either direct-port or reverse tunnel; updates are not offers. */
internal data class FluxIncomingFile(val name: String, val size: Long, val port: Int = 0,
                                     val tunnel: String? = null) {
    init { require((port > 0) != (tunnel != null)) { "Exactly one payload endpoint is required" } }
}

internal object FluxIncomingFileGate {
    fun deliver(line: String, paired: Boolean, live: Boolean, onOffer: (FluxIncomingFile) -> Unit) {
        if (!paired || !live) return
        FluxIncomingFileParser.parse(line)?.let(onOffer)
    }
}

internal object FluxIncomingFileParser {
    private const val MAX_PACKET = 8192
    private const val MAX_BYTES = 256L * 1024 * 1024

    fun parse(line: String): FluxIncomingFile? {
        if (line.toByteArray(Charsets.UTF_8).size > MAX_PACKET) return null
        return runCatching {
            val tokens = JSONTokener(line)
            val packet = tokens.nextValue() as? JSONObject ?: return null
            if (tokens.nextClean() != '\u0000' || packet.opt("id") !is Number ||
                packet.optString("type") != "kdeconnect.share.request") return null
            val body = packet.opt("body") as? JSONObject ?: return null
            val transfer = packet.opt("payloadTransferInfo") as? JSONObject ?: return null
            if (packet.keys().asSequence().any { it !in setOf("id", "type", "body", "payloadSize", "payloadTransferInfo") } ||
                body.keys().asSequence().any { it !in setOf("filename", "open", "numberOfFiles", "totalPayloadSize", "lastModified") } ||
                transfer.keys().asSequence().toList().let { keys ->
                    keys.size != 1 || keys[0] !in setOf("port", "tunnel") }) return null
            val name = body.opt("filename") as? String ?: return null
            val size = positiveInteger(packet.opt("payloadSize")) ?: return null
            val count = positiveInteger(body.opt("numberOfFiles")) ?: return null
            val total = positiveInteger(body.opt("totalPayloadSize")) ?: return null
            val tunnel = transfer.opt("tunnel") as? String
            val port = if (tunnel == null) positiveInteger(transfer.opt("port")) ?: return null else 0L
            if (name.isBlank() || name == "." || name == ".." ||
                !Charsets.UTF_8.newEncoder().canEncode(name) ||
                name.toByteArray(Charsets.UTF_8).size > 255 ||
                name.any { it == '/' || it == '\\' || it.isISOControl() } ||
                body.opt("open") != false || count != 1L || size != total ||
                size > MAX_BYTES || (tunnel == null && port !in 1739..1764) ||
                (tunnel != null && !tunnel.matches(Regex("[0-9a-f]{24}")))) return null
            if (body.has("lastModified") && positiveInteger(body.opt("lastModified")) == null) return null
            FluxIncomingFile(name, size, port.toInt(), tunnel)
        }.getOrNull()
    }

    private fun positiveInteger(value: Any?): Long? {
        if (value !is Number || !value.toString().matches(Regex("[0-9]{1,18}"))) return null
        return value.toString().toLongOrNull()?.takeIf { it > 0 }
    }
}

/** Per-control-session receiver. Call offer only from authenticated control dispatch, then
 * accept(token) on an IO thread after user confirmation. Never auto-opens or exports a file.
 * The caller must revoke synchronously on Forget, replacement, close, and Activity destruction.
 */
internal class FluxIncomingFileReceiver(
    private val privateCacheDir: File,
    private val session: Any,
    private val current: () -> Any?,
    private val authorized: () -> Boolean,
    private val address: InetAddress,
    expectedCertificateDer: ByteArray,
    private val tlsContext: () -> SSLContext,
    private val clockNanos: () -> Long = System::nanoTime,
    private val socket: () -> Socket = ::Socket,
    private val offerTimeoutNanos: Long = TimeUnit.SECONDS.toNanos(20),
    private val listenerFactory: (Int) -> ServerSocket = { port -> ServerSocket(port, 1) },
    private val sendTunnel: (String, Int) -> Unit = { _, _ -> error("Tunnel sender unavailable") },
) {
    init { require(offerTimeoutNanos > 0) }
    private val expectedDer = expectedCertificateDer.clone()
    private val lock = Any()
    private var revoked = false
    private var pending: Offer? = null
    private var active: Socket? = null
    private var secure: SSLSocket? = null
    private var listener: ServerSocket? = null
    private var activeToken: String? = null
    private var partial: File? = null
    private var timeout: ScheduledFuture<*>? = null
    private var rawCloseQueued = false
    private var secureCloseQueued = false
    private var listenerCloseQueued = false
    private var deletionQueued = false
    private var cleanupOutstanding = 0
    private var acceptFinished = false
    private data class Offer(val token: String, val file: FluxIncomingFile, val deadline: Long)

    private fun checkLive() {
        check(!revoked && current() === session && authorized()) { "Flux control session changed or unpaired" }
    }

    /** Deadline begins when the control frame arrives, not when confirmation is shown. */
    fun offer(file: FluxIncomingFile): String = synchronized(lock) {
        checkLive()
        check(activeToken == null && cleanupOutstanding == 0 && !acceptFinished) {
            "A payload or its cleanup is already in progress"
        }
        timeout?.cancel(false)
        val offer = Offer(UUID.randomUUID().toString(), file, clockNanos() + offerTimeoutNanos)
        pending = offer
        timeout = deadlines.schedule({ expire(offer.token) }, offerTimeoutNanos, TimeUnit.NANOSECONDS)
        offer.token
    }

    /** Returns a private staged file; caller owns deletion after successful handoff to SAF. */
    fun accept(token: String): File {
        val offer = synchronized(lock) {
            checkLive()
            val found = pending
            check(found != null && found.token == token && clockNanos() - found.deadline < 0) { "File offer expired" }
            pending = null // one-use even if the dial or handshake fails
            check(acceptSlots.tryAcquire()) { "Incoming file cleanup capacity exhausted" }
            activeToken = token
            found
        }
        val raw = try {
            if (offer.file.tunnel != null) {
                val bound = (1739..1764).firstNotNullOfOrNull { port ->
                    try { listenerFactory(port) } catch (_: BindException) { null }
                } ?: throw IllegalStateException("No Flux tunnel port available")
                synchronized(lock) { listener = bound; checkLive() }
                val remaining = offer.deadline - clockNanos()
                check(remaining > 0) { "File offer expired" }
                bound.soTimeout = minOf(20_000L, maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining))).toInt()
                checkLive()
                sendTunnel(offer.file.tunnel, bound.localPort)
                checkLive()
                val accepted = bound.accept()
                synchronized(lock) { active = accepted; queueListenerClose(); checkLive() }
                check(accepted.inetAddress == address) { "Tunnel peer address changed" }
                accepted
            } else socket()
        } catch (e: Exception) {
            synchronized(lock) {
                queueListenerClose()
                queueRawClose()
                if (activeToken == token) activeToken = null
                acceptFinished = true
                finishCleanupIfPossible()
            }
            throw e
        }
        try {
            synchronized(lock) { active = raw; checkLive() }
            // Register before connect so Forget or timeout can close a blocked dial.
            checkLive()
            val left = offer.deadline - clockNanos()
            check(left > 0) { "File offer expired" }
            if (offer.file.tunnel == null) raw.connect(InetSocketAddress(address, offer.file.port),
                minOf(10_000L, maxOf(1L, TimeUnit.NANOSECONDS.toMillis(left))).toInt())
            checkLive()
            val ssl = tlsContext().socketFactory.createSocket(raw, address.hostAddress,
                if (offer.file.tunnel == null) offer.file.port else raw.port, false) as SSLSocket
            try {
                synchronized(lock) { secure = ssl; checkLive() }
                ssl.useClientMode = offer.file.tunnel == null
                ssl.needClientAuth = offer.file.tunnel != null
                ssl.enabledProtocols = arrayOf("TLSv1.2")
                ssl.soTimeout = 15_000
                ssl.startHandshake()
                val peer = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
                check(peer != null && peer.encoded.contentEquals(expectedDer)) { "Payload desktop certificate changed" }
                checkLive()
                // Desktop accepts the secondary socket within 20s; large transfers get their own cap.
                synchronized(lock) {
                    check(clockNanos() - offer.deadline < 0) { "File offer expired" }
                    checkLive()
                    timeout?.cancel(false)
                    timeout = deadlines.schedule({ revoke() }, 10, TimeUnit.MINUTES)
                }
                val staged = File.createTempFile("flux-incoming-", ".part", privateCacheDir)
                try {
                    synchronized(lock) { partial = staged; checkLive() }
                    FileOutputStream(staged).use { out ->
                        ssl.soTimeout = 30_000
                        copyExact(ssl.inputStream, out, offer.file.size, ::checkLive)
                        out.fd.sync()
                    }
                    synchronized(lock) {
                        checkLive()
                        partial = null
                    }
                    return staged
                } catch (e: Exception) {
                    staged.delete()
                    synchronized(lock) { if (partial === staged) partial = null }
                    throw e
                }
            } finally {
                synchronized(lock) { queueSecureClose() }
            }
        } finally {
            synchronized(lock) {
                queueRawClose()
                queueListenerClose()
                if (activeToken == token) activeToken = null
                timeout?.cancel(false)
                timeout = null
                acceptFinished = true
                finishCleanupIfPossible()
            }
        }
    }

    // Each admitted accept reserves at most four cleanup jobs (listener, raw, TLS, partial).
    // Submit while holding lock so completion cannot release its permit before revocation
    // has registered every job. No close or delete executes on the caller/deadline thread.
    private fun queueCleanup(action: () -> Unit) {
        cleanupOutstanding++
        cleanup.execute {
            try { runCatching { action() } }
            finally {
                synchronized(lock) {
                    cleanupOutstanding--
                    finishCleanupIfPossible()
                }
            }
        }
    }

    private fun queueRawClose() {
        if (!rawCloseQueued) active?.let { raw ->
            rawCloseQueued = true
            queueCleanup { raw.close() }
        }
    }

    private fun queueSecureClose() {
        if (!secureCloseQueued) secure?.let { ssl ->
            secureCloseQueued = true
            queueCleanup { ssl.close() }
        }
    }

    private fun queueListenerClose() {
        if (!listenerCloseQueued) listener?.let { bound ->
            listenerCloseQueued = true
            queueCleanup { bound.close() }
        }
    }

    private fun finishCleanupIfPossible() {
        if (acceptFinished && cleanupOutstanding == 0) {
            active = null
            secure = null
            listener = null
            partial = null
            rawCloseQueued = false
            secureCloseQueued = false
            listenerCloseQueued = false
            deletionQueued = false
            acceptFinished = false
            acceptSlots.release()
        }
    }

    /** Withdraw authority synchronously; close sockets independently to unblock IO workers. */
    fun revoke() {
        synchronized(lock) {
            if (revoked) return
            revoked = true
            pending = null
            timeout?.cancel(false)
            timeout = null
            queueRawClose()
            queueSecureClose()
            queueListenerClose()
            if (!deletionQueued) partial?.let { staged ->
                deletionQueued = true
                queueCleanup { staged.delete() }
            }
        }
    }

    private fun expire(token: String) {
        val shouldRevoke = synchronized(lock) {
            if (pending?.token == token) {
                pending = null
                timeout = null
                false
            } else activeToken == token
        }
        if (shouldRevoke) revoke()
    }

    companion object {
        private const val MAX_ACTIVE_CLEANUPS = 4
        private val acceptSlots = Semaphore(MAX_ACTIVE_CLEANUPS)
        private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "ohm-flux-incoming-deadline").apply { isDaemon = true }
        }
        private val cleanup = ThreadPoolExecutor(12, 12, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(12)) { task ->
            Thread(task, "ohm-flux-incoming-close").apply { isDaemon = true }
        }
        internal val cleanupPendingJobs: Int get() = cleanup.queue.size
        internal val activeCleanupReservations: Int get() = MAX_ACTIVE_CLEANUPS - acceptSlots.availablePermits()

        fun copyExact(input: InputStream, output: OutputStream, size: Long, check: () -> Unit) {
            require(size in 1..256L * 1024 * 1024)
            var remaining = size
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                check()
                val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                check()
                if (n < 0) throw EOFException("Payload ended before declared length")
                if (n == 0) continue
                output.write(buffer, 0, n)
                remaining -= n
            }
            check()
            require(input.read() == -1) { "Payload exceeded declared length" }
            check()
            output.flush()
        }
    }
}
