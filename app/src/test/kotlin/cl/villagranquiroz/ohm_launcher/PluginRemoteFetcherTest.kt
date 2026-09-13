package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginRemoteFetcherTest {
    @Test
    fun findsMatchingManifestInConventionalPluginDirectory() {
        val requested = mutableListOf<String>()
        val http = HttpFetcher { url, _ ->
            requested += url
            when (url) {
                "https://raw.githubusercontent.com/owner/repo/main/manifest.json" ->
                    MANIFEST.replace("weather", "other").toByteArray()
                "https://raw.githubusercontent.com/owner/repo/main/weather/manifest.json" -> MANIFEST.toByteArray()
                else -> null
            }
        }

        val found = PluginRemoteFetcher(http).findManifest("owner", "repo", "main", "weather")

        assertEquals("weather", found?.data?.getString("id"))
        assertEquals("weather", found?.manifestDir)
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/owner/repo/main/manifest.json",
                "https://raw.githubusercontent.com/owner/repo/main/weather/manifest.json",
            ),
            requested,
        )
    }

    @Test
    fun sweepsContentsApiAfterConventionalLocationsMiss() {
        val http = HttpFetcher { url, _ ->
            when (url) {
                "https://api.github.com/repos/owner/repo/contents/?ref=main" ->
                    """[{"name":"nested","type":"dir"},{"name":"file","type":"file"}]""".toByteArray()
                "https://raw.githubusercontent.com/owner/repo/main/nested/manifest.json" -> MANIFEST.toByteArray()
                else -> null
            }
        }

        val found = PluginRemoteFetcher(http).findManifest("owner", "repo", "main", "weather")

        assertEquals("nested", found?.manifestDir)
    }

    @Test
    fun rejectsUnsafeGithubPathSegmentsBeforeMakingRequests() {
        var calls = 0
        val fetcher = PluginRemoteFetcher(HttpFetcher { _, _ -> calls++; null })

        assertNull(fetcher.findManifest("../owner", "repo", "main", "weather"))
        assertNull(fetcher.findManifest("owner", "repo", "feature/x", "weather"))
        assertEquals(0, calls)
    }

    companion object {
        private val MANIFEST = """
            {"schemaVersion":1,"id":"weather","name":"Weather","version":"1","author":"Acme",
             "kinds":["bar-widget"],"entryPoints":{"barWidget":"BarWidget.qml"}}
        """.trimIndent()
    }
}
