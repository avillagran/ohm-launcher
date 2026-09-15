package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationBadgeStoreTest {
    @Test
    fun updateAllReplacesCountsAndNotifiesListeners() {
        var notifications = 0
        val unregister = NotificationBadgeStore.register { notifications++ }

        NotificationBadgeStore.updateAll(mapOf("com.example.a" to 2, "com.example.b" to 1))
        assertEquals(2, NotificationBadgeStore.countFor("com.example.a"))
        assertEquals(1, NotificationBadgeStore.countFor("com.example.b"))
        assertEquals(0, NotificationBadgeStore.countFor("com.example.c"))
        assertEquals(1, notifications)

        NotificationBadgeStore.updateAll(mapOf("com.example.c" to 5))
        assertEquals(0, NotificationBadgeStore.countFor("com.example.a"))
        assertEquals(5, NotificationBadgeStore.countFor("com.example.c"))
        assertEquals(2, notifications)

        unregister()
        NotificationBadgeStore.updateAll(emptyMap())
        assertEquals(2, notifications)
    }
}
