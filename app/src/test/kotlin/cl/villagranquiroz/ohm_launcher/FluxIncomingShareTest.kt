package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FluxIncomingShareTest {
    @Test fun onlyDirectEditionAdvertisesReversePayloadTunnel() {
        val id = "92baf92fcbb34073aa190966c9de14e6"
        fun advertised(play: Boolean): List<String> {
            val body = JSONObject(FluxWire.identity(id, "Ohm", playStore = play)).getJSONObject("body")
            val outgoing = body.getJSONArray("outgoingCapabilities")
            return (0 until outgoing.length()).map(outgoing::getString)
        }
        assertEquals(1, advertised(false).count { it == FluxWire.TUNNEL })
        assertFalse(advertised(true).contains(FluxWire.TUNNEL))
    }

    @Test fun reverseTunnelAnnouncementIsBoundedAndUsesAuthenticatedControlPacket() {
        val token = "0123456789abcdef01234567"
        val packet = JSONObject(FluxWire.tunnelPacket(token, 1739))
        assertEquals("flux.tunnel", packet.getString("type"))
        assertEquals(token, packet.getJSONObject("body").getString("id"))
        assertEquals(1739, packet.getJSONObject("body").getInt("port"))
        assertThrows(IllegalArgumentException::class.java) { FluxWire.tunnelPacket("../bad", 1739) }
        assertThrows(IllegalArgumentException::class.java) { FluxWire.tunnelPacket(token, 1738) }
    }
    private fun packet(body: JSONObject): String = JSONObject().put("id", 17)
        .put("type", "kdeconnect.share.request").put("body", body).toString()

    @Test fun directIdentityAdvertisesInboundShareButPlayDoesNot() {
        val id = "92baf92fcbb34073aa190966c9de14e6"
        fun incoming(play: Boolean): List<String> {
            val body = JSONObject(FluxWire.identity(id, "Ohm", playStore = play)).getJSONObject("body")
            val capabilities = body.getJSONArray("incomingCapabilities")
            return (0 until capabilities.length()).map(capabilities::getString)
        }
        assertTrue(FluxWire.SHARE in incoming(false))
        assertFalse(FluxWire.SHARE in incoming(true))
    }

    @Test fun authenticatedLiveSessionIsRequiredBeforeDeliveringIncomingText() {
        val line = packet(JSONObject().put("text", "hello"))
        val delivered = mutableListOf<FluxIncomingShare>()
        FluxIncomingShareGate.deliver(line, paired = false, live = true) { delivered.add(it) }
        FluxIncomingShareGate.deliver(line, paired = true, live = false) { delivered.add(it) }
        assertTrue(delivered.isEmpty())
        FluxIncomingShareGate.deliver(line, paired = true, live = true) { delivered.add(it) }
        assertEquals(listOf(FluxIncomingShare.Text("hello")), delivered)
    }

    @Test fun malformedOrFileOffersDoNotReachTextShareCallback() {
        val delivered = mutableListOf<FluxIncomingShare>()
        FluxIncomingShareGate.deliver("not json", true, true) { delivered.add(it) }
        val file = JSONObject(packet(JSONObject().put("filename", "safe.txt")))
            .put("payloadSize", 3)
            .put("payloadTransferInfo", JSONObject().put("port", 1739)).toString()
        FluxIncomingShareGate.deliver(file, true, true) { delivered.add(it) }
        assertTrue(delivered.isEmpty())
    }

    @Test fun acceptsTextWithoutSideEffects() {
        assertEquals(FluxIncomingShare.Text("Hello 🌍\nsecond line"),
            FluxIncomingShareParser.parse(packet(JSONObject().put("text", "Hello 🌍\nsecond line"))))
    }

    @Test fun acceptsStrictHttpUrls() {
        assertEquals(FluxIncomingShare.Url("https://example.org/path?q=1"),
            FluxIncomingShareParser.parse(packet(JSONObject().put("url", "https://example.org/path?q=1"))))
    }

    @Test fun rejectsMissingMixedAndFilePayloads() {
        val invalid = listOf(
            JSONObject(), JSONObject().put("text", "a").put("url", "https://example.org"),
            JSONObject().put("text", "a").put("filename", "a.txt"),
            JSONObject().put("url", "https://example.org").put("open", true),
            JSONObject().put("text", "a").put("scan", true),
            JSONObject().put("text", "a").put("numberOfFiles", 1),
            JSONObject().put("text", "a").put("totalPayloadSize", 1),
        )
        invalid.forEach { assertNull(it.toString(), FluxIncomingShareParser.parse(packet(it))) }
        val body = JSONObject().put("text", "a")
        assertNull(FluxIncomingShareParser.parse(JSONObject(packet(body)).put("payloadSize", 1).toString()))
        assertNull(FluxIncomingShareParser.parse(JSONObject(packet(body))
            .put("payloadTransferInfo", JSONObject().put("port", 1739)).toString()))
    }

    @Test fun rejectsMalformedTypesAndUnsupportedMime() {
        val valid = JSONObject().put("text", "a")
        for (line in listOf("{", "[]", "null", "{}", packet(JSONObject().put("text", 42)),
            packet(JSONObject().put("text", JSONObject.NULL)),
            packet(valid.put("mime", "image/png")),
            packet(JSONObject().put("url", "https://example.org").put("mime", "text/plain")),
            JSONObject(packet(JSONObject().put("text", "a"))).put("type", "kdeconnect.clipboard").toString())) {
            assertNull(line, FluxIncomingShareParser.parse(line))
        }
        assertEquals(FluxIncomingShare.Text("a"), FluxIncomingShareParser.parse(
            packet(JSONObject().put("text", "a").put("mime", "text/plain"))))
    }

    @Test fun enforcesUtf8BytesAndTextControls() {
        assertNotNull(FluxIncomingShareParser.parse(packet(JSONObject().put("text", "界".repeat(21845)))))
        for (value in listOf("", "  \n ", "a\u0000b", "a\u001bb", "界".repeat(21846)))
            assertNull(FluxIncomingShareParser.parse(packet(JSONObject().put("text", value))))
    }

    @Test fun rejectsDangerousOrMalformedUrls() {
        for (value in listOf("", "https://", "ftp://example.org", "javascript:alert(1)",
            "//example.org", "https://user:pass@example.org", "https://example.org/a b",
            "https://example.org\n", "https://example.org:99999", "https://example.org/#fragment",
            "https://example.org/" + "a".repeat(2100)))
            assertNull(value, FluxIncomingShareParser.parse(packet(JSONObject().put("url", value))))
        assertNotNull(FluxIncomingShareParser.parse(packet(JSONObject().put("url", "http://localhost:8080/a"))))
    }

    @Test fun rejectsAmbiguousJsonAndAuthority() {
        val prefix = "{\"id\":17,\"type\":\"kdeconnect.share.request\",\"body\":"
        assertNull(FluxIncomingShareParser.parse(prefix + "{\"text\":\"first\",\"text\":\"second\"}}"))
        assertNull(FluxIncomingShareParser.parse(packet(JSONObject().put("text", "a")) + " trailing"))
        for (url in listOf("https://example.org:", "https://example.org:bad", "https://example.org:0"))
            assertNull(url, FluxIncomingShareParser.parse(packet(JSONObject().put("url", url))))
    }

    @Test fun rejectsOversizedAndInvalidEnvelopeWithoutCrashing() {
        val body = JSONObject().put("text", "a")
        assertNull(FluxIncomingShareParser.parse(packet(body).repeat(10_000)))
        assertNull(FluxIncomingShareParser.parse(JSONObject().put("type", "kdeconnect.share.request")
            .put("body", body).toString()))
        assertNull(FluxIncomingShareParser.parse(JSONObject(packet(body)).put("payloadSize", 0).toString()))
        assertNull(FluxIncomingShareParser.parse(JSONObject(packet(body)).put("body", "a").toString()))
    }
}
