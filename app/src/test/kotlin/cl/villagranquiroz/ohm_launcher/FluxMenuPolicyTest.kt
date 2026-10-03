package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.*
import org.junit.Test

class FluxMenuPolicyTest {
    @Test fun submenuResolvesFreshChildrenOnEveryEntry() {
        var rows = listOf(OmarchyMenuEntry("f", "old"))
        val menu = OmarchyMenuEntry("o", "Omarchy", childrenProvider = { rows })
        assertTrue(OmarchyMenuEnterPolicy.shouldOpen(menu))
        assertEquals("old", menu.resolvedChildren().single().label)
        rows = listOf(OmarchyMenuEntry("f", "new"))
        assertEquals("new", menu.resolvedChildren().single().label)
        assertEquals(listOf("new"), OmarchyMenuSearch.results(listOf(menu), emptyList(), "new").map { it.label })
        assertTrue(OmarchyMenuSearch.results(listOf(menu), emptyList(), "old").isEmpty())
    }

    @Test fun replacementClearsOnlyPreviousSessionAndClosesIt() {
        val peers = java.util.concurrent.ConcurrentHashMap<String, Any>()
        val old = Any()
        val next = Any()
        val closed = mutableListOf<Any>()
        peers["desktop"] = old
        FluxMenuPolicy.replacePeer(peers, "desktop", next) { closed += it }
        assertSame(next, peers["desktop"])
        assertEquals(listOf(old), closed)
        FluxMenuPolicy.replacePeer(peers, "desktop", next) { closed += it }
        assertEquals(listOf(old), closed)
    }

    @Test fun rejectedActionOverlayClosesTransientSessionOnce() {
        var closed = 0
        FluxMenuPolicy.showOrClose(closeAfter = true, onCancelled = { closed++ }) { false }
        assertEquals(1, closed)
        FluxMenuPolicy.showOrClose(closeAfter = false, onCancelled = { closed++ }) { false }
        assertEquals(1, closed)
    }
    @Test fun linkRowNeverFallsBackToAvailableFluxPeer() {
        val sent = mutableListOf<String>()
        assertFalse(OmarchyLinkTextPolicy.send<String>(null, allowed = true) { sent.add(it) })
        assertTrue(sent.isEmpty())
        assertTrue(OmarchyLinkTextPolicy.send("link", allowed = true) { sent += it })
        assertEquals(listOf("link"), sent)
        assertFalse(OmarchyLinkTextPolicy.send("link", allowed = false) { sent += it })
        assertEquals(listOf("link"), sent)
    }
    @Test fun oneLivePeerPlacesEveryEligibleActionDirectly() {
        val rows = FluxMenuPolicy.placement(listOf("desktop"), playStore = false)
        assertEquals(1, rows.size)
        assertEquals("desktop", rows.single().peerId)
        assertFalse(rows.single().grouped)
        assertEquals(FluxMenuPolicy.Action.values().toSet() - setOf(FluxMenuPolicy.Action.SCREEN_STOP,
            FluxMenuPolicy.Action.MEDIA, FluxMenuPolicy.Action.REMOTE_INPUT),
            FluxMenuPolicy.actions(screenRunning = false, hasTheme = true).toSet())
        assertTrue(FluxMenuPolicy.Action.MEDIA in FluxMenuPolicy.actions(false, true, hasMedia = true))
    }

    @Test fun manyPeersRemainDistinctAndNoneShowsDiscovery() {
        assertTrue(FluxMenuPolicy.placement(emptyList(), false).isEmpty())
        val rows = FluxMenuPolicy.placement(listOf("a", "b"), false)
        assertEquals(listOf("a", "b"), rows.map { it.peerId })
        assertTrue(rows.all { it.grouped })
        assertEquals(rows.map { it.peerId }.distinct(), rows.map { it.peerId })
    }

    @Test fun screenAndThemeOnlyAppearWhenEligibleAndPlayNeverShowsFlux() {
        val actions = FluxMenuPolicy.actions(screenRunning = true, hasTheme = false)
        assertTrue(FluxMenuPolicy.Action.SCREEN_STOP in actions)
        assertFalse(FluxMenuPolicy.Action.SCREEN_START in actions)
        assertFalse(FluxMenuPolicy.Action.THEME in actions)
        assertTrue(FluxMenuPolicy.placement(listOf("a"), true).isEmpty())
    }

    @Test fun authorizationRejectsReplacementOfflineUnpairedAndPlay() {
        val action = FluxMenuPolicy.Action.FORGET
        assertTrue(FluxMenuPolicy.authorized("a", "a", true, true, false, action))
        assertFalse(FluxMenuPolicy.authorized("a", "b", true, true, false, action))
        assertFalse(FluxMenuPolicy.authorized("a", "a", false, true, false, action))
        assertFalse(FluxMenuPolicy.authorized("a", "a", true, false, false, action))
        assertFalse(FluxMenuPolicy.authorized("a", "a", true, true, true, action))
        assertFalse(FluxMenuPolicy.authorized("a", "a", true, true, false,
            FluxMenuPolicy.Action.SCREEN_STOP, screenRunning = false))
        assertFalse(FluxMenuPolicy.authorized("a", "a", true, true, false,
            FluxMenuPolicy.Action.SCREEN_START, screenRunning = true))
        assertFalse(FluxMenuPolicy.authorized("a", "a", true, true, false, FluxMenuPolicy.Action.THEME, hasTheme = false))
    }
    @Test fun remoteInputActionRequiresPeerCapabilityAndDesktopEnabledState() {
        val off = FluxMenuPolicy.actions(false, false, hasInput = false)
        assertFalse(FluxMenuPolicy.Action.REMOTE_INPUT in off)
        val on = FluxMenuPolicy.actions(false, false, hasInput = true, inputEnabled = true)
        assertFalse(FluxMenuPolicy.Action.REMOTE_INPUT in on)
        assertFalse(FluxMenuPolicy.authorized("desktop", "desktop", true, true, false,
            FluxMenuPolicy.Action.REMOTE_INPUT, hasInput = false))
        assertFalse(FluxMenuPolicy.authorized("desktop", "desktop", true, true, false,
            FluxMenuPolicy.Action.REMOTE_INPUT, hasInput = true, inputEnabled = true))
        assertFalse(FluxMenuPolicy.authorized("desktop", "desktop", true, true, true,
            FluxMenuPolicy.Action.REMOTE_INPUT, hasInput = true))
    }

    @Test fun disabledDesktopDoesNotOfferUnusableTouchpadUnlessApprovalCanBeRequested() {
        val legacy = FluxMenuPolicy.actions(false, false, hasInput = true,
            inputEnabled = false, canRequestInput = false)
        assertFalse(FluxMenuPolicy.Action.REMOTE_INPUT in legacy)
        val requestable = FluxMenuPolicy.actions(false, false, hasInput = true,
            inputEnabled = false, canRequestInput = true)
        assertTrue(FluxMenuPolicy.Action.REMOTE_INPUT in requestable)
        assertFalse(FluxMenuPolicy.authorized("desktop", "desktop", true, true, false,
            FluxMenuPolicy.Action.REMOTE_INPUT, hasInput = true,
            inputEnabled = false, canRequestInput = false))
        assertTrue(FluxMenuPolicy.authorized("desktop", "desktop", true, true, false,
            FluxMenuPolicy.Action.REMOTE_INPUT, hasInput = true,
            inputEnabled = false, canRequestInput = true))
    }
}
