package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LauncherSettingsStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun absentSettingsFileReadsEffectiveDefaultsWithoutCreatingIt() {
        val file = temporary.root.resolve("settings.json")

        val settings = LauncherSettingsStore(file).read()

        assertEquals(1.0, settings.textScale, 0.0)
        assertFalse(file.exists())
    }

    @Test
    fun boxAppearanceDefaultsAndRoundTripSurviveSerialization() {
        val defaults = LauncherSettings.parse("{}")

        assertTrue(defaults.boxBorderVisible)
        assertEquals(1.0, defaults.boxBorderWidth, 0.0)
        assertEquals(48.0, defaults.boxItemSize, 0.0)

        val custom = defaults.copy(boxBorderVisible = false, boxBorderWidth = 4.0, boxItemSize = 64.0)
        val restored = LauncherSettings.parse(custom.toJson().toString())

        assertFalse(restored.boxBorderVisible)
        assertEquals(4.0, restored.boxBorderWidth, 0.0)
        assertEquals(64.0, restored.boxItemSize, 0.0)
    }

    @Test
    fun writesACompleteModelAtomicallyAndPreservesExtensionFields() {
        val file = temporary.root.resolve("nested/settings.json")
        val store = LauncherSettingsStore(file)
        val settings = LauncherSettings.parse("""{"extension":{"keep":true}}""")

        store.write(settings)

        val persisted = JSONObject(file.readText())
        assertTrue(persisted.getJSONObject("extension").getBoolean("keep"))
        assertEquals("Predeterminada", persisted.getString("fontFamily"))
        assertTrue(persisted.isNull("favoritesBarMode"))
        assertTrue(persisted.isNull("omarchyPeer"))
        assertEquals(emptyList<String>(), file.parentFile.listFiles()!!.filter { it.name.contains(".tmp-") }.map { it.name })
    }

    @Test
    fun failedUpdateDoesNotReplaceTheLastValidDocument() {
        val file = temporary.newFile("settings-valid.json")
        val original = """{"accent":"#fff"}"""
        file.writeText(original)
        val store = LauncherSettingsStore(file)

        runCatching { store.updateRaw { JSONObject("{") } }

        assertEquals(original, file.readText())
    }

    @Test
    fun themeSnapshotReturnsOnlyCaseInsensitiveThemeAndColorKeys() {
        val file = temporary.newFile("settings-theme.json")
        file.writeText(
            """{
              "accent":"#1","surfaceColor":"#2","TemaOscuro":true,
              "themeName":"night","desktopBackground":"#3",
              "fontFamily":"Inter","unrelated":7
            }""",
        )

        val snapshot = LauncherSettingsStore(file).themeSnapshot()
        val colors = snapshot.getJSONObject("colors")

        assertEquals(setOf("accent", "surfaceColor", "TemaOscuro", "themeName", "desktopBackground"), colors.keys().asSequence().toSet())
        assertFalse(colors.has("fontFamily"))
        assertFalse(colors.has("unrelated"))
    }

    @Test
    fun themeUpdatePersistsEveryTopLevelEntryIncludingNullAndPreservesOthers() {
        val file = temporary.newFile("settings-update.json")
        file.writeText("""{"accent":"old","future":{"keep":true},"textScale":1.1}""")
        val store = LauncherSettingsStore(file)

        val effective = store.updateTheme(
            JSONObject()
                .put("accent", "new")
                .put("customThemeField", 9)
                .put("nullableExtension", JSONObject.NULL),
        )

        val persisted = JSONObject(file.readText())
        assertEquals("new", persisted.getString("accent"))
        assertEquals(9, persisted.getInt("customThemeField"))
        assertTrue(persisted.isNull("nullableExtension"))
        assertTrue(persisted.getJSONObject("future").getBoolean("keep"))
        assertEquals(1.1, effective.textScale, 0.0)
    }

    @Test
    fun storesAndReturnsCanonicalOmarchyThemeWithoutDroppingSettings() {
        val file = temporary.newFile("settings-omarchy-theme.json")
        file.writeText("""{"textScale":1.2,"future":{"keep":true}}""")
        val store = LauncherSettingsStore(file)
        val payload = JSONObject(
            """{"name":"Nord","mode":"dark","source":"omarchy","colors":{"accent":"#81a1c1","background":"#2e3440"}}""",
        )

        store.updateOmarchyTheme(payload)

        val persisted = JSONObject(file.readText())
        assertEquals("Nord", persisted.getJSONObject("omarchyTheme").getString("name"))
        assertTrue(persisted.getJSONObject("future").getBoolean("keep"))
        assertEquals(payload.toString(), store.themeSnapshot().toString())
    }

    @Test
    fun peerUpdatesUseRootSettingsFieldAndPreserveUnknownData() {
        val file = temporary.newFile("settings-peer.json")
        file.writeText("""{"future":true,"omarchyPeer":{"ip":"old","port":8753,"id":"old","peerExtension":8}}""")
        val store = LauncherSettingsStore(file)
        val peer = OmarchyPeer("10.0.0.8", 8753, "desk")

        store.updatePeer(peer)
        assertEquals(peer, store.read().omarchyPeer)
        assertTrue(JSONObject(file.readText()).getBoolean("future"))
        assertEquals(8, JSONObject(file.readText()).getJSONObject("omarchyPeer").getInt("peerExtension"))

        store.updatePeer(null)
        val cleared = JSONObject(file.readText())
        assertTrue(cleared.has("omarchyPeer"))
        assertTrue(cleared.isNull("omarchyPeer"))
        assertNull(store.read().omarchyPeer)
    }

    @Test
    fun migratesTemporaryNestedPeerFromWidgetsConfigOnce() {
        val settingsFile = temporary.root.resolve("settings.json")
        val widgetsFile = temporary.newFile("widgets_config.json")
        widgetsFile.writeText(
            """{"settings":{"omarchyPeer":{"ip":"10.0.0.9","port":9000,"id":"legacy","peerExtension":7},"keep":"nested"},"desktops":[]}""",
        )
        val store = LauncherSettingsStore(settingsFile, legacyPeerFile = widgetsFile)

        assertEquals(OmarchyPeer("10.0.0.9", 9000, "legacy"), store.read().omarchyPeer)

        val migrated = JSONObject(settingsFile.readText())
        assertEquals("10.0.0.9", migrated.getJSONObject("omarchyPeer").getString("ip"))
        assertEquals(7, migrated.getJSONObject("omarchyPeer").getInt("peerExtension"))
        assertFalse(migrated.has("settings"))
        assertEquals("nested", JSONObject(widgetsFile.readText()).getJSONObject("settings").getString("keep"))

        widgetsFile.writeText("""{"settings":{"omarchyPeer":{"ip":"changed","port":9001,"id":"changed"}}}""")
        assertEquals(OmarchyPeer("10.0.0.9", 9000, "legacy"), store.read().omarchyPeer)
    }

    @Test
    fun existingRootPeerWinsOverTemporaryNestedPeer() {
        val settingsFile = temporary.newFile("settings-root-peer.json")
        settingsFile.writeText("""{"omarchyPeer":{"ip":"root","port":8753,"id":"root"}}""")
        val widgetsFile = temporary.newFile("widgets-root-peer.json")
        widgetsFile.writeText("""{"settings":{"omarchyPeer":{"ip":"legacy","port":9000,"id":"legacy"}}}""")

        val settings = LauncherSettingsStore(settingsFile, widgetsFile).read()

        assertEquals(OmarchyPeer("root", 8753, "root"), settings.omarchyPeer)
    }
}
