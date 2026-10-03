package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** Direct edition only: ephemeral foreground TCP responder and mDNS advertisement. */
internal class FluxLanResponder(
    context: Context,
    private val onConnected: (FluxSession) -> Unit,
    private val onPairRequest: (FluxSession, Long) -> Unit,
    private val onDisconnected: (FluxSession) -> Unit,
    private val onUnpair: (FluxSession) -> Unit,
    private val onScreen: (FluxSession, FluxScreenProtocol.Reply) -> Unit = { _, _ -> },
    private val onThemeCatalog: (FluxSession, FluxThemeCatalog) -> Unit = { _, _ -> },
    private val onMedia: (FluxSession, org.json.JSONObject, Long) -> Unit = { _, _, _ -> },
    private val onShare: (FluxSession, FluxIncomingShare) -> Unit = { _, _ -> },
    private val onFileOffer: (FluxSession, FluxIncomingFile) -> Unit = { _, _ -> },
    private val onInputState: (FluxSession, FluxWire.InputState) -> Unit = { _, _ -> },
    private val onThemeSelected: (FluxSession, FluxThemeSelection.Result) -> Unit = { _, _ -> },
) : Closeable {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val identity = FluxIdentity(context.applicationContext)
    private val active = mutableSetOf<FluxSession>()
    private val handshakes = mutableSetOf<Socket>() // guarded by active, including shutdown
    private val count = AtomicInteger()
    private var server: ServerSocket? = null
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()
    @Volatile private var running = false
    @Volatile private var closed = false

    fun start() {
        check(!running && !closed)
        val bound = (1716..1764).firstNotNullOfOrNull { port ->
            val candidate = ServerSocket()
            try {
                candidate.reuseAddress = true
                candidate.bind(InetSocketAddress(port))
                candidate
            } catch (_: Exception) { candidate.close(); null }
        } ?: error("No available Flux TCP port")
        synchronized(active) {
            if (closed) { bound.close(); error("Flux responder closed") }
            server = bound
            running = true
        }
        Thread({
            while (running) {
                val socket = try { bound.accept() } catch (_: Exception) { break }
                if (count.incrementAndGet() > 8) { count.decrementAndGet(); socket.close(); continue }
                val admitted = synchronized(active) {
                    if (!running) false else { handshakes.add(socket); true }
                }
                if (!admitted) { count.decrementAndGet(); socket.close(); break }
                Thread({ handle(socket) }, "ohm-flux-peer").apply { isDaemon = true }.start()
            }
        }, "ohm-flux-listen").apply { isDaemon = true }.start()
        for (dialect in FluxDialect.entries) {
            val info = NsdServiceInfo().apply {
                serviceName = identity.deviceId
                serviceType = dialect.serviceType
                setPort(bound.localPort)
                setAttribute("id", identity.deviceId)
                setAttribute("name", "OhmLauncher")
                setAttribute("type", "phone")
                setAttribute("protocol", "8")
            }
            val callback = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(service: NsdServiceInfo) {
                    if (!running || closed) runCatching { manager.unregisterService(this) }
                }
                override fun onRegistrationFailed(service: NsdServiceInfo, code: Int) {
                    Log.w("OhmFlux", "NSD registration failed: $code")
                }
                override fun onServiceUnregistered(service: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(service: NsdServiceInfo, code: Int) = Unit
            }
            try {
                synchronized(active) {
                    check(running && !closed) { "Flux responder closed" }
                    registrations.add(callback)
                }
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, callback)
                if (!running || closed) runCatching { manager.unregisterService(callback) }
            } catch (e: Exception) { close(); throw e }
        }
    }

    private fun handle(raw: Socket) {
        var session: FluxSession? = null
        try {
            session = FluxSession.accept(raw, identity)
            val admitted = synchronized(active) {
                handshakes.remove(raw)
                if (!running) false else { active.add(session); true }
            }
            if (!admitted) return
            session.setReadTimeout(0)
            onConnected(session)
            while (running) {
                val request = session.readPair(
                    onScreen = { reply -> onScreen(session, reply) },
                    onThemeCatalog = { catalog -> onThemeCatalog(session, catalog) },
                    onMedia = { body, token -> onMedia(session, body, token) },
                    onShare = { share -> onShare(session, share) },
                    onFileOffer = { file -> onFileOffer(session, file) },
                    onInputState = { status -> onInputState(session, status) },
                    onThemeSelected = { result -> onThemeSelected(session, result) },
                ) ?: continue
                if (!request.first) {
                    onUnpair(session)
                    break
                }
                val timestamp = request.second
                if (!session.paired && FluxWire.pairTimestamp(timestamp)) {
                    onPairRequest(session, timestamp)
                } else session.rejectIncomingPair()
            }
        } catch (e: Exception) {
            if (running) Log.i("OhmFlux", "Peer disconnected: ${e.message}")
        } finally {
            synchronized(active) { handshakes.remove(raw) }
            session?.let {
                synchronized(active) { active.remove(it) }
                onDisconnected(it)
                it.close()
            } ?: raw.close()
            count.decrementAndGet()
        }
    }

    override fun close() {
        val (sessions, sockets, callbacks) = synchronized(active) {
            running = false
            closed = true
            val sockets = listOfNotNull(server) + handshakes.toList()
            server = null
            handshakes.clear()
            val sessions = active.toList()
            active.clear()
            val callbacks = registrations.toList()
            registrations.clear()
            Triple(sessions, sockets, callbacks)
        }
        // Revoke synchronously; Conscrypt may send close_notify when closing its TLS socket.
        sessions.forEach { it.close() }
        sockets.forEach {
            if (!FluxControlCleanup.shared.raw(it)) Log.w("OhmFlux", "Responder socket cleanup capacity exhausted")
        }
        callbacks.forEach { runCatching { manager.unregisterService(it) } }
    }
}
