package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactNavigationPolicyTest {
    @Test
    fun `system navigation is hidden only while compact navigation service is connected`() {
        assertTrue(CompactNavigationPolicy.shouldHideSystemNavigation(compact = true, serviceConnected = true))
        assertFalse(CompactNavigationPolicy.shouldHideSystemNavigation(compact = true, serviceConnected = false))
        assertFalse(CompactNavigationPolicy.shouldHideSystemNavigation(compact = false, serviceConnected = true))
    }

    @Test
    fun `entering compact mode requests permission only when navigation service is unavailable`() {
        assertTrue(CompactNavigationPolicy.shouldRequestPermission(compact = true, serviceAvailable = false))
        assertFalse(CompactNavigationPolicy.shouldRequestPermission(compact = true, serviceAvailable = true))
        assertFalse(CompactNavigationPolicy.shouldRequestPermission(compact = false, serviceAvailable = false))
    }
}