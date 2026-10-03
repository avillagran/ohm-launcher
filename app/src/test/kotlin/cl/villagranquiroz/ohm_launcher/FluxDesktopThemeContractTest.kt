package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fixtures emitted by the desktop Go adapter, not assembled by the Kotlin packet builder. */
class FluxDesktopThemeContractTest {
    private fun fixture(name: String): String = checkNotNull(javaClass.getResourceAsStream(
        "/flux/desktop-theme-$name.json")).bufferedReader().use { it.readText() }

    private fun catalog(): FluxThemeCatalog {
        val frame = JSONObject(fixture("catalog"))
        return checkNotNull(FluxThemePacket.receive(frame.getString("type"), frame.getJSONObject("body"),
            paired = true, playStore = false))
    }

    @Test fun desktopCatalogPreservesThemeIdsAndLightDarkPalettes() {
        val catalog = catalog()
        assertEquals("tokyo-night", catalog.current)
        assertEquals(listOf("custom-sunrise", "tokyo-night"), catalog.themes.map { it.id })
        assertTrue(catalog.themes.all { it.palette.name == it.id })
        assertEquals("Custom Sunrise", catalog.themes.first().label)
        assertEquals(OmarchyThemeMode.LIGHT, catalog.themes.first().palette.mode)
        assertEquals(OmarchyThemeMode.DARK, catalog.themes.last().palette.mode)
        assertEquals("#7aa2f7", catalog.themes.last().palette.colors["accent"])
    }

    @Test fun desktopSuccessAndFailureResolveOnlyTheMatchingLiveSelection() {
        for ((name, expected) in listOf("ack-success" to true, "ack-error" to false)) {
            val session = Any()
            val ids = catalog().themes.map { it.id }.toSet()
            val selection = FluxThemeSelection<Any>(directEdition = true)
            selection.bind("desktop", session, paired = true)
            val requestId = "5da63e00-8c22-4bc8-b615-290e4248e3fd"
            assertNotNull(selection.begin("desktop", session, true, ids, "custom-sunrise", requestId, 1000))
            val frame = fixture(name)
            assertNull(selection.accept("desktop", Any(), true, ids, frame))
            assertNull(selection.accept("desktop", session, false, ids, frame))
            val result = checkNotNull(selection.accept("desktop", session, true, ids, frame))
            assertEquals(expected, result.ok)
            assertEquals(if (expected) "custom-sunrise" else "tokyo-night", result.current)
            assertNull(selection.accept("desktop", session, true, ids, frame))
        }
    }

    @Test fun desktopCatalogRemainsUnavailableToPlayOrUnpairedPeers() {
        val frame = JSONObject(fixture("catalog"))
        val body = frame.getJSONObject("body")
        assertNull(FluxThemePacket.receive(frame.getString("type"), body, paired = false, playStore = false))
        assertNull(FluxThemePacket.receive(frame.getString("type"), body, paired = true, playStore = true))
    }
}
