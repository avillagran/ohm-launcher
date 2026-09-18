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
    fun desktopGesturesOpenTheMenuReadyForTyping() {
        assertTrue(OmarchyMenuInputPolicy.focusInput(OmarchyMenuOpenTrigger.LONG_PRESS))
        assertTrue(OmarchyMenuInputPolicy.focusInput(OmarchyMenuOpenTrigger.SWIPE_UP))
        assertFalse(OmarchyMenuInputPolicy.focusInput(OmarchyMenuOpenTrigger.DOUBLE_TAP))
        assertFalse(OmarchyMenuInputPolicy.focusInput(OmarchyMenuOpenTrigger.LOGO_TAP))
    }

    @Test
    fun hidingTheImeClearsMenuInputFocus() {
        assertTrue(OmarchyMenuFocusPolicy.shouldClearFocus(inputFocused = true, imeWasVisible = true, imeVisible = false))
        assertFalse(OmarchyMenuFocusPolicy.shouldClearFocus(inputFocused = true, imeWasVisible = false, imeVisible = false))
        assertFalse(OmarchyMenuFocusPolicy.shouldClearFocus(inputFocused = false, imeWasVisible = true, imeVisible = false))
    }

    @Test
    fun systemMenuAlwaysExposesConfigurationShortcuts() {
        assertTrue(OmarchySystemMenuPolicy.showDefaultLauncherShortcut(alreadyDefault = true))
        assertTrue(OmarchySystemMenuPolicy.showNotificationAccessShortcut(alreadyEnabled = true))
    }

    @Test
    fun systemNavigationButtonBarRemainsTransparent() {
        assertEquals(0, LauncherSystemBarPolicy.navigationBarColor())
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
}
