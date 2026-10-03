package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FluxThemePacketTest {
    private fun body(): JSONObject = JSONObject().put("kind", "catalog").put("version", 1).put("current", "tokyo-night")
        .put("themes", JSONArray().put(JSONObject().put("id", "tokyo-night").put("label", "Tokyo Night")
            .put("palette", JSONObject().put("name", "Tokyo Night").put("mode", "dark")
                .put("source", "omarchy").put("colors", JSONObject().put("accent", "#a1B2c3")))))

    private fun accepted(body: JSONObject): FluxThemeCatalog? =
        FluxThemePacket.receive(FluxThemePacket.TYPE, body, paired = true, playStore = false)

    private fun invalid(body: JSONObject) {
        assertThrows(RuntimeException::class.java) { accepted(body) }
    }

    @Test fun parsesExactCatalogWithoutApplyingIt() {
        val catalog = accepted(body())!!
        assertEquals("tokyo-night", catalog.current)
        assertEquals("Tokyo Night", catalog.themes.single().label)
        assertEquals(OmarchyThemeMode.DARK, catalog.themes.single().palette.mode)
        assertEquals("#a1B2c3", catalog.themes.single().palette.colors["accent"])
    }

    @Test fun unpairedPlayAndOtherTypesNeverParseEvenMalformedBody() {
        val malformed = JSONObject().put("kind", "command")
        assertNull(FluxThemePacket.receive(FluxThemePacket.TYPE, malformed, false, false))
        assertNull(FluxThemePacket.receive(FluxThemePacket.TYPE, malformed, true, true))
        assertNull(FluxThemePacket.receive("flux.command", malformed, true, false))
    }

    @Test fun rejectsMalformedNestedValuesAndUnexpectedCommands() {
        invalid(body().put("kind", "select"))
        invalid(body().put("version", 2))
        invalid(body().put("version", "1"))
        invalid(body().put("execute", "anything"))
        invalid(body().put("current", "missing"))
        invalid(body().put("themes", JSONArray()))
        invalid(body().put("current", 5))
        val theme = body().getJSONArray("themes").getJSONObject(0)
        invalid(body().also { it.getJSONArray("themes").put(theme.put("label", "\u0000bad")) })
        theme.put("label", "Tokyo Night").getJSONObject("palette").put("source", "remote")
        invalid(body().put("themes", JSONArray().put(theme)))
        theme.getJSONObject("palette").put("source", "omarchy").put("mode", "darkish")
        invalid(body().put("themes", JSONArray().put(theme)))
        theme.getJSONObject("palette").put("mode", "dark").getJSONObject("colors").put("accent", "#fff")
        invalid(body().put("themes", JSONArray().put(theme)))
        theme.getJSONObject("palette").getJSONObject("colors").put("accent", "#123456").put("evil role", "#123456")
        invalid(body().put("themes", JSONArray().put(theme)))
    }

    @Test fun boundsPacketThemesColorsAndDuplicateIds() {
        val one = body().getJSONArray("themes").getJSONObject(0)
        invalid(body().put("themes", JSONArray().put(one).put(JSONObject(one.toString()))))
        val many = JSONArray()
        repeat(FluxThemePacket.MAX_THEMES + 1) { n -> many.put(JSONObject(one.toString()).put("id", "theme$n")) }
        invalid(body().put("themes", many))
        val colors = JSONObject()
        repeat(FluxThemePacket.MAX_COLORS + 1) { n -> colors.put("role$n", "#123456") }
        invalid(body().put("themes", JSONArray().put(JSONObject(one.toString()).put("palette",
            JSONObject(one.getJSONObject("palette").toString()).put("colors", colors)))))
        assertThrows(IllegalStateException::class.java) {
            FluxWire.readLine(("x".repeat(FluxThemePacket.MAX_PACKET_BYTES) + "\n").byteInputStream(),
                FluxThemePacket.MAX_PACKET_BYTES)
        }
    }

    @Test fun themeCatalogIsReceivedOnlyInDirectEdition() {
        val id = "92baf92fcbb34073aa190966c9de14e6"
        for (play in listOf(false, true)) {
            val body = JSONObject(FluxWire.identity(id, "Ohm", playStore = play)).getJSONObject("body")
            val incoming = body.getJSONArray("incomingCapabilities")
            val outgoing = body.getJSONArray("outgoingCapabilities")
            assertEquals(if (play) 0 else 6, incoming.length())
            assertEquals(!play, (0 until incoming.length()).any { incoming.getString(it) == FluxThemePacket.TYPE })
            assertFalse((0 until outgoing.length()).any { outgoing.getString(it) == FluxThemePacket.TYPE })
        }
    }
}
