package cl.villagranquiroz.ohm_launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/** Play-safe navigation bridge: a direct user press can only open Recents. */
class OmarchyNavigationService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        announceStateChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        announceStateChanged()
        return super.onUnbind(intent)
    }

    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    private fun announceStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    companion object {
        const val ACTION_STATE_CHANGED = "cl.villagranquiroz.ohm_launcher.OMARCHY_NAVIGATION_STATE_CHANGED"

        @Volatile
        var instance: OmarchyNavigationService? = null
            private set
    }
}