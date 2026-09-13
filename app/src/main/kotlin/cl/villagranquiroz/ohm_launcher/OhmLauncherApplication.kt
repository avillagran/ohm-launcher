package cl.villagranquiroz.ohm_launcher

import android.app.Application
import android.net.nsd.NsdManager

/** Process-level owner of reusable native discovery components. */
class OhmLauncherApplication : Application() {
    lateinit var lanAdvertiser: OmarchyLanAdvertiser
        private set

    override fun onCreate() {
        super.onCreate()
        val config = OhmDiscoveryConfig(apiPort = resources.getInteger(R.integer.local_api_port))
        val nsdManager = getSystemService(NsdManager::class.java)
        lanAdvertiser = OmarchyLanAdvertiser(config, AndroidNsdRegistrar(nsdManager))
        lanAdvertiser.start()
    }
}
