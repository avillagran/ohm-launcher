package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class PluginRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun discoversValidSchemaVersionOnePlugin() {
        val root = temporaryFolder.newFolder("OhmLauncher")
        val plugin = root.resolve("plugins/io.github.ohm.weather")
        plugin.mkdirs()
        plugin.resolve("BarWidget.qml").writeText("Item {}")
        plugin.resolve("manifest.json").writeText(
            """
            {
              "schemaVersion": 1,
              "id": "io.github.ohm.weather",
              "name": "Weather",
              "version": "1.0.0",
              "author": "Ohm",
              "kinds": ["bar-widget"],
              "entryPoints": {"barWidget": "BarWidget.qml"}
            }
            """.trimIndent(),
        )

        val discovered = PluginRepository(root).discover()

        assertEquals(1, discovered.size)
        assertEquals("io.github.ohm.weather", discovered.single().id)
        assertEquals(listOf("bar-widget"), discovered.single().kinds)
        assertTrue(discovered.single().isValid)
        assertEquals(plugin.resolve("BarWidget.qml"), discovered.single().entryFileForKind("bar-widget"))
    }

    @Test
    fun rejectsSymlinksAnywhereInsidePluginDirectory() {
        val root = temporaryFolder.newFolder("OhmLauncher")
        val plugin = createPlugin(root, "io.github.ohm.linked")
        val outside = root.resolve("outside.qml").apply { writeText("Item {}") }
        val nested = plugin.resolve("nested").apply { mkdirs() }
        Files.createSymbolicLink(nested.resolve("Linked.qml").toPath(), outside.toPath())

        val discovered = PluginRepository(root).discover().single()

        assertTrue(discovered.validationErrors.any { it.contains("symlink", ignoreCase = true) })
        assertTrue(!discovered.isValid)
    }

    @Test
    fun preservesTopLevelPluginSymlinkAsInvalidDiagnostic() {
        val root = temporaryFolder.newFolder("OhmLauncher")
        val target = createPlugin(root, "io.github.ohm.target", folderName = "target")
        val link = root.resolve("plugins/linked-plugin")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        val linked = PluginRepository(root).discover().first { it.folder.name == "linked-plugin" }

        assertTrue(linked.validationErrors.any { it.contains("symlink", ignoreCase = true) })
        assertTrue(!linked.isValid)
    }

    @Test
    fun validatesEveryDeclaredEntryPointEvenWhenKindIsNotDeclared() {
        val root = temporaryFolder.newFolder("OhmLauncher")
        val plugin = createPlugin(root, "io.github.ohm.extra-entry")
        plugin.resolve("manifest.json").writeText(
            """
            {
              "schemaVersion": 1,
              "id": "io.github.ohm.extra-entry",
              "name": "Extra entry",
              "version": "1.0.0",
              "author": "Ohm",
              "kinds": ["bar-widget"],
              "entryPoints": {
                "barWidget": "BarWidget.qml",
                "panel": "../Panel.qml"
              }
            }
            """.trimIndent(),
        )

        val discovered = PluginRepository(root).discover().single()

        assertTrue(discovered.validationErrors.any { it.contains("entryPoints.panel") && it.contains("unsafe") })
    }

    @Test
    fun rejectsMissingQmlFilesReferencedByResolvedUrl() {
        val root = temporaryFolder.newFolder("OhmLauncher")
        val plugin = createPlugin(root, "io.github.ohm.missing-loader")
        plugin.resolve("BarWidget.qml").writeText(
            """
            import QtQuick
            import "Model.js" as Model
            BarWidget {
              Loader { source: Qt.resolvedUrl("Panel.qml") }
            }
            """.trimIndent(),
        )

        val discovered = PluginRepository(root).discover().single()

        assertFalse(discovered.isValid)
        assertTrue(discovered.validationErrors.any { it.contains("Panel.qml") && it.contains("not found") })
        assertTrue(discovered.validationErrors.any { it.contains("Model.js") && it.contains("not found") })
    }

    @Test
    fun preservesMalformedAndMissingManifestsWithDiagnostics() {
        val root = temporaryFolder.newFolder("OhmLauncher")
        root.resolve("plugins/missing").mkdirs()
        root.resolve("plugins/malformed").apply {
            mkdirs()
            resolve("manifest.json").writeText("{not json")
        }

        val discovered = PluginRepository(root).discover()

        assertEquals(listOf("malformed", "missing"), discovered.map(Plugin::id))
        assertTrue(discovered.all { !it.isValid && it.validationErrors.isNotEmpty() })
        assertTrue(discovered.first { it.id == "missing" }.validationErrors.single().contains("manifest.json"))
        assertTrue(discovered.first { it.id == "malformed" }.validationErrors.single().contains("Invalid"))
    }

    @Test
    fun rejectsWrongSchemaUnknownKindAndReservedThirdPartyId() {
        val root = temporaryFolder.newFolder("OhmLauncher")
        val plugin = createPlugin(root, "Omarchy.community")
        plugin.resolve("manifest.json").writeText(
            """
            {
              "schemaVersion": 2,
              "id": "Omarchy.community",
              "name": "Reserved",
              "version": "1.0.0",
              "author": "Third party",
              "kinds": ["wallpaper"],
              "entryPoints": {}
            }
            """.trimIndent(),
        )

        val errors = PluginRepository(root).discover().single().validationErrors

        assertTrue(errors.any { it.contains("schemaVersion") })
        assertTrue(errors.any { it.contains("reserved") })
        assertTrue(errors.any { it.contains("Unknown kind") })
    }

    @Test
    fun requiresMappedEntryPointForEverySupportedKind() {
        val root = temporaryFolder.newFolder("OhmLauncher")
        val plugin = createPlugin(root, "io.github.ohm.missing-entry")
        plugin.resolve("manifest.json").writeText(
            """
            {
              "schemaVersion": 1,
              "id": "io.github.ohm.missing-entry",
              "name": "Missing entry",
              "version": "1.0.0",
              "author": "Ohm",
              "kinds": ["panel"],
              "entryPoints": {}
            }
            """.trimIndent(),
        )

        val errors = PluginRepository(root).discover().single().validationErrors

        assertTrue(errors.any { it.contains("entryPoints.panel") })
    }

    @Test
    fun rejectsAbsoluteTraversalAndMissingEntryPointPaths() {
        val root = temporaryFolder.newFolder("OhmLauncher")
        val unsafePaths = listOf("../Outside.qml", "/tmp/Outside.qml", "C:\\Outside.qml", "nested/../../Outside.qml")
        unsafePaths.forEachIndexed { index, path ->
            val plugin = createPlugin(root, "io.github.ohm.unsafe$index")
            replaceBarWidgetEntry(plugin, path)
        }
        val missing = createPlugin(root, "io.github.ohm.missing-file")
        replaceBarWidgetEntry(missing, "Missing.qml")

        val discovered = PluginRepository(root).discover()

        unsafePaths.indices.forEach { index ->
            val unsafe = discovered.first { it.id == "io.github.ohm.unsafe$index" }
            assertTrue(unsafe.validationErrors.any { it.contains("unsafe") })
            assertEquals(null, unsafe.entryFileForKind("bar-widget"))
        }
        assertTrue(discovered.first { it.id == "io.github.ohm.missing-file" }
            .validationErrors.any { it.contains("not found") })
    }

    @Test
    fun exposesAllOmarchyKindMappings() {
        assertEquals(
            mapOf(
                "bar-widget" to "barWidget",
                "panel" to "panel",
                "overlay" to "overlay",
                "menu" to "menu",
                "service" to "service",
                "bar" to "bar",
            ),
            PluginContract.KIND_TO_ENTRY_POINT,
        )
        assertEquals(1, PluginContract.SCHEMA_VERSION)
        assertEquals("/sdcard/OhmLauncher", PluginContract.PUBLIC_ROOT)
        assertEquals("plugins", PluginContract.ENABLED_DIRECTORY)
        assertEquals("plugins.disabled", PluginContract.DISABLED_DIRECTORY)
    }

    @Test
    fun disablesPluginByManifestIdInsteadOfFolderName() {
        val root = temporaryFolder.newFolder("OhmLauncher")
        val plugin = createPlugin(root, "io.github.ohm.toggle", folderName = "downloaded-folder")
        val repository = PluginRepository(root)

        assertTrue(repository.disable("io.github.ohm.toggle"))

        assertTrue(!plugin.exists())
        assertTrue(root.resolve("plugins.disabled/downloaded-folder").isDirectory)
        assertEquals(listOf("io.github.ohm.toggle"), repository.disabledPluginIds())
    }

    @Test
    fun disablingReplacesPriorDisabledCopyWithSameManifestId() {
        val root = temporaryFolder.newFolder("OhmLauncher")
        createPlugin(root, "io.github.ohm.replace", folderName = "active-copy")
        val old = createPlugin(root, "io.github.ohm.replace", folderName = "old-copy")
        val oldDisabled = root.resolve("plugins.disabled/old-copy")
        requireNotNull(oldDisabled.parentFile).mkdirs()
        assertTrue(old.renameTo(oldDisabled))
        val repository = PluginRepository(root)

        assertTrue(repository.disable("io.github.ohm.replace"))

        assertEquals(listOf("io.github.ohm.replace"), repository.disabledPluginIds())
        assertFalse(oldDisabled.exists())
    }

    @Test
    fun enablesPluginByManifestId() {
        val root = temporaryFolder.newFolder("OhmLauncher")
        val repository = PluginRepository(root)
        createPlugin(root, "io.github.ohm.toggle", folderName = "downloaded-folder")
        repository.disable("io.github.ohm.toggle")

        assertTrue(repository.enable("io.github.ohm.toggle"))

        assertEquals(listOf("io.github.ohm.toggle"), repository.discover().map(Plugin::id))
        assertTrue(repository.disabledPluginIds().isEmpty())
    }

    @Test
    fun deletesMatchingPluginFromEnabledAndDisabledDirectories() {
        val root = temporaryFolder.newFolder("OhmLauncher")
        val active = createPlugin(root, "io.github.ohm.remove", folderName = "active-copy")
        val disabled = createPlugin(root, "io.github.ohm.remove", folderName = "disabled-copy")
        val disabledDestination = root.resolve("plugins.disabled/disabled-copy")
        requireNotNull(disabledDestination.parentFile).mkdirs()
        assertTrue(disabled.renameTo(disabledDestination))
        val repository = PluginRepository(root)

        assertTrue(repository.delete("io.github.ohm.remove"))

        assertFalse(active.exists())
        assertFalse(disabledDestination.exists())
    }

    @Test
    fun installsPreparedPluginAtomicallyAndValidatesIt() {
        val root = temporaryFolder.newFolder("OhmLauncher")
        val manifest = """{"schemaVersion":1,"id":"io.github.ohm.remote","name":"Remote","version":"1","author":"Ohm","kinds":["bar-widget"],"entryPoints":{"barWidget":"BarWidget.qml"}}"""
        val prepared = PreparedPlugin(
            pluginId = "io.github.ohm.remote",
            branch = "main",
            files = mapOf(
                "manifest.json" to manifest.toByteArray(),
                "BarWidget.qml" to "Item { Text { text: \"Remote\" } }".toByteArray(),
            ),
            jsonFiles = 1,
            qmlFiles = 1,
            missingFiles = 0,
        )

        val installed = PluginRepository(root).installPrepared(prepared)

        assertTrue(installed.isValid)
        assertEquals("io.github.ohm.remote", installed.id)
        assertTrue(root.resolve("plugins/io.github.ohm.remote/BarWidget.qml").isFile)
    }

    private fun replaceBarWidgetEntry(plugin: java.io.File, path: String) {
        val manifest = plugin.resolve("manifest.json")
        manifest.writeText(manifest.readText().replace("BarWidget.qml", path.replace("\\", "\\\\")))
    }

    private fun createPlugin(root: java.io.File, id: String, folderName: String = id): java.io.File {
        val plugin = root.resolve("plugins/$folderName").apply { mkdirs() }
        plugin.resolve("BarWidget.qml").writeText("Item {}")
        plugin.resolve("manifest.json").writeText(
            """
            {
              "schemaVersion": 1,
              "id": "$id",
              "name": "Test",
              "version": "1.0.0",
              "author": "Ohm",
              "kinds": ["bar-widget"],
              "entryPoints": {"barWidget": "BarWidget.qml"}
            }
            """.trimIndent(),
        )
        return plugin
    }
}
