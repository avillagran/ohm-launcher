package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FluxThemeCatalogDispatchTest {
    private val peer = "1234567890abcdef1234567890abcdef"
    private fun identity(advertises: Boolean): String = JSONObject(FluxWire.identity(peer, "desktop")).apply {
        getJSONObject("body").put("outgoingCapabilities", JSONArray().apply {
            if (advertises) put(FluxThemePacket.TYPE)
        })
    }.toString()
    private fun catalog(): JSONObject = JSONObject().put("kind", "catalog").put("version", 1)
        .put("current", "tokyo-night").put("themes", JSONArray().put(
            JSONObject().put("id", "tokyo-night").put("label", "Tokyo Night")
                .put("palette", JSONObject().put("name", "Tokyo Night").put("mode", "dark")
                    .put("source", "omarchy").put("colors", JSONObject().put("accent", "#a1b2c3")))))

    @Test fun validCatalogWithoutInitialOutgoingCapabilityNeverReachesCacheOrCallback() {
        val session = Any()
        val cache = FluxThemeCatalogCache<Any>()
        var callbacks = 0
        val gate = FluxThemeCatalogDispatch(identity(false))
        gate.deliver(FluxThemePacket.TYPE, catalog(), paired = true, open = true,
            current = true, playStore = false) { cache.put(peer, session, it); callbacks++ }
        assertNull(cache.get(peer, session))
        assertEquals(0, callbacks)
    }

    @Test fun initialHandshakeCapabilityAllowsExactCurrentDirectSession() {
        val session = Any()
        val cache = FluxThemeCatalogCache<Any>()
        val gate = FluxThemeCatalogDispatch(identity(true))
        gate.deliver(FluxThemePacket.TYPE, catalog(), paired = true, open = true,
            current = true, playStore = false) { cache.put(peer, session, it) }
        assertEquals("tokyo-night", cache.get(peer, session)?.current)
        cache.remove(peer, session)
        gate.deliver(FluxThemePacket.TYPE, catalog(), paired = true, open = true,
            current = false, playStore = false) { cache.put(peer, session, it) }
        assertNull(cache.get(peer, session))
    }

    @Test fun inBandIdentityAdditionCannotAuthorizeOriginalLink() {
        val initial = identity(false)
        val gate = FluxThemeCatalogDispatch(initial)
        val inBand = identity(true)
        assertTrue(FluxThemeCatalogDispatch.supportsCatalog(inBand))
        var callbacks = 0
        gate.deliver(FluxThemePacket.TYPE, catalog(), paired = true, open = true,
            current = true, playStore = false) { callbacks++ }
        assertEquals(0, callbacks)
    }

    @Test fun playEditionAndClosedOrUnpairedLinksRejectCatalog() {
        val gate = FluxThemeCatalogDispatch(identity(true))
        var callbacks = 0
        for (play in listOf(false, true)) for (paired in listOf(false, true))
            for (open in listOf(false, true)) {
                if (play || !paired || !open) gate.deliver(FluxThemePacket.TYPE, catalog(),
                    paired, open, current = true, playStore = play) { callbacks++ }
            }
        assertEquals(0, callbacks)
    }
}
