package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerConfigEditorTest {
    @Test
    fun storesAndClearsPeerWithoutChangingUnknownSettings() {
        val original = """{"accent":"#66e0ff","custom":{"keep":true}}"""
        val peer = OmarchyPeer("192.168.1.31", 8753, "desk one")

        val stored = JSONObject(PeerConfigEditor.store(original, peer))
        assertEquals("#66e0ff", stored.getString("accent"))
        assertTrue(stored.getJSONObject("custom").getBoolean("keep"))
        assertEquals("192.168.1.31", stored.getJSONObject("omarchyPeer").getString("ip"))
        assertEquals(peer, PeerConfigEditor.read(stored.toString()))

        val cleared = JSONObject(PeerConfigEditor.store(stored.toString(), null))
        assertTrue(cleared.has("omarchyPeer"))
        assertTrue(cleared.isNull("omarchyPeer"))
        assertFalse(PeerConfigEditor.read(cleared.toString()) != null)
    }

    @Test
    fun readsTheTemporaryWidgetsConfigShapeWithPeerDefaults() {
        val source = """{
          "settings": {
            "keep": true,
            "omarchyPeer": {"ip":"desktop.local","extension":9}
          }
        }"""

        assertEquals(
            OmarchyPeer("desktop.local", 8753, "omarchy-pc"),
            PeerConfigEditor.read(source),
        )
    }

    @Test
    fun storingIntoTemporaryShapeMigratesPeerToRootAndKeepsNestedExtensions() {
        val source = """{
          "settings": {
            "keep": true,
            "omarchyPeer": {"ip":"old","port":8753,"id":"old"}
          },
          "rootExtension": 4
        }"""
        val peer = OmarchyPeer("new", 9000, "new")

        val stored = JSONObject(PeerConfigEditor.store(source, peer))

        assertEquals(peer, PeerConfigEditor.read(stored.toString()))
        assertEquals(4, stored.getInt("rootExtension"))
        assertTrue(stored.getJSONObject("settings").getBoolean("keep"))
        assertFalse(stored.getJSONObject("settings").has("omarchyPeer"))
    }
}
