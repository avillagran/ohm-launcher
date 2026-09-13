package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceCatalogTest {
    @Test
    fun fetchFallsBackAndParsesPluginAndSuiteSources() {
        val requested = mutableListOf<String>()
        val transport = HttpFetcher { url, _ ->
            requested += url
            if (url == MarketplaceCatalog.PRIMARY_URL) null else REGISTRY.toByteArray()
        }

        val entries = MarketplaceCatalog(transport).fetch()

        assertEquals(listOf(MarketplaceCatalog.PRIMARY_URL, MarketplaceCatalog.FALLBACK_URL), requested)
        assertEquals(2, entries.size)
        assertEquals("desktop-suite", entries[0].id)
        assertTrue(entries[0].isSuite)
        assertEquals("Suite name", entries[0].name)
        assertEquals("https://github.com/acme/plugins", entries[0].repoUrl)
        assertEquals("weather-clock", entries[1].id)
        assertEquals("Weather Clock", entries[1].name)
        assertEquals(listOf("weather", "clock"), entries[1].tags)
        assertEquals("Use defaults", entries[1].installNote)
        assertFalse(entries[1].isSuite)
    }

    @Test(expected = MarketplaceFetchException::class)
    fun fetchFailsWhenBothRegistryEndpointsFail() {
        MarketplaceCatalog(HttpFetcher { _, _ -> null }).fetch()
    }

    companion object {
        private val REGISTRY = """
            {
              "sources": [{
                "repo": "https://github.com/acme/plugins",
                "catalog": {
                  "id": "desktop-suite", "name": "Suite name", "description": "A suite",
                  "author": "Acme", "version": "2", "category": "Desktop",
                  "tags": ["suite"], "installCommand": "install", "installNote": "Careful"
                },
                "plugins": {
                  "weather-clock": {
                    "description": "Weather", "author": "Acme", "version": "1",
                    "category": "Widgets", "tags": ["weather", 7, "clock"],
                    "installation": {"note": "Use defaults"}
                  }
                }
              }]
            }
        """.trimIndent()
    }
}
