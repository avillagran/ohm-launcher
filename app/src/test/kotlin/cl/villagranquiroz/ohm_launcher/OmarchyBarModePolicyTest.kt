package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
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
    fun edgeBoxesStayHiddenEvenDuringWidgetEditing() {
        assertFalse(OmarchyBarModePolicy.edgeBoxesVisible(omarchyBarMode = true, widgetEditing = false))
        assertFalse(OmarchyBarModePolicy.edgeBoxesVisible(omarchyBarMode = false, widgetEditing = false))
        assertFalse(OmarchyBarModePolicy.edgeBoxesVisible(omarchyBarMode = true, widgetEditing = true))
    }

    @Test
    fun savedEdgeBoxesAreNeverRenderedDuringStartupOrAfterReturningToLauncher() {
        assertFalse(
            OmarchyBarModePolicy.shouldRenderEdgeBoxes(
                hasConfiguredBoxes = true,
                omarchyBarMode = true,
                widgetEditing = false,
            ),
        )
        assertFalse(
            OmarchyBarModePolicy.shouldRenderEdgeBoxes(
                hasConfiguredBoxes = true,
                omarchyBarMode = false,
                widgetEditing = true,
            ),
        )
        assertFalse(
            OmarchyBarModePolicy.shouldRenderEdgeBoxes(
                hasConfiguredBoxes = false,
                omarchyBarMode = true,
                widgetEditing = false,
            ),
        )
    }

    @Test
    fun favoriteStripStaysHiddenButFavoritesButtonIsVisible() {
        assertFalse(OmarchyBarModePolicy.favoritesInBar())
        assertTrue(OmarchyBarModePolicy.favAppsButtonVisible())
    }

    @Test
    fun omarchyLayoutIsAlwaysOnAndIndependentOfAccessibility() {
        assertTrue(OmarchyBarModePolicy.ALWAYS_OMARCHY_MODE)
        assertTrue(OmarchyBarModePolicy.spacerVisible())
        assertFalse(OmarchyBarModePolicy.appsLabelVisible())
        assertFalse(OmarchyBarModePolicy.accessibilityShortcutVisible(serviceConnected = true))
        assertTrue(OmarchyBarModePolicy.accessibilityShortcutVisible(serviceConnected = false))
        assertTrue(OmarchyBarModePolicy.recentsButtonVisible(serviceConnected = true))
        assertFalse(OmarchyBarModePolicy.recentsButtonVisible(serviceConnected = false))
    }

    @Test
    fun omarchyModeIsIndependentOfAccessibilityServiceState() {
        // Accessibility only controls the right-side shortcut and system nav;
        // it must never change the user's selected launcher layout.
        assertTrue(OmarchyBarModePolicy.accessibilityShortcutVisible(serviceConnected = false))
        assertFalse(OmarchyBarModePolicy.accessibilityShortcutVisible(serviceConnected = true))
    }
}
