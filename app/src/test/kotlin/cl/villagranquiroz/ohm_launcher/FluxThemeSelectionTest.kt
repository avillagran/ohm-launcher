package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FluxThemeSelectionTest {
    private val a = "123e4567-e89b-42d3-a456-426614174000"
    private val b = "123e4567-e89b-42d3-a456-426614174001"
    private val installed = setOf("Tokyo Night", "Gruvbox")

    @Test fun requestIsExactAndBoundedAndAckCompletesOnce() {
        val state = FluxThemeSelection<Any>(directEdition = true)
        val session = Any()
        state.bind("peer", session, paired = true)
        val frame = state.begin("peer", session, true, installed, "Tokyo Night", a, 12L)!!
        assertTrue(frame.endsWith("\n"))
        assertTrue(frame.toByteArray(Charsets.UTF_8).size <= FluxThemeSelection.MAX_FRAME_BYTES)
        val packet = JSONObject(frame)
        assertEquals(setOf("id", "type", "body"), packet.keys().asSequence().toSet())
        assertEquals(12L, packet.getLong("id"))
        assertEquals("flux.omarchy_theme.select", packet.getString("type"))
        assertEquals(setOf("version", "requestId", "id"), packet.getJSONObject("body").keys().asSequence().toSet())
        assertEquals(1, packet.getJSONObject("body").getInt("version"))
        assertEquals(a, packet.getJSONObject("body").getString("requestId"))
        assertEquals("Tokyo Night", packet.getJSONObject("body").getString("id"))
        val reply = ack(a, true, "Gruvbox")
        assertEquals(FluxThemeSelection.Result(a, true, "Gruvbox"), state.accept("peer", session, true, installed, reply))
        assertNull(state.accept("peer", session, true, installed, reply))
        assertNull(state.begin("peer", session, true, installed, "Tokyo Night", a, 13L))
    }

    @Test fun staleAReplyCannotCompleteBInSameSession() {
        val state = FluxThemeSelection<Any>(directEdition = true)
        val session = Any()
        state.bind("peer", session, true)
        assertNotNull(state.begin("peer", session, true, installed, "Tokyo Night", a, 1))
        assertEquals(FluxThemeSelection.Result(a, false, "Tokyo Night"),
            state.accept("peer", session, true, installed, ack(a, false, "Tokyo Night")))
        assertNotNull(state.begin("peer", session, true, installed, "Gruvbox", b, 2))
        assertNull(state.accept("peer", session, true, installed, ack(a, true, "Gruvbox")))
        assertNull(state.begin("peer", session, true, installed, "Tokyo Night", a, 3))
        assertEquals(FluxThemeSelection.Result(b, true, "Gruvbox"),
            state.accept("peer", session, true, installed, ack(b, true, "Gruvbox")))
    }

    @Test fun cancelledAReplyCannotCompleteBAndCancellationIsSessionScoped() {
        val state = FluxThemeSelection<Any>(directEdition = true)
        val session = Any()
        state.bind("peer", session, true)
        assertNotNull(state.begin("peer", session, true, installed, "Tokyo Night", a, 1))
        state.cancel("peer", Any())
        assertNull(state.begin("peer", session, true, installed, "Gruvbox", b, 2))
        state.cancel("peer", session)
        assertNotNull(state.begin("peer", session, true, installed, "Gruvbox", b, 2))
        assertNull(state.accept("peer", session, true, installed, ack(a, true, "Tokyo Night")))
        assertEquals(FluxThemeSelection.Result(b, true, "Gruvbox"),
            state.accept("peer", session, true, installed, ack(b, true, "Gruvbox")))
    }

    @Test fun replacementRevocationAndPairingGateBothDirections() {
        val state = FluxThemeSelection<Any>(directEdition = true)
        val old = Any()
        val replacement = Any()
        state.bind("peer", old, true)
        assertNotNull(state.begin("peer", old, true, installed, "Tokyo Night", a, 1))
        state.bind("peer", replacement, true)
        state.revoke("peer", old)
        assertNull(state.accept("peer", old, true, installed, ack(a, true, "Gruvbox")))
        assertNull(state.begin("peer", old, true, installed, "Gruvbox", b, 2))
        assertNull(state.begin("peer", replacement, false, installed, "Gruvbox", b, 2))
        assertNotNull(state.begin("peer", replacement, true, installed, "Gruvbox", b, 2))
        assertNull(state.accept("peer", replacement, false, installed, ack(b, true, "Gruvbox")))
        assertEquals(FluxThemeSelection.Result(b, true, "Gruvbox"),
            state.accept("peer", replacement, true, installed, ack(b, true, "Gruvbox")))
        state.revoke("peer", replacement)
        assertNull(state.begin("peer", replacement, true, installed, "Tokyo Night",
            "123e4567-e89b-42d3-a456-426614174002", 3))
    }

    @Test fun malformedRequestIdsAndUnsafeThemeIdsAreRejected() {
        val state = FluxThemeSelection<Any>(directEdition = true)
        val session = Any()
        state.bind("peer", session, true)
        for (bad in listOf(a.uppercase(), a.replace("-42d3-", "-12d3-"),
                a.replace("-a456-", "-7456-"), a + "x", "not-a-uuid")) {
            assertNull(bad, state.begin("peer", session, true, installed, "Tokyo Night", bad, 1))
        }
        for (bad in listOf("../etc", "a..b", "a/b", "a\\\\b", "caf\u00e9", "bad\u0000id",
                " bad", "bad ", "x".repeat(65))) {
            assertFalse(bad, FluxThemeSelection.validThemeId(bad))
            assertNull(bad, state.begin("peer", session, true, installed + bad, bad, a, 1))
        }
        assertNull(state.begin("peer", session, true, installed, "uninstalled", a, 1))
    }

    @Test fun malformedAcknowledgmentsDoNotConsumePendingRequest() {
        val state = FluxThemeSelection<Any>(directEdition = true)
        val session = Any()
        state.bind("peer", session, true)
        assertNotNull(state.begin("peer", session, true, installed, "Tokyo Night", a, 1))
        val valid = JSONObject(ack(a, true, "Gruvbox"))
        val body = valid.getJSONObject("body")
        val invalid = listOf(
            JSONObject(valid.toString()).put("extra", 1).toString() + "\n",
            "{\"id\":2,\"type\":\"flux.omarchy_theme.selected\",\"body\":${body},\"body\":${body}}\n",
            "{\"id\":2,\"type\":\"flux.omarchy_theme.selected\",\"body\":{\"version\":1,\"version\":1,\"requestId\":\"$a\",\"ok\":true,\"current\":\"Gruvbox\"}}\n",
            JSONObject(valid.toString()).put("id", 2.5).toString() + "\n",
            JSONObject(valid.toString()).put("type", "flux.omarchy_theme.select").toString() + "\n",
            JSONObject(valid.toString()).put("body", JSONObject(body.toString()).put("extra", true)).toString() + "\n",
            valid.toString().replace("\"version\":1", "\"version\":1.5") + "\n",
            JSONObject(valid.toString()).put("body", JSONObject(body.toString()).put("ok", "true")).toString() + "\n",
            JSONObject(valid.toString()).put("body", JSONObject(body.toString()).put("requestId", b)).toString() + "\n",
            JSONObject(valid.toString()).put("body", JSONObject(body.toString()).put("current", "uninstalled")).toString() + "\n",
            JSONObject(valid.toString()).put("body", JSONObject(body.toString()).put("current", "../etc")).toString() + "\n",
            valid.toString(), valid.toString() + "\n\n", valid.toString() + "garbage\n",
            "x".repeat(FluxThemeSelection.MAX_FRAME_BYTES) + "\n",
        )
        invalid.forEach { assertNull(it.take(80), state.accept("peer", session, true, installed, it)) }
        assertEquals(FluxThemeSelection.Result(a, true, "Gruvbox"),
            state.accept("peer", session, true, installed, ack(a, true, "Gruvbox")))
    }

    @Test fun playEditionCannotSendOrAcceptSelection() {
        val state = FluxThemeSelection<Any>(directEdition = false)
        val session = Any()
        state.bind("peer", session, true)
        assertNull(state.begin("peer", session, true, installed, "Tokyo Night", a, 1))
        assertNull(state.accept("peer", session, true, installed, ack(a, true, "Tokyo Night")))
    }

    private fun ack(id: String, ok: Boolean, current: String): String =
        JSONObject().put("id", 2).put("type", "flux.omarchy_theme.selected")
            .put("body", JSONObject().put("version", 1).put("requestId", id)
                .put("ok", ok).put("current", current)).toString() + "\n"
}
