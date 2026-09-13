package cl.villagranquiroz.ohm_launcher

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.IdentityHashMap

/** Android [NsdManager] adapter for the platform-neutral LAN advertiser. */
class AndroidNsdRegistrar(
    private val manager: NsdManager,
) : OhmServiceRegistrar {
    private val listeners = IdentityHashMap<OhmServiceRegistrationListener, NsdManager.RegistrationListener>()

    @Synchronized
    override fun register(
        advertisement: OhmServiceAdvertisement,
        listener: OhmServiceRegistrationListener,
    ) {
        check(listeners[listener] == null) { "Listener is already registered" }
        val service = NsdServiceInfo().apply {
            serviceName = advertisement.name
            serviceType = advertisement.type
            port = advertisement.port
            advertisement.txt.forEach { (key, value) ->
                setAttribute(key, value.toString(Charsets.UTF_8))
            }
        }
        val androidListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                listener.onRegistered()
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(this@AndroidNsdRegistrar) { listeners.remove(listener) }
                listener.onRegistrationFailed(errorCode)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                synchronized(this@AndroidNsdRegistrar) { listeners.remove(listener) }
                listener.onUnregistered()
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(this@AndroidNsdRegistrar) { listeners.remove(listener) }
                listener.onUnregistrationFailed(errorCode)
            }
        }
        listeners[listener] = androidListener
        try {
            manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, androidListener)
        } catch (error: RuntimeException) {
            listeners.remove(listener)
            throw error
        }
    }

    @Synchronized
    override fun unregister(listener: OhmServiceRegistrationListener) {
        val androidListener = checkNotNull(listeners[listener]) { "Listener is not registered" }
        manager.unregisterService(androidListener)
    }
}
