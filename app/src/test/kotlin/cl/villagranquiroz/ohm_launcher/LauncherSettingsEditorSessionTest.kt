package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherSettingsEditorSessionTest {
    @Test
    fun previewsEveryChangeAndRollsBackWhenDismissedWithoutSaving() {
        val original = LauncherSettings.parse("{}")
        val previews = mutableListOf<LauncherSettings>()
        val session = LauncherSettingsEditorSession(original, previews::add)

        session.update(original.copy(textScale = 1.2))
        session.cancel()

        assertEquals(listOf(1.2, 1.0), previews.map(LauncherSettings::textScale))
    }

    @Test
    fun commitPersistsLatestValueWithoutRollback() {
        val original = LauncherSettings.parse("{}")
        val previews = mutableListOf<LauncherSettings>()
        var saved: LauncherSettings? = null
        val session = LauncherSettingsEditorSession(original, previews::add)
        val changed = original.copy(settingsPanelOpacity = 0.64)

        session.update(changed)
        session.commit { saved = it }
        session.cancel()

        assertEquals(changed, saved)
        assertEquals(listOf(0.64), previews.map(LauncherSettings::settingsPanelOpacity))
    }
}
