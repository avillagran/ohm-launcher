package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherInteractionParityTest {
    @Test
    fun screenSharePromptsForRemoteControlWhenAccessibilityIsDisabled() {
        assertTrue(ScreenSharePermissionPolicy.shouldPrompt(started = true, accessibilityEnabled = false))
        assertFalse(ScreenSharePermissionPolicy.shouldPrompt(started = false, accessibilityEnabled = false))
        assertFalse(ScreenSharePermissionPolicy.shouldPrompt(started = true, accessibilityEnabled = true))
    }

    @Test
    fun routesVerticalGesturesToExactlyOneSurface() {
        assertEquals(
            LauncherVerticalAction.CLOSE_QUAKE,
            LauncherGesturePolicy.verticalAction(
                quakeVisible = true,
                drawerVisible = false,
                deltaY = -180f,
                startedInLowerHalf = true,
            ),
        )
        assertEquals(
            LauncherVerticalAction.OPEN_DRAWER,
            LauncherGesturePolicy.verticalAction(
                quakeVisible = false,
                drawerVisible = false,
                deltaY = -180f,
                startedInLowerHalf = true,
            ),
        )
        assertEquals(
            LauncherVerticalAction.CLOSE_DRAWER,
            LauncherGesturePolicy.verticalAction(
                quakeVisible = false,
                drawerVisible = true,
                deltaY = 180f,
                startedInLowerHalf = false,
            ),
        )
    }

    @Test
    fun suppressesAppLaunchAfterDrawerDismissDrag() {
        assertFalse(DrawerTapPolicy.mayLaunchApp(verticalDragDistance = 48f, touchSlop = 16f))
        assertTrue(DrawerTapPolicy.mayLaunchApp(verticalDragDistance = 8f, touchSlop = 16f))
    }

    @Test
    fun backgroundPressDismissesFocusedCommandBar() {
        assertTrue(CommandBarFocusPolicy.shouldDismiss(hasFocus = true, backgroundPressed = true))
        assertFalse(CommandBarFocusPolicy.shouldDismiss(hasFocus = false, backgroundPressed = true))
        assertFalse(CommandBarFocusPolicy.shouldDismiss(hasFocus = true, backgroundPressed = false))
    }

    @Test
    fun ranksPreNormalizedAppSearchWithoutRebuildingCatalog() {
        val index = AppSearchIndex(
            listOf(
                SearchableApp("org.mozilla.firefox/.App", "Firefox", "org.mozilla.firefox"),
                SearchableApp("com.android.settings/.Settings", "Configuración", "com.android.settings"),
                SearchableApp("com.termux/.Home", "Termux", "com.termux"),
            ),
        )

        assertEquals("Firefox", index.search("fire", 3).single().label)
        assertEquals("Configuración", index.search("configuracion", 3).single().label)
        assertEquals("Termux", index.search("com.term", 3).single().label)
    }

    @Test
    fun quakeHeightNeverOverlapsVisibleIme() {
        assertEquals(1844, QuakePanelGeometry.height(screenHeight = 2712, statusBar = 80, imeHeight = 0))
        assertEquals(1512, QuakePanelGeometry.height(screenHeight = 2712, statusBar = 80, imeHeight = 1200))
    }

    @Test
    fun edgeBoxExpansionButtonRemainsOptionalAndCompatible() {
        val hidden = EdgeBoxConfig.parse(
            JSONObject("""{"id":"a","items":[{},{}],"showExpandButton":false}"""),
            0,
        )
        val legacy = EdgeBoxConfig.parse(JSONObject("""{"id":"b","items":[{},{}]}"""), 0)

        assertFalse(hidden.showExpandButton)
        assertTrue(legacy.showExpandButton)
    }

    @Test
    fun desktopTransitionDirectionMatchesSwipeDirection() {
        assertEquals(1f, DesktopTransitionPolicy.entryDirection(previous = 0, next = 1), 0f)
        assertEquals(-1f, DesktopTransitionPolicy.entryDirection(previous = 2, next = 1), 0f)
    }

    @Test
    fun desktopTitleIsHiddenWhenThereIsNothingToNavigate() {
        assertEquals(null, DesktopTitlePolicy.text("Inicio", index = 0, desktopCount = 1))
        assertEquals("Trabajo  2/3", DesktopTitlePolicy.text("Trabajo", index = 1, desktopCount = 3))
    }

    @Test
    fun omarchyMenuOffersBothQrDirections() {
        assertEquals(
            listOf("Bluetooth", "Mostrar QR", "Leer QR"),
            OmarchyMenuAction.entries.map(OmarchyMenuAction::label),
        )
    }

    @Test
    fun orbitalMenuPlacesEveryActionOnTwoDistinctRings() {
        val points = OrbitalMenuGeometry.positions(count = 12, width = 1000, height = 1800)

        assertEquals(12, points.size)
        assertTrue(points.take(8).all { it.ring == 0 })
        assertTrue(points.drop(8).all { it.ring == 1 })
        assertEquals(12, points.map { it.x to it.y }.distinct().size)
    }

    @Test
    fun recognizesFlutterParticleClockAliases() {
        assertTrue(ClockStylePolicy.isParticle("particles"))
        assertTrue(ClockStylePolicy.isParticle("arrival"))
        assertTrue(ClockStylePolicy.isParticle("hourglass"))
        assertFalse(ClockStylePolicy.isParticle("ticker"))
    }
}
