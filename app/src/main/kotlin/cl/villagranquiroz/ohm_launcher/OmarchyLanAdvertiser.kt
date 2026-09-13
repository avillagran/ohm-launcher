package cl.villagranquiroz.ohm_launcher

/** Observable lifecycle of an asynchronous discovery registration. */
enum class OhmDiscoveryState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
}

interface OhmServiceRegistrationListener {
    fun onRegistered()
    fun onRegistrationFailed(errorCode: Int = 0)
    fun onUnregistered()
    fun onUnregistrationFailed(errorCode: Int = 0)
}

interface OhmServiceRegistrar {
    fun register(
        advertisement: OhmServiceAdvertisement,
        listener: OhmServiceRegistrationListener,
    )

    fun unregister(listener: OhmServiceRegistrationListener)
}

/** Idempotent, platform-neutral coordinator for an mDNS registration. */
class OmarchyLanAdvertiser(
    config: OhmDiscoveryConfig,
    private val registrar: OhmServiceRegistrar,
) {
    private val advertisement = OhmServiceAdvertisement.create(config)
    private val listener = object : OhmServiceRegistrationListener {
        override fun onRegistered() {
            transition(OhmDiscoveryState.STARTING, OhmDiscoveryState.RUNNING)
        }

        override fun onRegistrationFailed(errorCode: Int) = updateState(OhmDiscoveryState.STOPPED)
        override fun onUnregistered() = updateState(OhmDiscoveryState.STOPPED)
        override fun onUnregistrationFailed(errorCode: Int) = updateState(OhmDiscoveryState.RUNNING)
    }

    @Volatile
    var state: OhmDiscoveryState = OhmDiscoveryState.STOPPED
        private set

    val isRunning: Boolean
        get() = state == OhmDiscoveryState.RUNNING

    @Synchronized
    fun start(): Boolean {
        if (state != OhmDiscoveryState.STOPPED) return false
        state = OhmDiscoveryState.STARTING
        return try {
            registrar.register(advertisement, listener)
            true
        } catch (error: RuntimeException) {
            state = OhmDiscoveryState.STOPPED
            false
        }
    }

    @Synchronized
    fun stop(): Boolean {
        if (state == OhmDiscoveryState.STOPPED || state == OhmDiscoveryState.STOPPING) return false
        state = OhmDiscoveryState.STOPPING
        return try {
            registrar.unregister(listener)
            true
        } catch (error: RuntimeException) {
            state = OhmDiscoveryState.STOPPED
            false
        }
    }

    @Synchronized
    private fun transition(expected: OhmDiscoveryState, newState: OhmDiscoveryState) {
        if (state == expected) state = newState
    }

    @Synchronized
    private fun updateState(newState: OhmDiscoveryState) {
        state = newState
    }
}
