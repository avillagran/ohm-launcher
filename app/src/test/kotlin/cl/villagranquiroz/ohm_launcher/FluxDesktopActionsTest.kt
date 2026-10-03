package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FluxDesktopActionsTest {
    @Test fun remoteInputRequiresDesktopIncomingCapability() {
        val id = "1234567890abcdef1234567890abcdef"
        val identity = JSONObject(FluxWire.identity(id, "desktop"))
        val body = identity.getJSONObject("body")
        body.put("incomingCapabilities", org.json.JSONArray().put(FluxWire.SHARE))
        assertFalse(FluxWire.supportsRemoteInput(identity.toString()))
        body.put("incomingCapabilities", org.json.JSONArray().put("kdeconnect.mousepad.request"))
        assertTrue(FluxWire.supportsRemoteInput(identity.toString()))
        assertFalse(FluxWire.supportsInputApproval(identity.toString()))
        body.put("incomingCapabilities", org.json.JSONArray().put(FluxWire.REMOTE_INPUT_REQUEST)
            .put("flux.input.request.v2"))
        assertTrue(FluxWire.supportsInputApproval(identity.toString()))
        body.put("incomingCapabilities", org.json.JSONArray().put("flux.input.request"))
        assertFalse(FluxWire.supportsInputApproval(identity.toString()))
        body.put("incomingCapabilities", org.json.JSONArray().put("not.kdeconnect.mousepad.request"))
        assertFalse(FluxWire.supportsRemoteInput(identity.toString()))
    }

    @Test fun directEditionAdvertisesRemoteInputStateAndRequestsOnly() {
        val id = "1234567890abcdef1234567890abcdef"
        val direct = JSONObject(FluxWire.identity(id, "Ohm", playStore = false)).getJSONObject("body")
        val play = JSONObject(FluxWire.identity(id, "Ohm", playStore = true)).getJSONObject("body")
        fun capabilities(body: JSONObject, key: String) = body.getJSONArray(key).let { array ->
            (0 until array.length()).map(array::getString)
        }
        assertTrue(FluxWire.REMOTE_INPUT_STATE in capabilities(direct, "incomingCapabilities"))
        assertTrue(FluxWire.REMOTE_INPUT_REQUEST in capabilities(direct, "outgoingCapabilities"))
        assertTrue(FluxWire.INPUT_APPROVAL in capabilities(direct, "outgoingCapabilities"))
        assertFalse(FluxWire.REMOTE_INPUT_STATE in capabilities(play, "incomingCapabilities"))
        assertFalse(FluxWire.REMOTE_INPUT_REQUEST in capabilities(play, "outgoingCapabilities"))
        assertFalse(FluxWire.INPUT_APPROVAL in capabilities(play, "outgoingCapabilities"))
    }

    @Test fun remoteInputStateAcceptsOnlyCorrelatedBooleanOnAuthenticatedLink() {
        val id = "123e4567-e89b-42d3-a456-426614174000"
        assertEquals(true, FluxWire.remoteInputState("flux.input", JSONObject().put("enabled", true).put("requestId", id))?.enabled)
        assertEquals(false, FluxWire.remoteInputState("flux.input", JSONObject().put("enabled", false).put("requestId", id))?.enabled)
        assertEquals(null, FluxWire.remoteInputState("flux.input", JSONObject().put("enabled", "true")))
        assertEquals(null, FluxWire.remoteInputState("flux.input", JSONObject().put("enabled", true).put("other", 1)))
        assertEquals(null, FluxWire.remoteInputState("flux.input", JSONObject()))
        assertEquals(null, FluxWire.remoteInputState(FluxWire.PING, JSONObject().put("enabled", true)))
    }
    @Test fun approvalRequestFramesCarryExactCanonicalIdAndBoolean() {
        val id = "123e4567-e89b-42d3-a456-426614174000"
        for (requested in listOf(true, false)) {
            val frame = FluxWire.inputApprovalPacket(requested, id)
            assertTrue(frame.endsWith("\n"))
            val packet = JSONObject(frame.trim())
            assertEquals(FluxWire.INPUT_APPROVAL, packet.getString("type"))
            val body = packet.getJSONObject("body")
            assertEquals(setOf("request", "requestId"), body.keys().asSequence().toSet())
            assertEquals(requested, body.getBoolean("request"))
            assertEquals(id, body.getString("requestId"))
        }
        for (bad in listOf("", id.uppercase(), "123e4567-e89b-12d3-a456-426614174000", "not-a-uuid"))
            assertThrows(IllegalArgumentException::class.java) { FluxWire.inputApprovalPacket(true, bad) }
    }
    @Test fun correlatedStatusRequiresExactShapeAndCanonicalId() {
        val id = "123e4567-e89b-42d3-a456-426614174000"
        val good = JSONObject().put("enabled", true).put("requestId", id)
        assertEquals(true, FluxWire.remoteInputState("flux.input", good)?.enabled)
        assertEquals(id, FluxWire.remoteInputState("flux.input", good)?.requestId)
        for (body in listOf(JSONObject().put("enabled", true),
            JSONObject().put("enabled", false).put("requestId", id.uppercase()),
            JSONObject().put("enabled", "true").put("requestId", id),
            JSONObject().put("enabled", true).put("requestId", id).put("extra", 1)))
            assertEquals(null, FluxWire.remoteInputState("flux.input", body))
    }
    @Test fun remoteInputStateDispatchRejectsStaleOrUnpairedControlSessions() {
        val current = Any()
        val stale = Any()
        val values = mutableListOf<FluxWire.InputState>()
        fun deliver(session: Any, paired: Boolean, capable: Boolean, open: Boolean, enabled: Any?) {
            FluxInputStateDispatch.deliver(FluxWire.REMOTE_INPUT_STATE,
                JSONObject().put("enabled", enabled).put("requestId", "123e4567-e89b-42d3-a456-426614174000"), session, { current },
                paired, capable, open) { values.add(it) }
        }
        deliver(stale, true, true, true, true)
        deliver(current, false, true, true, true)
        deliver(current, true, false, true, true)
        deliver(current, true, true, false, true)
        deliver(current, true, true, true, "true")
        assertTrue(values.isEmpty())
        deliver(current, true, true, true, true)
        deliver(current, true, true, true, false)
        assertEquals(listOf(true, false), values.map { it.enabled })
    }
    private val sent = mutableListOf<Pair<String, JSONObject>>()
    private var paired = true
    private val actions = FluxDesktopActions({ paired }) { type, body -> sent.add(type to body) }

    @Test fun sendsOnlyAdvertisedOutboundPacketTypes() {
        val identity = JSONObject(FluxWire.identity("1234567890abcdef1234567890abcdef", "Ohm")).getJSONObject("body")
        val outgoing = identity.getJSONArray("outgoingCapabilities")
        assertEquals(setOf(FluxWire.SHARE, FluxWire.PING, FluxWire.CLIPBOARD, FluxWire.BATTERY, FluxWire.NOTIFICATION, FluxWire.SCREEN, FluxWire.MPRIS_REQUEST, FluxWire.TUNNEL, FluxWire.REMOTE_INPUT_REQUEST, FluxWire.INPUT_APPROVAL, FluxWire.OMARCHY_THEME_SELECT, FluxWallpaper.TYPE),
            (0 until outgoing.length()).map(outgoing::getString).toSet())
        val incoming = identity.getJSONArray("incomingCapabilities")
        assertEquals(6, incoming.length())
        assertEquals(FluxWire.MPRIS, incoming.getString(0))
        assertEquals(FluxWire.SHARE, incoming.getString(1))
        assertEquals(FluxWire.REMOTE_INPUT_STATE, incoming.getString(2))
        assertEquals(FluxWire.OMARCHY_THEME, incoming.getString(3))
        assertEquals(FluxWire.OMARCHY_THEME_SELECTED, incoming.getString(4))
    }

    @Test fun sendsPingWithOptionalBoundedMessage() {
        actions.sendPing()
        assertEquals(FluxWire.PING, sent.last().first)
        assertEquals(0, sent.last().second.length())
        actions.sendPing("hello")
        assertEquals("hello", sent.last().second.getString("message"))
        assertThrows(IllegalArgumentException::class.java) { actions.sendPing("x".repeat(257)) }
    }

    @Test fun sendsClipboardAsContentNotShareText() {
        actions.sendClipboard("copied\ntext")
        assertEquals(FluxWire.CLIPBOARD, sent.last().first)
        assertEquals("copied\ntext", sent.last().second.getString("content"))
        assertThrows(IllegalArgumentException::class.java) { actions.sendClipboard("") }
        assertThrows(IllegalArgumentException::class.java) { actions.sendClipboard("é".repeat(32769)) }
    }

    @Test fun sharesUrlAsActualUrlFieldWithHttpSchemeAndNoCredentials() {
        actions.sendUrl("https://example.org/path?q=1")
        assertEquals(FluxWire.SHARE, sent.last().first)
        assertEquals("https://example.org/path?q=1", sent.last().second.getString("url"))
        assertFalse(sent.last().second.has("text"))
        for (url in listOf("javascript:alert(1)", "file:///secret", "https://user:pass@example.org/", "https://", "https://example.org/" + "a".repeat(2049))) {
            assertThrows(IllegalArgumentException::class.java) { actions.sendUrl(url) }
        }
    }

    @Test fun sendsBatteryWithLowThresholdOnlyWhenNotCharging() {
        actions.sendBattery(15, false)
        assertEquals(FluxWire.BATTERY, sent.last().first)
        assertEquals(15, sent.last().second.getInt("currentCharge"))
        assertFalse(sent.last().second.getBoolean("isCharging"))
        assertEquals(1, sent.last().second.getInt("thresholdEvent"))
        actions.sendBattery(15, true)
        assertEquals(0, sent.last().second.getInt("thresholdEvent"))
        assertThrows(IllegalArgumentException::class.java) { actions.sendBattery(-1, false) }
        assertThrows(IllegalArgumentException::class.java) { actions.sendBattery(101, false) }
    }

    @Test fun unpairedPeerCannotSendAnyDesktopAction() {
        paired = false
        assertThrows(IllegalStateException::class.java) { actions.sendPing() }
        assertThrows(IllegalStateException::class.java) { actions.sendClipboard("text") }
        assertThrows(IllegalStateException::class.java) { actions.sendUrl("https://example.org") }
        assertThrows(IllegalStateException::class.java) { actions.sendBattery(50, false) }
        assertTrue(sent.isEmpty())
    }
}
