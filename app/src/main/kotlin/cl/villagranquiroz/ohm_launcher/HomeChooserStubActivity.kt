package cl.villagranquiroz.ohm_launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Temporary HOME component used only to reset the system's default-launcher
 * choice so the resolver appears with an "Always" option (reliable on MIUI,
 * where the RoleManager request is ignored). Disabled again from
 * [MainActivity.onResume] as soon as the user comes back; if the stub itself
 * is somehow chosen it simply forwards to the real launcher.
 */
class HomeChooserStubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }
}
