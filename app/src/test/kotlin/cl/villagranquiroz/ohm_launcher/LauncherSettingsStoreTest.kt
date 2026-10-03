package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
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
        assertEquals(emptyList<String>(), requireNotNull(file.parentFile).listFiles()!!.filter { it.name.contains(".tmp-") }.map { it.name })
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

    private fun payload() = JSONObject("""{"name":"Nord","colors":{"accent":"#81a1c1"}}""")

    @Test fun api24DefaultDirectorySyncReportsCommittedAfterRename() {
        val file = temporary.newFile("settings-api24-sync.json")
        file.writeText("{}")
        val result = LauncherSettingsStore(file).prepareOmarchyTheme(payload()).commit { true }
        assertEquals(result.failure?.toString(), ThemeCommitStatus.COMMITTED, result.status)
        assertNull(result.failure)
        assertEquals("Nord", JSONObject(file.readText()).getJSONObject("omarchyTheme").getString("name"))
    }

    private fun injectedIo(
        onStage: () -> Unit = {},
        onReplace: () -> Unit = {},
        onSync: () -> Unit = {},
    ) = object : ThemeTransactionIo {
        override fun stage(temporary: File, bytes: ByteArray) {
            onStage()
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
        }
        override fun replace(temporary: File, target: File) {
            onReplace()
            check(temporary.renameTo(target))
        }
        override fun syncDirectory(directory: File) = onSync()
    }

    private fun assertNoTemps() {
        assertTrue(temporary.root.listFiles()!!.none { it.name.contains(".tmp-") })
    }

    @Test
    fun blockedStagingPermitsLogicalRevokeBeforeTargetReplacement() {
        val file = temporary.newFile("settings.json")
        val old = """{"future":"old"}"""
        file.writeText(old)
        val staging = CountDownLatch(1)
        val continueStage = CountDownLatch(1)
        val valid = AtomicBoolean(true)
        val status = AtomicReference<ThemeCommitResult>()
        val store = LauncherSettingsStore(file, themeIo = injectedIo(onStage = {
            staging.countDown()
            check(continueStage.await(5, TimeUnit.SECONDS))
        }))
        val prepared = store.prepareOmarchyTheme(payload())
        val worker = Thread { status.set(prepared.commit { valid.get() }) }
        worker.start()
        try {
            assertTrue(staging.await(5, TimeUnit.SECONDS))
            // No store monitor acquisition: revocation is independent of blocked disk I/O.
            valid.set(false)
            assertEquals(old, file.readText())
        } finally {
            continueStage.countDown()
            worker.join(5000)
        }
        assertFalse(worker.isAlive)
        assertEquals(ThemeCommitStatus.NOT_COMMITTED, status.get().status)
        assertEquals(old, file.readText())
        assertNoTemps()
    }

    @Test
    fun deniedPreRenameDoesNotReplaceTarget() {
        val file = temporary.newFile("settings.json")
        file.writeText("""{"future":1}""")
        var replaced = false
        val store = LauncherSettingsStore(file, themeIo = injectedIo(onReplace = { replaced = true }))
        val result = store.prepareOmarchyTheme(payload()).commit { false }
        assertEquals(ThemeCommitStatus.NOT_COMMITTED, result.status)
        assertFalse(replaced)
        assertEquals(1, JSONObject(file.readText()).getInt("future"))
        assertNoTemps()
    }

    @Test
    fun replacementFailureRemovesStagedFileAndRetainsOldDocument() {
        val file = temporary.newFile("settings.json")
        val old = """{"future":1}"""
        file.writeText(old)
        val store = LauncherSettingsStore(file, themeIo = injectedIo(onReplace = {
            throw java.io.IOException("rename failed")
        }))
        val result = store.prepareOmarchyTheme(payload()).commit { true }
        assertEquals(ThemeCommitStatus.NOT_COMMITTED, result.status)
        assertEquals("rename failed", result.failure?.message)
        assertEquals(old, file.readText())
        assertNoTemps()
    }

    @Test
    fun postRenameSyncFailureReportsIndeterminateWithNewTarget() {
        val file = temporary.newFile("settings.json")
        file.writeText("""{"future":1}""")
        val store = LauncherSettingsStore(file, themeIo = injectedIo(onSync = {
            throw java.io.IOException("directory fsync failed")
        }))
        val result = store.prepareOmarchyTheme(payload()).commit { true }
        assertEquals(ThemeCommitStatus.INDETERMINATE, result.status)
        assertEquals("directory fsync failed", result.failure?.message)
        assertEquals("Nord", JSONObject(file.readText()).getJSONObject("omarchyTheme").getString("name"))
        assertNoTemps()
    }

    @Test
    fun stagingHoldsStoreMonitorAgainstOtherSettingsWriters() {
        val file = temporary.newFile("settings.json")
        file.writeText("""{"future":1}""")
        val staging = CountDownLatch(1)
        val release = CountDownLatch(1)
        val otherDone = CountDownLatch(1)
        val store = LauncherSettingsStore(file, themeIo = injectedIo(onStage = {
            staging.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }))
        val transaction = Thread { store.prepareOmarchyTheme(payload()).commit { true } }
        transaction.start()
        var other: Thread? = null
        try {
            assertTrue(staging.await(5, TimeUnit.SECONDS))
            other = Thread {
                store.updateRaw { root -> root.put("otherWriter", 2) }
                otherDone.countDown()
            }.also { it.start() }
            assertFalse(otherDone.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            transaction.join(5000)
            other?.join(5000)
        }
        assertTrue(otherDone.await(1, TimeUnit.SECONDS))
        val root = JSONObject(file.readText())
        assertEquals(2, root.getInt("otherWriter"))
        assertEquals("Nord", root.getJSONObject("omarchyTheme").getString("name"))
        assertNoTemps()
    }

    @Test
    fun preparedThemeCommitsAgainstLatestDocumentWithoutLosingPeerUpdate() {
        val file = temporary.newFile("prepared-settings.json")
        file.writeText("""{"future":{"keep":true}}""")
        val store = LauncherSettingsStore(file)
        val prepared = store.prepareOmarchyTheme(JSONObject("""{"name":"Nord","colors":{"accent":"#81a1c1"}}"""))
        store.updatePeer(OmarchyPeer("10.0.0.8", 8753, "desk"))

        assertEquals(ThemeCommitStatus.COMMITTED, prepared.commit { true }.status)
        val root = JSONObject(file.readText())
        assertEquals("Nord", root.getJSONObject("omarchyTheme").getString("name"))
        assertEquals("desk", root.getJSONObject("omarchyPeer").getString("id"))
        assertTrue(root.getJSONObject("future").getBoolean("keep"))
    }

    @Test fun staleFullModelWriterPreservesPersistedThemeButExplicitThemeSetterCanChangeIt() {
        val file = temporary.newFile("stale-theme.json")
        file.writeText("{}")
        val store = LauncherSettingsStore(file)
        val stale = store.read()
        assertEquals(ThemeCommitStatus.COMMITTED, store.prepareOmarchyTheme(payload()).commit { true }.status)
        val saved = store.write(stale.copy(textScale = 1.4))
        assertEquals("Nord", saved.toJson().getJSONObject("omarchyTheme").getString("name"))
        assertEquals("Nord", JSONObject(file.readText()).getJSONObject("omarchyTheme").getString("name"))
        assertEquals(1.4, store.read().textScale, 0.0)
        store.updateOmarchyTheme(JSONObject("""{"name":"Solar","colors":{"accent":"#ff0000"}}"""))
        assertEquals("Solar", JSONObject(file.readText()).getJSONObject("omarchyTheme").getString("name"))
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
