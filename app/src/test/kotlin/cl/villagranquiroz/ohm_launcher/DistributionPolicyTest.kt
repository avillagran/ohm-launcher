package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributionPolicyTest {
    @Test
    fun `Play distribution disables privileged and remote capabilities`() {
        val policy = DistributionPolicy(playStore = true)

        assertFalse(policy.allowLanIntegration)
        assertFalse(policy.allowAllFilesAccess)
        assertFalse(policy.allowAccessibilityControl)
        assertFalse(policy.allowNotificationAccess)
        assertFalse(policy.allowCompactSystemNavigation)
    }

    @Test
    fun `direct distribution preserves existing capabilities`() {
        val policy = DistributionPolicy(playStore = false)

        assertTrue(policy.allowLanIntegration)
        assertTrue(policy.allowAllFilesAccess)
        assertTrue(policy.allowAccessibilityControl)
        assertTrue(policy.allowNotificationAccess)
        assertTrue(policy.allowCompactSystemNavigation)
    }
}
