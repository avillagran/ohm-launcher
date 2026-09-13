package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginInstallPreparerTest {
    @Test
    fun preparesManifestEntryPointsAndOptionalPanelWithoutWritingThem() {
        val requested = mutableListOf<String>()
        val http = HttpFetcher { url, _ ->
            requested += url
            when {
                url.endsWith("/manifest.json") -> MANIFEST.toByteArray()
                url.endsWith("/ui/BarWidget.qml") -> "Item {}".toByteArray()
                url.endsWith("/settings.json") -> "{}".toByteArray()
                url.endsWith("/Panel.json") -> null
                else -> null
            }
        }

        val result = PluginInstallPreparer(http).prepare("https://github.com/acme/weather.git", "weather")

        assertTrue(result is InstallPreparation.Success)
        val plugin = (result as InstallPreparation.Success).plugin
        assertEquals("weather", plugin.pluginId)
        assertEquals("main", plugin.branch)
        assertEquals(setOf("manifest.json", "ui/BarWidget.qml", "settings.json"), plugin.files.keys)
        assertEquals(2, plugin.jsonFiles)
        assertEquals(1, plugin.qmlFiles)
        assertEquals(1, plugin.missingFiles)
        assertTrue(requested.any { it.endsWith("/Panel.json") })
    }

    @Test
    fun rejectsUntrustedRepositoryUrlsAndUnsafeManifestPaths() {
        val manifest = MANIFEST.replace("ui/BarWidget.qml", "../outside.qml")
        val http = HttpFetcher { url, _ -> if (url.endsWith("manifest.json")) manifest.toByteArray() else null }
        val preparer = PluginInstallPreparer(http)

        assertTrue(preparer.prepare("https://github.com.evil.test/acme/weather") is InstallPreparation.Failure)
        assertTrue(preparer.prepare("http://github.com/acme/weather") is InstallPreparation.Failure)
        assertTrue(preparer.prepare("https://user@github.com/acme/weather") is InstallPreparation.Failure)
        val unsafePath = preparer.prepare("https://github.com/acme/weather")
        assertTrue(unsafePath is InstallPreparation.Failure)
        assertTrue((unsafePath as InstallPreparation.Failure).message.contains("unsafe", ignoreCase = true))
    }

    @Test
    fun rejectsUnsafePluginIdsAndAggregateDownloadsOverTheLimit() {
        val unsafeId = MANIFEST.replace("\"weather\"", "\"../weather\"")
        val unsafePreparer = PluginInstallPreparer(
            HttpFetcher { url, _ -> if (url.endsWith("manifest.json")) unsafeId.toByteArray() else null },
        )
        assertTrue(unsafePreparer.prepare("https://github.com/acme/weather") is InstallPreparation.Failure)

        val http = HttpFetcher { url, _ ->
            when {
                url.endsWith("manifest.json") -> MANIFEST.toByteArray()
                url.endsWith("Panel.json") -> null
                else -> ByteArray(20)
            }
        }
        val bounded = PluginInstallPreparer(http, maxFileBytes = 20, maxTotalBytes = MANIFEST.toByteArray().size.toLong() + 30)
            .prepare("https://github.com/acme/weather", "weather")
        assertTrue(bounded is InstallPreparation.Failure)
        assertTrue((bounded as InstallPreparation.Failure).message.contains("size", ignoreCase = true))
    }

    @Test
    fun followsSafeQmlResolvedUrlDependenciesRecursively() {
        val http = HttpFetcher { url, _ ->
            when {
                url.endsWith("/manifest.json") -> MANIFEST.toByteArray()
                url.endsWith("/ui/BarWidget.qml") -> "Loader { source: Qt.resolvedUrl(\"Panel.qml\") }".toByteArray()
                url.endsWith("/ui/Panel.qml") -> "import \"parts/Model.js\" as Model\nLoader { source: Qt.resolvedUrl(\"parts/Details.qml\") }".toByteArray()
                url.endsWith("/ui/parts/Details.qml") -> "Text { text: \"Ready\" }".toByteArray()
                url.endsWith("/ui/parts/Model.js") -> "function ready() { return true }".toByteArray()
                url.endsWith("/settings.json") -> "{}".toByteArray()
                else -> null
            }
        }

        val result = PluginInstallPreparer(http).prepare("https://github.com/acme/weather", "weather")

        assertTrue(result is InstallPreparation.Success)
        assertEquals(
            setOf("manifest.json", "ui/BarWidget.qml", "ui/Panel.qml", "ui/parts/Details.qml", "ui/parts/Model.js", "settings.json"),
            (result as InstallPreparation.Success).plugin.files.keys,
        )
    }

    companion object {
        private val MANIFEST = """
            {"schemaVersion":1,"id":"weather","name":"Weather","version":"1","author":"Acme",
             "kinds":["bar-widget"],
             "entryPoints":{"barWidget":"ui/BarWidget.qml","settings":"settings.json"}}
        """.trimIndent()
    }
}
