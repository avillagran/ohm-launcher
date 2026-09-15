package cl.villagranquiroz.ohm_launcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Keeps [NotificationBadgeStore] in sync with the status bar notification set
 * so app icons in favorites, edge boxes and search results can render their
 * pending-count badges. Requires the user to grant "Notification access".
 */
class OhmNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        publish()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        publish()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        publish()
    }

    private fun publish() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val counts = HashMap<String, Int>()
        for (sbn in active) {
            if (sbn.packageName == packageName) continue
            counts[sbn.packageName] = (counts[sbn.packageName] ?: 0) + 1
        }
        NotificationBadgeStore.updateAll(counts)
    }
}
