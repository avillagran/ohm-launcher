package cl.villagranquiroz.ohm_launcher

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class FluxDiscoveryTest {
    @Test fun closingDuringResolveClearsQueuedPeersAndRejectsLateResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resolved = AtomicReference<NsdManager.ResolveListener>()
        val resolves = AtomicInteger()
        val peers = AtomicInteger()
        val browser = object : FakeBrowser() {
            override fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener) {
                resolves.incrementAndGet()
                resolved.set(listener)
                entered.countDown()
                release.await()
            }
        }
        val discovery = FluxDiscovery(browser)
        discovery.start({ peers.incrementAndGet() }, { fail("unexpected discovery failure") })
        val first = service("1234567890abcdef1234567890abcdef")
        val callback = Thread { browser.listener.onServiceFound(first) }
        callback.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            browser.listener.onServiceFound(service("abcdef1234567890abcdef1234567890"))
            discovery.close()
            assertEquals(1, browser.stops.get())
            release.countDown()
            callback.join(2000)
            assertFalse(callback.isAlive)
            resolved.get().onServiceResolved(first)
            browser.listener.onServiceFound(first)
            assertEquals(1, resolves.get())
            assertEquals(0, peers.get())
        } finally { release.countDown(); callback.join(2000); discovery.close() }
    }

    @Test fun synchronousResolveRejectionDoesNotPreventNextPeer() {
        val resolved = AtomicReference<NsdManager.ResolveListener>()
        val attempts = AtomicInteger()
        val browser = object : FakeBrowser() {
            override fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener) {
                if (attempts.incrementAndGet() == 1) throw IllegalArgumentException("already resolving")
                resolved.set(listener)
            }
        }
        val peers = mutableListOf<FluxDiscovery.Endpoint>()
        val discovery = FluxDiscovery(browser)
        try {
            discovery.start({ peers.add(it) }, { fail("unexpected discovery failure") })
            browser.listener.onServiceFound(service("1234567890abcdef1234567890abcdef"))
            val second = service("abcdef1234567890abcdef1234567890")
            browser.listener.onServiceFound(second)
            resolved.get().onServiceResolved(second)
            assertEquals(2, attempts.get())
            assertEquals(listOf(second.serviceName), peers.map { it.id })
        } finally { discovery.close() }
    }

    @Test fun bothServiceTypesResolveSeriallyAndPreserveTheirDialect() {
        val listeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
        val pending = mutableListOf<Pair<NsdServiceInfo, NsdManager.ResolveListener>>()
        val stopped = mutableListOf<NsdManager.DiscoveryListener>()
        val browser = object : FluxNsdBrowser {
            override fun discover(listener: NsdManager.DiscoveryListener) = error("type required")
            override fun discover(type: String, listener: NsdManager.DiscoveryListener): Boolean {
                listeners[type] = listener
                return true
            }
            override fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener) { pending.add(info to listener) }
            override fun stop(listener: NsdManager.DiscoveryListener) { stopped.add(listener) }
        }
        val peers = mutableListOf<FluxDiscovery.Endpoint>()
        val discovery = FluxDiscovery(browser)
        discovery.start(peers::add, { fail("unexpected failure") })
        val legacy = service("1234567890abcdef1234567890abcdef")
        val native = service("abcdef1234567890abcdef1234567890").apply { serviceType = FluxDialect.FLUX.serviceType }
        listeners.getValue(FluxDialect.LEGACY_KDE.serviceType).onServiceFound(legacy)
        listeners.getValue(FluxDialect.FLUX.serviceType).onServiceFound(native)
        assertEquals(1, pending.size)
        pending[0].second.onServiceResolved(legacy)
        assertEquals(2, pending.size)
        pending[1].second.onServiceResolved(native)
        assertEquals(listOf(FluxDialect.LEGACY_KDE, FluxDialect.FLUX), peers.map { it.dialect })
        discovery.close()
        assertEquals(2, stopped.size)
        listeners.values.forEach { it.onDiscoveryStarted("late") }
        assertEquals(4, stopped.size)
    }

    @Test fun oneUnavailableServiceTypeDoesNotStopTheOtherOrChangeResolvedDialect() {
        val listeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
        var resolve: NsdManager.ResolveListener? = null
        val browser = object : FluxNsdBrowser {
            override fun discover(listener: NsdManager.DiscoveryListener) = error("type required")
            override fun discover(type: String, listener: NsdManager.DiscoveryListener): Boolean {
                listeners[type] = listener
                return true
            }
            override fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener) { resolve = listener }
            override fun stop(listener: NsdManager.DiscoveryListener) = Unit
        }
        val peers = mutableListOf<FluxDiscovery.Endpoint>()
        val discovery = FluxDiscovery(browser)
        try {
            discovery.start(peers::add, { fail("legacy is still available") })
            listeners.getValue(FluxDialect.FLUX.serviceType).onStartDiscoveryFailed(FluxDialect.FLUX.serviceType, 1)
            val legacy = service("1234567890abcdef1234567890abcdef")
            listeners.getValue(FluxDialect.LEGACY_KDE.serviceType).onServiceFound(legacy)
            resolve!!.onServiceResolved(service(legacy.serviceName).apply { serviceType = FluxDialect.FLUX.serviceType })
            assertTrue(peers.isEmpty())
            listeners.getValue(FluxDialect.LEGACY_KDE.serviceType).onServiceFound(legacy)
            resolve!!.onServiceResolved(legacy)
            assertEquals(FluxDialect.LEGACY_KDE, peers.single().dialect)
        } finally { discovery.close() }
    }

    private fun service(id: String) = NsdServiceInfo().apply {
        serviceName = id
        serviceType = "_kdeconnect._udp."
        host = InetAddress.getLoopbackAddress()
        port = 1716
        setAttribute("id", id)
        setAttribute("name", "Desktop")
    }

    private abstract class FakeBrowser : FluxNsdBrowser {
        lateinit var listener: NsdManager.DiscoveryListener
        val stops = AtomicInteger()
        override fun discover(listener: NsdManager.DiscoveryListener) { this.listener = listener }
        override fun stop(listener: NsdManager.DiscoveryListener) { stops.incrementAndGet() }
    }
}
