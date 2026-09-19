package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmarchyMenuBehaviorTest {
    @Test
    fun searchTraversesAllSubmenusAndAdditionalApps() {
        val menu = listOf(
            OmarchyMenuEntry(
                icon = "system",
                label = "System",
                children = listOf(OmarchyMenuEntry(icon = "storage", label = "Storage")),
            ),
        )
        val apps = listOf(OmarchyMenuEntry(icon = "app", label = "Firefox", detail = "Application"))

        assertEquals(listOf("Storage"), OmarchyMenuSearch.results(menu, apps, "storage").map { it.label })
        assertEquals(listOf("Firefox"), OmarchyMenuSearch.results(menu, apps, "fire").map { it.label })
    }

    @Test
    fun searchListsEveryMatchingMenuBeforeApplications() {
        val firefox = OmarchyMenuEntry(
            icon = "app",
            label = "Firefox",
            detail = "org.mozilla.firefox",
            iconKey = "org.mozilla.firefox/Main",
        )
        val menu = listOf(
            OmarchyMenuEntry(icon = "apps", label = "Applications", children = listOf(firefox)),
            OmarchyMenuEntry(
                icon = "system",
                label = "System",
                children = listOf(OmarchyMenuEntry(icon = "settings", label = "Firefox settings")),
            ),
        )

        assertEquals(
            listOf("Firefox settings", "Firefox"),
            OmarchyMenuSearch.results(menu, listOf(firefox), "firefox").map { it.label },
        )
    }

    @Test
    fun androidBackReturnsFromSubmenuBeforeClosingRootMenu() {
        assertEquals(OmarchyMenuBackAction.GO_BACK, OmarchyMenuBackPolicy.action(levelCount = 2))
        assertEquals(OmarchyMenuBackAction.CLOSE, OmarchyMenuBackPolicy.action(levelCount = 1))
    }

    @Test
    fun focusedSearchMovesCardAboveTheKeyboardImmediately() {
        assertEquals(138, OmarchyMenuCardPlacement.top(searchFocused = true, centeredTop = 700, statusBar = 90, gap = 48))
        assertEquals(700, OmarchyMenuCardPlacement.top(searchFocused = false, centeredTop = 700, statusBar = 90, gap = 48))
    }

    @Test
    fun submenuExposesATouchBackControl() {
        assertFalse(OmarchyMenuHeaderPolicy.showBack(levelCount = 1))
        assertTrue(OmarchyMenuHeaderPolicy.showBack(levelCount = 2))
    }

    @Test
    fun desktopDoubleTapOpensTypingWhileLongPressOpensNormalMenu() {
        assertFalse(OmarchyMenuInputPolicy.focusInput(OmarchyMenuOpenTrigger.LONG_PRESS))
        assertTrue(OmarchyMenuInputPolicy.focusInput(OmarchyMenuOpenTrigger.SWIPE_UP))
        assertTrue(OmarchyMenuInputPolicy.focusInput(OmarchyMenuOpenTrigger.DOUBLE_TAP))
        assertFalse(OmarchyMenuInputPolicy.focusInput(OmarchyMenuOpenTrigger.LOGO_TAP))
    }

    @Test
    fun hidingTheImeClearsMenuInputFocus() {
        assertTrue(OmarchyMenuFocusPolicy.shouldClearFocus(inputFocused = true, imeWasVisible = true, imeVisible = false))
        assertFalse(OmarchyMenuFocusPolicy.shouldClearFocus(inputFocused = true, imeWasVisible = false, imeVisible = false))
        assertFalse(OmarchyMenuFocusPolicy.shouldClearFocus(inputFocused = false, imeWasVisible = true, imeVisible = false))
    }

    @Test
    fun hidingTheImeClosesTheAppsOnlyMenu() {
        assertTrue(
            OmarchyMenuFocusPolicy.shouldDismiss(
                mode = OmarchyMenuMode.APPS_ONLY,
                inputFocused = true,
                imeWasVisible = true,
                imeVisible = false,
            ),
        )
        assertFalse(
            OmarchyMenuFocusPolicy.shouldDismiss(
                mode = OmarchyMenuMode.FULL,
                inputFocused = true,
                imeWasVisible = true,
                imeVisible = false,
            ),
        )
        assertFalse(
            OmarchyMenuFocusPolicy.shouldDismiss(
                mode = OmarchyMenuMode.APPS_ONLY,
                inputFocused = true,
                imeWasVisible = false,
                imeVisible = false,
            ),
        )
    }

    @Test
    fun repeatedImeChecksCannotRestartMenuDismissal() {
        val guard = OmarchyMenuDismissGuard()

        assertTrue(guard.begin())
        assertFalse(guard.begin())
    }

    @Test
    fun systemMenuAlwaysExposesConfigurationShortcuts() {
        assertTrue(OmarchySystemMenuPolicy.showDefaultLauncherShortcut(alreadyDefault = true))
        assertTrue(OmarchySystemMenuPolicy.showNotificationAccessShortcut(alreadyEnabled = true))
    }

    @Test
    fun systemNavigationButtonBarKeepsTheLauncherBackground() {
        assertEquals(0xFF1A1B26.toInt(), LauncherSystemBarPolicy.navigationBarColor())
    }

    @Test
    fun filteringAppsDoesNotLoadTheirIconsEagerly() {
        var loaded = false
        val app = OmarchyMenuEntry(
            icon = "app",
            label = "Firefox",
            iconLoader = {
                loaded = true
                null
            },
        )

        assertEquals(listOf("Firefox"), OmarchyMenuSearch.results(emptyList(), listOf(app), "fire").map { it.label })
        assertFalse(loaded)
    }

    @Test
    fun appsButtonSearchesOnlyApplications() {
        val settings = OmarchyMenuEntry(icon = "settings", label = "Firefox settings")
        val firefox = OmarchyMenuEntry(icon = "app", label = "Firefox", detail = "org.mozilla.firefox")

        assertEquals(
            listOf("Firefox"),
            OmarchyMenuSearch.results(
                roots = listOf(settings),
                additionalEntries = listOf(firefox),
                rawQuery = "firefox",
                mode = OmarchyMenuSearchMode.APPS_ONLY,
            ).map { it.label },
        )
    }

    @Test
    fun appsButtonPlacesItsSearchInputBelowResults() {
        assertEquals(OmarchyMenuInputPlacement.BOTTOM, OmarchyMenuMode.APPS_ONLY.inputPlacement)
        assertEquals(OmarchyMenuInputPlacement.TOP, OmarchyMenuMode.FULL.inputPlacement)
    }

    @Test
    fun appsSearchMenuIsTwentyPercentShorterThanTheFullMenu() {
        assertEquals(720, OmarchyMenuHeightPolicy.maximumHeight(1000, OmarchyMenuMode.FULL))
        assertEquals(576, OmarchyMenuHeightPolicy.maximumHeight(1000, OmarchyMenuMode.APPS_ONLY))
    }

    @Test
    fun oneBackgroundTapDismissesTheMenuEvenWhileSearchIsFocused() {
        assertTrue(OmarchyMenuDismissPolicy.isBackgroundTap(20f, 500f, 96, 170, 1124, 1640))
        assertFalse(OmarchyMenuDismissPolicy.isBackgroundTap(200f, 500f, 96, 170, 1124, 1640))
    }

    @Test
    fun appsOnlyMenuKeepsItsBottomEdgeFixedWhenResultCountChanges() {
        assertEquals(120, OmarchyMenuCardHeightPolicy.height(120, 576, OmarchyMenuMode.APPS_ONLY))
        assertEquals(400, OmarchyMenuCardHeightPolicy.height(400, 720, OmarchyMenuMode.FULL))
        assertEquals(1640, OmarchyMenuAvailableHeightPolicy.bottom(2640, imeBottom = 1000, OmarchyMenuMode.APPS_ONLY))
        assertEquals(2640, OmarchyMenuAvailableHeightPolicy.bottom(2640, imeBottom = 1000, OmarchyMenuMode.FULL))
        assertEquals(1624, OmarchyMenuBottomAnchorPolicy.anchor(null, availableBottom = 1640, gap = 16))
        assertEquals(1624, OmarchyMenuBottomAnchorPolicy.anchor(1624, availableBottom = 1610, gap = 16))
        assertEquals(1654, OmarchyMenuBottomAnchorPolicy.adjustForImeChange(1624, oldImeBottom = 1000, newImeBottom = 1030))
        assertEquals(
            584,
            OmarchyMenuVerticalPlacement.top(
                viewportHeight = 1000,
                cardHeight = 400,
                mode = OmarchyMenuMode.APPS_ONLY,
                searchFocused = true,
                centeredTop = 300,
                statusBar = 24,
                gap = 16,
            ),
        )
        assertEquals(
            784,
            OmarchyMenuVerticalPlacement.top(
                viewportHeight = 1000,
                cardHeight = 200,
                mode = OmarchyMenuMode.APPS_ONLY,
                searchFocused = true,
                centeredTop = 400,
                statusBar = 24,
                gap = 16,
            ),
        )
        assertEquals(
            300,
            OmarchyMenuVerticalPlacement.top(
                viewportHeight = 1000,
                cardHeight = 400,
                mode = OmarchyMenuMode.APPS_ONLY,
                searchFocused = false,
                centeredTop = 300,
                statusBar = 24,
                gap = 16,
            ),
        )
    }
}
