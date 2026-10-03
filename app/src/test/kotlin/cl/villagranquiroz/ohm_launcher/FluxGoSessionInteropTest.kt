package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedReader
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Optional real-Go checks; build the disposable test peers using tools/flux/interoperability. */
@RunWith(RobolectricTestRunner::class)
class FluxGoSessionInteropTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun nativeGoListenerAcceptsOhmAndExchangesAuthenticatedSharePackets() = exchange(FluxDialect.FLUX, false)
    @Test fun nativeGoDialerPairsWithOhmResponderAndExchangesAuthenticatedSharePackets() = exchange(FluxDialect.FLUX, true)
    @Test fun legacyGoListenerPreservesInstalledProtocolAndVerificationKey() = exchange(FluxDialect.LEGACY_KDE, false)
    @Test fun legacyGoDialerPreservesInstalledResponderProtocol() = exchange(FluxDialect.LEGACY_KDE, true)

    private fun exchange(dialect: FluxDialect, incoming: Boolean) {
        val name = if (dialect == FluxDialect.FLUX) "native" else "legacy"
        val helper = System.getenv("OHM_FLUX_INTEROP_HELPER_${name.uppercase()}")
        val fixtureSource = System.getenv("OHM_FLUX_INTEROP_FIXTURES")
        assumeTrue("Go interoperability helper and disposable fixtures were not configured", helper != null && fixtureSource != null)
        assertTrue("Configured Go helper is not executable", File(helper!!).canExecute())
        val fixtures = temporary.newFolder("fixtures")
        File(fixtureSource!!).copyRecursively(fixtures, overwrite = true)
        val fixture = JSONObject(File(fixtures, "fixture-$name.json").readText())
        val identity = FixtureIdentity(fixture.getString("kotlinId"), File(fixtures, "kotlin"))
        val goCertificate = File(fixtures, "go/certificate.der").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val listener = if (incoming) openFluxListener().apply { soTimeout = 10_000 } else null
        val goPort = reserveFluxPort()
        val udpPort = DatagramSocket(0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val command = mutableListOf(helper, "--mode", if (incoming) "dial" else "listen", "--dialect", name,
            "--fixtures", fixtures.absolutePath, "--port", goPort.toString(), "--udp-port", udpPort.toString(),
            "--pair", if (incoming) "initiate" else "respond")
        listener?.let { command.addAll(listOf("--peer-port", it.localPort.toString())) }
        val stderr = File(temporary.root, "go-stderr.txt")
        val process = ProcessBuilder(command).redirectError(stderr).start()
        val output = process.inputStream.bufferedReader()
        val reads = Executors.newSingleThreadExecutor { work -> Thread(work, "ohm-flux-go-test-output").apply { isDaemon = true } }
        val observed = java.util.concurrent.CopyOnWriteArrayList<String>()
        fun event(): JSONObject = reads.submit<JSONObject> {
            val line = output.readLine() ?: error("Go peer exited before its expected result")
            observed.add(line)
            JSONObject(line)
        }.get(12, TimeUnit.SECONDS)
        try {
            val ready = event()
            assertTrue(ready.getBoolean("ready"))
            assertEquals(name, ready.getString("dialect"))
            val session = if (incoming) FluxSession.accept(listener!!.accept(), identity) else FluxSession.connect(
                FluxDiscovery.Endpoint(ready.getString("id"), "Go test", InetAddress.getLoopbackAddress(), ready.getInt("port"), dialect), identity)
            session.use {
                assertEquals(dialect, session.dialect)
                assertArrayEquals(goCertificate.encoded, session.certificate.encoded)
                assertFalse(session.paired)
                val peers = ConcurrentHashMap<String, FluxSession>()
                peers[session.id] = session
                session.bindLiveSession({ peers[session.id] === session }, peers)
                if (incoming) {
                    val request = session.readPair()!!
                    assertTrue(request.first)
                    assertTrue(FluxWire.pairTimestamp(request.second))
                    assertEquals(if (dialect == FluxDialect.FLUX) 16 else 8, session.key(request.second).length)
                    session.acceptIncomingPair(request.second) { it() }
                } else {
                    assertTrue(session.pair(System.currentTimeMillis() / 1000))
                    assertFalse("a Go reply cannot approve trust on the phone", session.paired)
                    session.confirmPair { it() }
                }
                assertTrue(session.paired)
                val paired = event()
                assertTrue(paired.getBoolean("paired"))
                assertTrue(paired.getBoolean("peerCertificateMatched"))
                session.setReadTimeout(10_000)
                var share: FluxIncomingShare? = null
                repeat(2) { session.readPair(onShare = { share = it }) }
                assertEquals(FluxIncomingShare.Text("Go interoperability fixture"), share)
                session.sendText("Ohm interoperability fixture")
                val delivered = event()
                assertEquals("Ohm interoperability fixture", delivered.getString("receivedText"))
                assertEquals(FluxWire.type(FluxWire.SHARE, dialect), delivered.getString("type"))
            }
        } catch (failure: Throwable) {
            failure.addSuppressed(AssertionError("Go peer diagnostics; stdout=${observed.joinToString("\n")}; stderr=${stderr.readText()}"))
            throw failure
        } finally {
            listener?.close()
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            reads.shutdownNow()
            output.close()
        }
    }

    private fun reserveFluxPort(): Int = openFluxListener().use { it.localPort }

    /** The native provider enforces the actual LAN protocol range, including in dial mode. */
    private fun openFluxListener(): ServerSocket = (1716..1764).firstNotNullOfOrNull { port ->
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
            socket
        } catch (_: Exception) { socket.close(); null }
    } ?: error("No loopback Flux port available")

    private class FixtureIdentity(override val deviceId: String, directory: File) : FluxSessionIdentity {
        override val certificate = File(directory, "certificate.der").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        private val material = FluxIdentity.Material(
            KeyFactory.getInstance(certificate.publicKey.algorithm).generatePrivate(
                PKCS8EncodedKeySpec(File(directory, "privateKey.pkcs8.der").readBytes())), certificate)
        private val pins = ConcurrentHashMap<String, ByteArray>()
        override fun tlsContext() = FluxIdentity.createTlsContext(material)
        override fun pinned(id: String) = pins[id]?.clone()
        override fun pin(id: String, certificate: X509Certificate) { pins[id] = certificate.encoded }
        override fun forget(id: String) { pins.remove(id) }
    }
}
