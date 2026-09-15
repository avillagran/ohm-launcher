package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtfxRestartPolicyTest {
    private val base = TtfxConfig.parse(JSONObject())

    @Test
    fun customTextRasterGeometryGrowsWithConfiguredSize() {
        val small = TtfxTextLayoutPolicy.targetRows(textSize = 1, lineCount = 1, canvasRows = 100)
        val normal = TtfxTextLayoutPolicy.targetRows(textSize = 7, lineCount = 1, canvasRows = 100)
        val large = TtfxTextLayoutPolicy.targetRows(textSize = 12, lineCount = 1, canvasRows = 100)

        assertTrue(small < normal)
        assertTrue(normal < large)
        assertEquals(40, large)
        assertTrue(
            TtfxTextLayoutPolicy.wrapChars(columns = 80, textSize = 12) <
                TtfxTextLayoutPolicy.wrapChars(columns = 80, textSize = 1),
        )
        assertEquals(120, TtfxTextLayoutPolicy.canvasColumns(resolution = 1))
        assertTrue(TtfxTextLayoutPolicy.canvasColumns(1) > TtfxTextLayoutPolicy.canvasColumns(8))
    }

    @Test
    fun decryptRevealsRasterizedTextInLargeBatches() {
        assertEquals(listOf("--typing-speed", "120"), TtfxEffectOptions.forEffect("decrypt", 4.0))
        assertTrue(TtfxEffectOptions.forEffect("vhstape", 4.0).isEmpty())
    }

    @Test
    fun compactCustomTextRunsFasterThanTheOfficialWordmark() {
        assertEquals(120, TtfxFrameRatePolicy.frameRate(isOmarchy = false, speed = 4.0))
        assertEquals(30, TtfxFrameRatePolicy.frameRate(isOmarchy = true, speed = 4.0))
    }

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
