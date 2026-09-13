package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TtfxEditorSessionTest {
    private val original = TtfxConfig.parse(JSONObject())

    @Test
    fun previewsEveryDistinctEditAndCancelRestoresOriginal() {
        val previews = mutableListOf<TtfxConfig>()
        val session = TtfxEditorSession(original, previews::add)
        val moved = original.copy(textX = .2, textY = .8)

        session.update(moved)
        session.update(moved)
        session.cancel()

        assertEquals(listOf(moved, original), previews)
    }

    @Test
    fun commitSavesLatestPreviewWithoutRestoringOriginal() {
        val previews = mutableListOf<TtfxConfig>()
        val saves = mutableListOf<TtfxConfig>()
        val session = TtfxEditorSession(original, previews::add)
        val edited = original.copy(effect = "beams", speed = 2.2)

        session.update(edited)
        session.commit(saves::add)
        session.cancel()

        assertEquals(listOf(edited), previews)
        assertEquals(listOf(edited), saves)
    }
}
