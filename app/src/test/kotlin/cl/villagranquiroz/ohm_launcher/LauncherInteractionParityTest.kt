package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherInteractionParityTest {
    @Test
    fun orbitalButtonsUseNerdFontPrivateUseGlyphs() {
        val icons = listOf(NerdGlyph.ADD, NerdGlyph.BOX, NerdGlyph.SETTINGS, NerdGlyph.TRASH)

        assertTrue(icons.all { it.codePointAt(0) in 0xE000..0xF8FF })
        assertEquals(icons.size, icons.distinct().size)
    }

    @Test
    fun edgeBoxSettingsUseTheOmarchyMenuActionModel() {
        val box = EdgeBoxConfig.parse(
            JSONObject("""{"id":"box","compact":true,"showTitle":false,"showExpandButton":true,"items":[]}"""),
            0,
        )

        assertEquals(
            listOf(
                EdgeBoxMenuAction.MOVE_TOP,
                EdgeBoxMenuAction.MOVE_BOTTOM,
                EdgeBoxMenuAction.MOVE_LEFT,
                EdgeBoxMenuAction.MOVE_RIGHT,
                EdgeBoxMenuAction.ADD_APPLICATION,
                EdgeBoxMenuAction.EXPAND,
                EdgeBoxMenuAction.SHOW_TITLE,
                EdgeBoxMenuAction.HIDE_EXPAND_BUTTON,
                EdgeBoxMenuAction.REMOVE,
            ),
            EdgeBoxMenuPolicy.actions(box),
        )
    }

    @Test
    fun sharedEdgeItemsReceiveAdjacentNonOverlappingOffsets() {
        assertEquals(listOf(-30f, 55f), SharedEdgeLayout.centerOffsets(listOf(100, 50), spacing = 10))
    }

    @Test
    fun launcherBarDragStartsOnlyAfterCrossingTouchSlop() {
        val drag = LauncherBarDragState()

        assertFalse(drag.update(15f))
        assertTrue(drag.update(16f))
        assertTrue(drag.update(4f))
    }

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
            LauncherVerticalAction.OPEN_FAVORITE_APPS,
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
    fun themeBackgroundRefreshPreservesLiveFavorites() {
        val live = listOf("app/live", "app/new")
        val staleConfig = listOf("app/live")

        assertEquals(live, FavoriteConfigMergePolicy.resolve(staleConfig, live, preserveCurrent = true))
        assertEquals(staleConfig, FavoriteConfigMergePolicy.resolve(staleConfig, live, preserveCurrent = false))
    }

    @Test
    fun doubleTapAndLongPressOpenMenuWithDistinctInputModes() {
        assertEquals(BackgroundTapAction.OPEN_OMARCHY_MENU, BackgroundTapPolicy.onDoubleTap(editing = false))
        assertEquals(BackgroundTapAction.EXIT_EDIT_AND_OPEN_OMARCHY_MENU, BackgroundTapPolicy.onDoubleTap(editing = true))
        assertEquals(BackgroundTapAction.OPEN_OMARCHY_MENU, BackgroundTapPolicy.onLongPress(editing = false))
        assertEquals(BackgroundTapAction.EXIT_EDIT_AND_OPEN_OMARCHY_MENU, BackgroundTapPolicy.onLongPress(editing = true))
    }

    @Test
    fun backExitsWidgetEditModeBeforeLeavingTheLauncher() {
        assertTrue(WidgetEditExitPolicy.consumeBack(editing = true))
        assertFalse(WidgetEditExitPolicy.consumeBack(editing = false))
    }

    @Test
    fun themeAndBackgroundSelectorsOwnSwipesWithoutChangingDesktop() {
        assertTrue(LauncherGestureGate.routeToDesktop(widgetEditing = false, selectorVisible = false))
        assertFalse(LauncherGestureGate.routeToDesktop(widgetEditing = false, selectorVisible = true))
        assertFalse(LauncherGestureGate.routeToDesktop(widgetEditing = true, selectorVisible = false))
    }

    @Test
    fun omarchySearchBarIsFlushWithItsConfiguredScreenEdge() {
        LauncherEdge.entries.forEach { edge ->
            assertEquals(BarInsets(0, 0, 0, 0), OmarchyBarInsets.forEdge(edge, 14))
        }
    }

    @Test
    fun unifiedBottomBarScalesFavoritesBeforeEnablingHorizontalScroll() {
        assertEquals(38, UnifiedLauncherBarPolicy.favoriteIconSize(availableWidth = 420, fixedButtonsWidth = 96, favoriteCount = 4))
        assertFalse(UnifiedLauncherBarPolicy.favoritesScrollable(availableWidth = 420, fixedButtonsWidth = 96, favoriteCount = 4))

        assertEquals(27, UnifiedLauncherBarPolicy.favoriteIconSize(availableWidth = 360, fixedButtonsWidth = 96, favoriteCount = 12))
        assertTrue(UnifiedLauncherBarPolicy.favoritesScrollable(availableWidth = 360, fixedButtonsWidth = 96, favoriteCount = 12))
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
    fun nineActionBoxMenuUsesOneWideRingWithoutAStackedOuterButton() {
        val points = OrbitalMenuGeometry.positions(count = 9, width = 1080, height = 2400)

        assertTrue(points.all { it.ring == 0 })
        assertEquals(9, points.map { it.x to it.y }.distinct().size)
    }

    @Test
    fun longPressOrbitalMenuOpensAroundTheFingerAndStaysOnScreen() {
        val points = OrbitalMenuGeometry.positions(
            count = 6,
            width = 1080,
            height = 2400,
            anchorX = 70f,
            anchorY = 2100f,
            itemWidth = 104,
            itemHeight = 84,
        )

        assertTrue(points.all { it.x in 52f..1028f })
        assertTrue(points.all { it.y in 42f..2358f })
        assertTrue(points.map { it.x }.average() < 400.0)
        assertTrue(points.map { it.y }.average() > 1700.0)
    }

    @Test
    fun longPressActionsDeploySequentiallyAndCloseAppearsAfterRelease() {
        assertEquals(listOf(0L, 70L, 140L, 210L), (0..3).map(OrbitalMenuMotion::actionDelayMillis))
        assertEquals(1_000L, OrbitalMenuMotion.CLOSE_REVEAL_DELAY_MILLIS)
        assertEquals(OrbitalMenuPoint(37f, 2363f, 0), OrbitalMenuMotion.closeCenter(10f, 2390f, 1080, 2400, 74, 74))
    }

    @Test
    fun recognizesFlutterParticleClockAliases() {
        assertTrue(ClockStylePolicy.isParticle("particles"))
        assertTrue(ClockStylePolicy.isParticle("arrival"))
        assertTrue(ClockStylePolicy.isParticle("hourglass"))
        assertFalse(ClockStylePolicy.isParticle("ticker"))
    }
}
