package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmarchyBarModePolicyTest {
    @Test
    fun duplicateSettingsDoNotRebuildEdgeBoxesDuringAnimation() {
        assertTrue(OmarchyBarAnimationPolicy.ignoreDuplicateSubmission(true, false, true))
        assertFalse(OmarchyBarAnimationPolicy.ignoreDuplicateSubmission(false, false, true))
        assertFalse(OmarchyBarAnimationPolicy.ignoreDuplicateSubmission(true, true, true))
        assertFalse(OmarchyBarAnimationPolicy.ignoreDuplicateSubmission(true, false, false))
    }

    @Test
    fun omarchyBarModeDefaultsToEnabled() {
        val settings = LauncherSettings.parse("{}")

        assertTrue(settings.omarchyBarMode)
    }

    @Test
    fun omarchyBarModeRoundTripsThroughJson() {
        val updated = LauncherSettings.parse(JSONObject().put("omarchyBarMode", false).toString())

        assertFalse(updated.omarchyBarMode)
        assertFalse(updated.toJson().getBoolean("omarchyBarMode"))
        assertFalse(LauncherSettings.parse(updated.toJson().toString()).omarchyBarMode)
    }

    @Test
    fun omarchyBarModeHidesEdgeBoxesUnlessWidgetEditing() {
        assertFalse(OmarchyBarModePolicy.edgeBoxesVisible(omarchyBarMode = true, widgetEditing = false))
        assertTrue(OmarchyBarModePolicy.edgeBoxesVisible(omarchyBarMode = false, widgetEditing = false))
        assertTrue(OmarchyBarModePolicy.edgeBoxesVisible(omarchyBarMode = true, widgetEditing = true))
    }

    @Test
    fun favoritesStayInBarOnlyOutsideOmarchyMode() {
        assertFalse(OmarchyBarModePolicy.favoritesInBar(omarchyBarMode = true))
        assertTrue(OmarchyBarModePolicy.favoritesInBar(omarchyBarMode = false))
    }

    @Test
    fun compactBarShowsFavAppsButtonAndSpacer() {
        assertTrue(OmarchyBarModePolicy.favAppsButtonVisible(omarchyBarMode = true))
        assertFalse(OmarchyBarModePolicy.favAppsButtonVisible(omarchyBarMode = false))
        assertTrue(OmarchyBarModePolicy.spacerVisible(omarchyBarMode = true))
        assertFalse(OmarchyBarModePolicy.spacerVisible(omarchyBarMode = false))
    }

    @Test
    fun activadorGlyphPointsToTheStateItWillEnter() {
        assertEquals(NerdGlyph.EXPAND, OmarchyBarModePolicy.toggleGlyph(omarchyBarMode = true))
        assertEquals(NerdGlyph.COMPRESS, OmarchyBarModePolicy.toggleGlyph(omarchyBarMode = false))
    }
}
