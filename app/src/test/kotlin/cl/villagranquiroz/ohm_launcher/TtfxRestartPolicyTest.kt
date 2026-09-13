package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtfxRestartPolicyTest {
    private val base = TtfxConfig.parse(JSONObject())

    @Test
    fun positionAndAudioChangesDoNotRestartProcess() {
        assertFalse(requiresTtfxRestart(base, base.copy(textX = 0.2, textY = 0.8)))
        assertFalse(requiresTtfxRestart(base, base.copy(audio = !base.audio, intensity = 9, reactivity = 5)))
    }

    @Test
    fun engineInputChangesRestartProcess() {
        assertTrue(requiresTtfxRestart(base, base.copy(effect = "beams")))
        assertTrue(requiresTtfxRestart(base, base.copy(text = "Omarchy")))
        assertTrue(requiresTtfxRestart(base, base.copy(textSize = 7)))
        assertTrue(requiresTtfxRestart(base, base.copy(resolution = 4)))
        assertTrue(requiresTtfxRestart(base, base.copy(speed = 2.0)))
        assertTrue(requiresTtfxRestart(base, base.copy(enabled = !base.enabled)))
    }

    @Test
    fun staleQueuedRenderGenerationsNeverStartAProcess() {
        assertFalse(TtfxRenderGeneration.isCurrent(token = 3, current = 4))
        assertTrue(TtfxRenderGeneration.isCurrent(token = 4, current = 4))
    }
}
