package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDialogSurfacePolicyTest {
    @Test
    fun panelStaysCompactAndClampsConfiguredTransparency() {
        val compact = SettingsDialogSurfacePolicy.resolve(1080, 2400, 3f, 0.72)
        val tooTransparent = SettingsDialogSurfacePolicy.resolve(1080, 2400, 3f, 0.1)

        assertTrue(compact.widthPx < 1080)
        assertTrue(compact.maxContentHeightPx < 2400)
        assertEquals(0.72f, compact.opacity, 0.001f)
        assertEquals(0.5f, tooTransparent.opacity, 0.001f)
    }
}