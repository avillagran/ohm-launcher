package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtfxAudioPermissionPolicyTest {
    @Test
    fun promptsOnlyWhenUserTurnsAudioOn() {
        assertTrue(TtfxAudioPermissionPolicy.shouldRequest(checked = true))
        assertFalse(TtfxAudioPermissionPolicy.shouldRequest(checked = false))
    }
}
