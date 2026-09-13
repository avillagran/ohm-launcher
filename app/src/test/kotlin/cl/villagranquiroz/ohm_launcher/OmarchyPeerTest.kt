package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmarchyPeerTest {
    @Test
    fun parsesOmarchyQrUriAndDecodesPeerId() {
        val peer = OmarchyPeerUri.parse("omarchy://192.168.1.44:8753?id=Living%20Room")

        assertEquals(OmarchyPeer("192.168.1.44", 8753, "Living Room"), peer)
        assertEquals("http://192.168.1.44:8753", peer?.httpBaseUrl)
        assertEquals("ws://192.168.1.44:8753/omarchy/ws", peer?.webSocketUrl)
    }

    @Test
    fun supportsBracketedIpv6AndDefaultsIdToHost() {
        val peer = OmarchyPeerUri.parse("omarchy://[fd00::12]:8753")

        assertEquals("fd00::12", peer?.host)
        assertEquals("fd00::12", peer?.id)
        assertEquals("http://[fd00::12]:8753", peer?.httpBaseUrl)
    }

    @Test
    fun rejectsMalformedOrUnsafePeerUris() {
        listOf(
            "http://192.168.1.44:8753?id=pc",
            "omarchy://192.168.1.44",
            "omarchy://user@192.168.1.44:8753",
            "omarchy://192.168.1.44:8753/other",
            "omarchy://192.168.1.44:8753#fragment",
            "omarchy://192.168.1.44:0",
            "omarchy://192.168.1.44:65536",
            "omarchy://192.168.1.44:8753?id=",
        ).forEach { assertNull(it, OmarchyPeerUri.parse(it)) }
    }

    @Test
    fun roundTripsPersistedPeerStateAndRejectsInvalidValues() {
        val peer = OmarchyPeer("192.168.1.44", 8753, "Living Room")

        assertEquals(peer, OmarchyPeer.fromJson(peer.toJson()))
        assertEquals("192.168.1.44", peer.toJson().getString("ip"))
        assertNull(OmarchyPeer.fromJson(JSONObject("""{"ip":"host","port":0,"id":"pc"}""")))
        assertNull(OmarchyPeer.fromJson(JSONObject("""{"ip":"host","port":8753,"id":""}""")))
    }

    @Test
    fun connectionStateOnlyAcceptsLivePeerChanges() {
        val state = OmarchyConnectionState()
        val first = OmarchyPeer("192.168.1.10", 8753, "desktop")
        val other = OmarchyPeer("192.168.1.11", 8753, "other")

        assertFalse(state.isConnected)
        assertTrue(state.connect(first))
        assertFalse(state.connect(other))
        assertEquals(first, state.peer)
        assertTrue(state.setScreenSharing(true))
        assertTrue(state.screenSharing)
        assertTrue(state.disconnect())
        assertFalse(state.isConnected)
        assertFalse(state.screenSharing)
        assertFalse(state.setScreenSharing(true))
    }
}
