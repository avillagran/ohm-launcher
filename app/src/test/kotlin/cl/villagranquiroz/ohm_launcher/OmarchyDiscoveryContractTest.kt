package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmarchyDiscoveryContractTest {
    @Test
    fun createsOhmFallbackUriFromConfiguredEndpoint() {
        assertEquals(
            "ohm://192.168.1.44:9123",
            OhmDiscoveryConfig(apiPort = 9123).fallbackUri("192.168.1.44"),
        )
        assertEquals(
            "ohm://[fd00::12]:9123",
            OhmDiscoveryConfig(apiPort = 9123).fallbackUri("fd00::12"),
        )
    }

    @Test
    fun rejectsUnsafeFallbackUriHosts() {
        listOf("", "bad host", "[fd00::12]", "host/path", "host?query").forEach { host ->
            try {
                OhmDiscoveryConfig(apiPort = 9123).fallbackUri(host)
                throw AssertionError("Expected host to be rejected: $host")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun createsOhmServiceTxtRecordsFromConfiguredPort() {
        val advertisement = OhmServiceAdvertisement.create(OhmDiscoveryConfig(apiPort = 9123))

        assertEquals("OhmLauncher", advertisement.name)
        assertEquals("_ohm._tcp", advertisement.type)
        assertEquals(9123, advertisement.port)
        assertEquals("1", advertisement.txt.getValue("ohm").toString(Charsets.UTF_8))
        assertEquals("9123", advertisement.txt.getValue("port").toString(Charsets.UTF_8))
    }

    @Test
    fun advertiserTracksAsynchronousRegistrationStateAndIsIdempotent() {
        val registrar = RecordingRegistrar()
        val advertiser = OmarchyLanAdvertiser(OhmDiscoveryConfig(apiPort = 9123), registrar)

        assertTrue(advertiser.start())
        assertFalse(advertiser.start())
        assertEquals(OhmDiscoveryState.STARTING, advertiser.state)
        assertEquals(9123, registrar.advertisement?.port)

        registrar.listener?.onRegistered()
        assertEquals(OhmDiscoveryState.RUNNING, advertiser.state)
        assertTrue(advertiser.isRunning)

        assertTrue(advertiser.stop())
        assertFalse(advertiser.stop())
        assertEquals(OhmDiscoveryState.STOPPING, advertiser.state)
        registrar.listener?.onUnregistered()
        assertEquals(OhmDiscoveryState.STOPPED, advertiser.state)
        assertFalse(advertiser.isRunning)
    }

    @Test
    fun lateRegistrationCallbackCannotReviveStoppingAdvertiser() {
        val registrar = RecordingRegistrar()
        val advertiser = OmarchyLanAdvertiser(OhmDiscoveryConfig(apiPort = 9123), registrar)

        advertiser.start()
        advertiser.stop()
        registrar.listener?.onRegistered()

        assertEquals(OhmDiscoveryState.STOPPING, advertiser.state)
    }

    @Test
    fun failedUnregistrationReturnsToRunningSoStopCanRetry() {
        val registrar = RecordingRegistrar()
        val advertiser = OmarchyLanAdvertiser(OhmDiscoveryConfig(apiPort = 9123), registrar)

        advertiser.start()
        registrar.listener?.onRegistered()
        advertiser.stop()
        registrar.listener?.onUnregistrationFailed(7)

        assertEquals(OhmDiscoveryState.RUNNING, advertiser.state)
        assertTrue(advertiser.stop())
    }

    @Test
    fun bleCollectorFiltersPeerNamesAndDeduplicatesAddresses() {
        val collector = OmarchyBlePeerCollector()

        assertTrue(collector.record(OhmBlePeer("AA:00", "Omarchy Desk", -70)))
        assertTrue(collector.record(OhmBlePeer("AA:00", "Omarchy Desk", -55)))
        assertTrue(collector.record(OhmBlePeer("BB:00", "OHM nearby", -60)))
        assertFalse(collector.record(OhmBlePeer("CC:00", "Headphones", -40)))

        assertEquals(
            listOf(
                OhmBlePeer("AA:00", "Omarchy Desk", -55),
                OhmBlePeer("BB:00", "OHM nearby", -60),
            ),
            collector.peers(),
        )
    }

    private class RecordingRegistrar : OhmServiceRegistrar {
        var advertisement: OhmServiceAdvertisement? = null
        var listener: OhmServiceRegistrationListener? = null

        override fun register(
            advertisement: OhmServiceAdvertisement,
            listener: OhmServiceRegistrationListener,
        ) {
            this.advertisement = advertisement
            this.listener = listener
        }

        override fun unregister(listener: OhmServiceRegistrationListener) = Unit
    }
}
