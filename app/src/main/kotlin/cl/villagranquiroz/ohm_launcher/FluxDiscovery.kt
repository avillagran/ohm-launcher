package cl.villagranquiroz.ohm_launcher

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.Closeable
import java.net.InetAddress

internal interface FluxNsdBrowser {
    fun discover(listener: NsdManager.DiscoveryListener)
    fun discover(type: String, listener: NsdManager.DiscoveryListener): Boolean {
        if (type != FluxDialect.LEGACY_KDE.serviceType) return false
        discover(listener)
        return true
    }
    fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener)
    fun stop(listener: NsdManager.DiscoveryListener)
}

/** Both advertisements lead to one listener; the secure identity confirms the selected dialect. */
internal class FluxDiscovery(private val browser: FluxNsdBrowser) : Closeable {
    constructor(manager: NsdManager) : this(object : FluxNsdBrowser {
        override fun discover(listener: NsdManager.DiscoveryListener) {
            discover(FluxDialect.LEGACY_KDE.serviceType, listener)
        }
        override fun discover(type: String, listener: NsdManager.DiscoveryListener): Boolean {
            manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            return true
        }
        override fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener) =
            manager.resolveService(info, listener)
        override fun stop(listener: NsdManager.DiscoveryListener) = manager.stopServiceDiscovery(listener)
    })
    data class Endpoint(val id: String, val name: String, val address: InetAddress, val port: Int,
                        val dialect: FluxDialect = FluxDialect.LEGACY_KDE)
    private val lock = Any()
    private val listeners = mutableSetOf<NsdManager.DiscoveryListener>()
    private var started = false
    private var starting = false
    private var closed = false
    private val queued = ArrayDeque<NsdServiceInfo>()
    private var resolving: NsdServiceInfo? = null

    fun start(onPeer: (Endpoint) -> Unit, onFailure: () -> Unit) {
        synchronized(lock) { check(!started && !closed); started = true; starting = true }
        fun next() {
            val info = synchronized(lock) {
                if (closed || resolving != null || queued.isEmpty()) return
                queued.removeFirst().also { resolving = it }
            }
            fun finished() {
                synchronized(lock) { if (resolving === info) resolving = null }
                next()
            }
            val callback = object : NsdManager.ResolveListener {
                override fun onResolveFailed(service: NsdServiceInfo, code: Int) = finished()
                override fun onServiceResolved(service: NsdServiceInfo) {
                    try {
                        val dialect = FluxDialect.service(info.serviceType) ?: return
                        if (FluxDialect.service(service.serviceType) != dialect) return
                        val id = service.attributes["id"]?.toString(Charsets.UTF_8) ?: service.serviceName
                        val address = service.host
                        val current = synchronized(lock) { !closed && resolving === info }
                        if (current && FluxWire.validId(id) && address != null && service.port in 1716..1764) {
                            val name = service.attributes["name"]?.toString(Charsets.UTF_8) ?: service.serviceName
                            onPeer(Endpoint(id, name.take(32), address, service.port, dialect))
                        }
                    } finally { finished() }
                }
            }
            try { browser.resolve(info, callback) } catch (_: Exception) { finished() }
        }
        for (dialect in FluxDialect.entries) {
            val discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {
                    if (synchronized(lock) { closed || this !in listeners }) runCatching { browser.stop(this) }
                }
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onStartDiscoveryFailed(type: String, code: Int) {
                    val failed = synchronized(lock) {
                        listeners.remove(this)
                        !closed && !starting && listeners.isEmpty()
                    }
                    if (failed) { close(); onFailure() }
                }
                override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
                override fun onServiceLost(info: NsdServiceInfo) {
                    synchronized(lock) {
                        queued.removeAll { it.serviceName == info.serviceName && it.serviceType == info.serviceType }
                    }
                }
                override fun onServiceFound(info: NsdServiceInfo) {
                    synchronized(lock) {
                        if (closed || this !in listeners || FluxDialect.service(info.serviceType) != dialect) return
                        if (queued.size >= 64 || queued.any { it.serviceName == info.serviceName && it.serviceType == info.serviceType }) return
                        queued.add(info)
                    }
                    next()
                }
            }
            if (synchronized(lock) { if (closed) false else { listeners.add(discovery); true } }) {
                try {
                    if (!browser.discover(dialect.serviceType, discovery)) synchronized(lock) { listeners.remove(discovery) }
                    if (synchronized(lock) { closed }) runCatching { browser.stop(discovery) }
                } catch (_: Exception) { synchronized(lock) { listeners.remove(discovery) } }
            }
        }
        val failed = synchronized(lock) { starting = false; !closed && listeners.isEmpty() }
        if (failed) { close(); onFailure() }
    }

    override fun close() {
        val stopping = synchronized(lock) {
            if (closed) return
            closed = true
            queued.clear()
            resolving = null
            listeners.toList().also { listeners.clear() }
        }
        stopping.forEach { runCatching { browser.stop(it) } }
    }
}
