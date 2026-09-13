package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TtfxMiniEditorLogicTest {
    private val base = TtfxConfig.parse(JSONObject()).copy(effect = "beams")

    @Test
    fun cyclesEffectsInBothDirectionsAndPreservesOtherSettings() {
        val effects = listOf("beams", "matrix", "slice")

        assertEquals("slice", TtfxMiniEditorLogic.previous(base, effects).effect)
        assertEquals("matrix", TtfxMiniEditorLogic.next(base, effects).effect)
        assertEquals(base.text, TtfxMiniEditorLogic.next(base, effects).text)
    }

    @Test
    fun clampsLivePositionToTheCanvas() {
        assertEquals(0.0, TtfxMiniEditorLogic.position(base, -1.0, 2.0).textX, 0.0)
        assertEquals(1.0, TtfxMiniEditorLogic.position(base, -1.0, 2.0).textY, 0.0)
    }
}
