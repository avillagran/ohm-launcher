package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfigStorageTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun migratesLegacyRootOnceAndUsesPublicOhmRoot() {
        val publicRoot = temporary.newFolder("OhmLauncher")
        publicRoot.delete()
        val legacyRoot = temporary.newFolder("OmarchyLauncher")
        val privateRoot = temporary.newFolder("private")
        legacyRoot.resolve("widgets_config.json").writeText("{\"desktops\":[]}")

        val storage = ConfigStorage(publicRoot, legacyRoot, privateRoot)
        val root = storage.initialize(canUsePublicRoot = true)

        assertEquals(publicRoot.canonicalPath, root.canonicalPath)
        assertTrue(publicRoot.resolve("widgets_config.json").isFile)
        assertFalse(legacyRoot.exists())
    }

    @Test
    fun fallsBackToPrivateRootWithoutBroadStorageAccess() {
        val publicRoot = temporary.newFolder("public")
        val legacyRoot = temporary.newFolder("legacy")
        val privateRoot = temporary.newFolder("private")

        val storage = ConfigStorage(publicRoot, legacyRoot, privateRoot)

        assertEquals(privateRoot.canonicalPath, storage.initialize(false).canonicalPath)
        assertTrue(privateRoot.resolve("widgets_config.json").isFile)
    }

    @Test
    fun readsFlutterFavoritesWithoutChangingUnknownWidgetConfigData() {
        val root = temporary.newFolder("root-with-favorites")
        val legacyRoot = temporary.newFolder("legacy-with-favorites")
        val privateRoot = temporary.newFolder("private-with-favorites")
        root.resolve(ConfigStorage.CONFIG_NAME).writeText(
            """{"future":{"keep":true},"desktops":[{"widgets":[]}]}""",
        )
        root.resolve(ConfigStorage.FAVORITES_NAME).writeText(
            """["two.pkg/.Home","one.pkg/.Main"]""",
        )

        val config = ConfigStorage(root, legacyRoot, privateRoot).read(root)

        assertEquals(listOf("two.pkg/.Home", "one.pkg/.Main"), config.favorites)
        assertTrue(config.raw.getJSONObject("future").getBoolean("keep"))
    }

    @Test
    fun togglesAndPersistsFavoritesInFlutterFileWithoutRewritingWidgetsConfig() {
        val root = temporary.newFolder("active-root")
        root.delete()
        val legacyRoot = temporary.newFolder("active-legacy")
        val privateRoot = temporary.newFolder("active-private")
        val storage = ConfigStorage(root, legacyRoot, privateRoot)
        storage.initialize(true)
        val widgetsBefore = """{"unknown":"keep","desktops":[{"widgets":[]}]}"""
        root.resolve(ConfigStorage.CONFIG_NAME).writeText(widgetsBefore)

        assertEquals(listOf("pkg/.Main"), ConfigStorage.toggleActiveFavorite("pkg/.Main"))
        assertEquals(listOf("pkg/.Main"), storage.readFavorites(root))
        assertEquals(widgetsBefore, root.resolve(ConfigStorage.CONFIG_NAME).readText())

        assertEquals(emptyList<String>(), ConfigStorage.toggleActiveFavorite("pkg/.Main"))
    }
}
