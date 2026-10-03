package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FluxNotificationActionsTest {
    @Test fun explicitSnapshotContainsOnlyMinimalFields() {
        val frames = mutableListOf<Pair<String, JSONObject>>()
        val actions = FluxNotificationActions({ true }) { type, body -> frames += type to body }
        actions.sendNotification("chosen-1", "Messages", "A title", "A body", 1_727_260_000_000L)
        assertEquals(1, frames.size)
        assertEquals(FluxWire.NOTIFICATION, frames.single().first)
        val body = frames.single().second
        assertEquals(setOf("id", "appName", "title", "text", "time"), body.keys().asSequence().toSet())
        assertEquals("chosen-1", body.getString("id"))
        assertEquals("Messages", body.getString("appName"))
        assertEquals("A title", body.getString("title"))
        assertEquals("A body", body.getString("text"))
        assertEquals("1727260000000", body.getString("time"))
        val packet = JSONObject(FluxWire.packet(frames.single().first, body))
        assertFalse(packet.has("payloadSize"))
        assertFalse(packet.has("payloadTransferInfo"))
        assertEquals(setOf("id", "appName", "title", "text", "time"), packet.getJSONObject("body").keys().asSequence().toSet())
    }

    @Test fun unpairedSnapshotNeverSendsEvenValidFields() {
        var sends = 0
        val actions = FluxNotificationActions({ false }) { _, _ -> sends++ }
        assertThrows(IllegalStateException::class.java) {
            actions.sendNotification("chosen-1", "Messages", "title", "text", 1_727_260_000_000L)
        }
        assertEquals(0, sends)
    }

    @Test fun rejectsEmptyAndOversizedFieldsWithoutSending() {
        var sends = 0
        val actions = FluxNotificationActions({ true }) { _, _ -> sends++ }
        val valid = arrayOf("id", "app", "title", "text")
        for (index in listOf(0, 1)) {
            val fields = valid.copyOf().also { it[index] = "" }
            assertThrows(IllegalArgumentException::class.java) {
                actions.sendNotification(fields[0], fields[1], fields[2], fields[3], 1L)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            actions.sendNotification("id", "app", "", "", 1L)
        }
        for (index in valid.indices) {
            val fields = valid.copyOf().also { it[index] = "🔒".repeat(5000) }
            assertThrows(IllegalArgumentException::class.java) {
                actions.sendNotification(fields[0], fields[1], fields[2], fields[3], 1L)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            actions.sendNotification("id", "app", "title", "text", 0)
        }
        assertEquals(0, sends)
    }

    @Test fun escapedPayloadCannotExceedControlFrameLimit() {
        var sends = 0
        val actions = FluxNotificationActions({ true }) { _, _ -> sends++ }
        assertThrows(IllegalArgumentException::class.java) {
            actions.sendNotification("id", "app", "title", "\u0001".repeat(4096), 1L)
        }
        assertEquals(0, sends)
    }

    @Test fun identityAdvertisesOutgoingOnlyAndPlayPolicyRestrictsAction() {
        val identity = JSONObject(FluxWire.identity("92baf92fcbb34073aa190966c9de14e6", "Ohm")).getJSONObject("body")
        val outgoing = identity.getJSONArray("outgoingCapabilities")
        assertTrue((0 until outgoing.length()).any { outgoing.getString(it) == FluxWire.NOTIFICATION })
        val incoming = identity.getJSONArray("incomingCapabilities")
        assertFalse((0 until incoming.length()).any { incoming.getString(it) == FluxWire.NOTIFICATION })
        assertTrue(PeerActionPolicy.supports(PeerTransport.FLUX, PeerAction.SEND_NOTIFICATION, false))
        assertFalse(PeerActionPolicy.supports(PeerTransport.FLUX, PeerAction.SEND_NOTIFICATION, true))
        assertFalse(PeerActionPolicy.supports(PeerTransport.OMARCHY_LINK, PeerAction.SEND_NOTIFICATION, false))
    }
}
