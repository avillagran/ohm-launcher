package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

@RunWith(RobolectricTestRunner::class)
class FluxSessionDialectTest {
    private val phoneId = "abcdef1234567890abcdef1234567890"
    private val desktopId = "1234567890abcdef1234567890abcdef"

    @Test fun nativeAndLegacySessionsMutuallyAuthenticateAndRequirePhoneConfirmation() {
        for (dialect in FluxDialect.entries) withListener { listener ->
            val phone = SoftwareIdentity(phoneId)
            val desktop = SoftwareIdentity(desktopId)
            val errors = AtomicReference<Throwable>()
            val received = AtomicReference<String>()
            val desktopKey = AtomicReference<String>()
            val timestamp = System.currentTimeMillis() / 1000
            val worker = Thread {
                try {
                    FluxSession.accept(listener.accept(), desktop).use { incoming ->
                        assertEquals(dialect, incoming.dialect)
                        bind(incoming)
                        val request = incoming.readPair()!!
                        assertEquals(true to timestamp, request)
                        assertFalse(incoming.paired)
                        desktopKey.set(incoming.key(timestamp))
                        incoming.acceptIncomingPair(timestamp) { it() }
                        incoming.readPair(onShare = { received.set((it as FluxIncomingShare.Text).value) })
                    }
                } catch (error: Throwable) { errors.set(error) }
            }.apply { start() }
            try {
                val endpoint = FluxDiscovery.Endpoint(desktopId, "Desktop", InetAddress.getLoopbackAddress(), listener.localPort, dialect)
                FluxSession.connect(endpoint, phone).use { outgoing ->
                    assertEquals(dialect, outgoing.dialect)
                    assertThrows(IllegalStateException::class.java) { outgoing.sendText("before approval") }
                    assertTrue(outgoing.pair(timestamp))
                    assertFalse("desktop reply alone must not pin the phone", outgoing.paired)
                    assertEquals(if (dialect == FluxDialect.FLUX) 16 else 8, outgoing.key(timestamp).length)
                    assertEquals(desktopKey.get(), outgoing.key(timestamp))
                    outgoing.confirmPair { it() }
                    bind(outgoing)
                    outgoing.sendText("after explicit approval")
                    worker.join(10_000)
                    assertFalse("peer stalled", worker.isAlive)
                    errors.get()?.let { throw AssertionError("peer failed", it) }
                    assertEquals("after explicit approval", received.get())
                }
            } finally { listener.close(); worker.join(10_000) }
        }
    }

    @Test fun outgoingSessionRejectsLegacySecureIdentityAfterNativePlaintext() = withListener { listener ->
        val phone = SoftwareIdentity(phoneId)
        val desktop = SoftwareIdentity(desktopId)
        val errors = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                listener.accept().use { raw ->
                    raw.soTimeout = 5000
                    assertEquals(FluxDialect.FLUX, FluxWire.dialect(FluxWire.readLine(raw.inputStream)))
                    peerTls(raw, desktop, true).use { tls ->
                        tls.outputStream.write(FluxWire.identity(desktopId, "Desktop").toByteArray())
                        tls.outputStream.flush()
                        assertEquals(FluxDialect.FLUX, FluxWire.dialect(FluxWire.readLine(tls.inputStream)))
                    }
                }
            } catch (error: Throwable) { errors.set(error) }
        }.apply { start() }
        try {
            val endpoint = FluxDiscovery.Endpoint(desktopId, "Desktop", InetAddress.getLoopbackAddress(), listener.localPort, FluxDialect.FLUX)
            assertThrows(IllegalArgumentException::class.java) { FluxSession.connect(endpoint, phone) }
            worker.join(10_000)
            assertFalse(worker.isAlive)
            errors.get()?.let { throw AssertionError("TLS peer failed", it) }
            assertNull(phone.pinned(desktopId))
        } finally { listener.close(); worker.join(10_000) }
    }

    @Test fun incomingSessionRejectsNamespaceChangedInsideTlsWithoutPinning() = withListener { listener ->
        val phone = SoftwareIdentity(phoneId)
        val desktop = SoftwareIdentity(desktopId)
        val errors = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { raw ->
                    raw.outputStream.write(FluxWire.identity(desktopId, "Desktop", phoneId, dialect = FluxDialect.FLUX).toByteArray())
                    raw.outputStream.flush()
                    peerTls(raw, desktop, false).use { tls ->
                        tls.outputStream.write(FluxWire.identity(desktopId, "Desktop").toByteArray())
                        tls.outputStream.flush()
                        assertEquals(FluxDialect.FLUX, FluxWire.dialect(FluxWire.readLine(tls.inputStream)))
                    }
                }
            } catch (error: Throwable) { errors.set(error) }
        }.apply { start() }
        try {
            assertThrows(IllegalArgumentException::class.java) { FluxSession.accept(listener.accept(), phone) }
            worker.join(10_000)
            assertFalse(worker.isAlive)
            errors.get()?.let { throw AssertionError("TLS peer failed", it) }
            assertNull(phone.pinned(desktopId))
        } finally { listener.close(); worker.join(10_000) }
    }

    private fun bind(session: FluxSession) {
        val peers = ConcurrentHashMap<String, FluxSession>()
        peers[session.id] = session
        session.bindLiveSession({ peers[session.id] === session }, peers)
    }

    private fun peerTls(raw: Socket, identity: SoftwareIdentity, client: Boolean): SSLSocket =
        (identity.tlsContext().socketFactory.createSocket(raw, "127.0.0.1", raw.port, true) as SSLSocket).apply {
            useClientMode = client
            if (!client) needClientAuth = true
            enabledProtocols = arrayOf("TLSv1.2")
            soTimeout = 5000
            startHandshake()
        }

    private fun withListener(action: (ServerSocket) -> Unit) {
        val listener = (1716..1764).firstNotNullOfOrNull { port ->
            val socket = ServerSocket()
            try { socket.reuseAddress = true; socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port)); socket }
            catch (_: Exception) { socket.close(); null }
        } ?: error("No loopback Flux port available")
        listener.soTimeout = 10_000
        listener.use(action)
    }

    private class SoftwareIdentity(override val deviceId: String) : FluxSessionIdentity {
        private val material = FluxIdentity.createMaterial(deviceId)
        private val pins = ConcurrentHashMap<String, ByteArray>()
        override val certificate: X509Certificate get() = material.certificate
        override fun tlsContext() = FluxIdentity.createTlsContext(material)
        override fun pinned(id: String) = pins[id]?.clone()
        override fun pin(id: String, certificate: X509Certificate) { pins[id] = certificate.encoded }
        override fun forget(id: String) { pins.remove(id) }
    }
}
