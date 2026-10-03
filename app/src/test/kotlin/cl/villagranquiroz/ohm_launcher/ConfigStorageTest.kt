package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
    fun freshInstallCreatesCuratedSingleDesktopAndBottomFavorites() {
        val root = temporary.newFolder("curated-public").apply { delete() }
        val storage = ConfigStorage(root, temporary.newFolder("curated-legacy"), temporary.newFolder("curated-private"))

        val initialized = storage.initialize(canUsePublicRoot = true)
        val config = storage.read(initialized)

        assertEquals(1, config.desktops.size)
        assertEquals(listOf("clock", "plugin_widget"), config.desktops.single().widgets.map(WidgetNode::type))
        assertTrue(config.desktops.single().ttfx.enabled)
        assertEquals("decrypt", config.desktops.single().ttfx.effect)
        val right = config.edgeBoxes.single()
        assertEquals(EdgePosition.RIGHT, right.edge)
        assertEquals(listOf("Basecamp", "X"), right.items.map(EdgeItemConfig::label))
        assertEquals(
            listOf(
                "com.truecaller/com.truecaller.ui.TruecallerInit",
                "com.android.chrome/com.google.android.apps.chrome.Main",
                "com.termux/com.termux.app.TermuxActivity",
                "com.waze/com.waze.FreeMapAppActivity",
                "com.android.camera/com.android.camera.Camera",
                "com.whatsapp/com.whatsapp.Main",
            ),
            config.favorites,
        )
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
        root.resolve(ConfigStorage.FAVORITES_NAME).writeText("[]")

        assertEquals(listOf("pkg/.Main"), ConfigStorage.toggleActiveFavorite("pkg/.Main"))
        assertEquals(listOf("pkg/.Main"), storage.readFavorites(root))
        assertEquals(widgetsBefore, root.resolve(ConfigStorage.CONFIG_NAME).readText())

        assertEquals(emptyList<String>(), ConfigStorage.toggleActiveFavorite("pkg/.Main"))
    }

    @Test
    fun updateMergesTheLatestDocumentAndRetainsSeparateFavorites() {
        val root = configuredRoot("latest-document")
        val storage = ConfigStorage(root, root, root)
        storage.write(root, JSONObject(root.resolve(ConfigStorage.CONFIG_NAME).readText())
            .put("newerUserField", "retained").toString())
        val favoritesBefore = root.resolve(ConfigStorage.FAVORITES_NAME).readText()

        val result = checkNotNull(storage.update(root, { true }) { document ->
            document.getJSONArray("desktops").getJSONObject(0).put("backgroundImage", "selected.jpg")
            document
        })

        assertEquals("selected.jpg", result.raw.getJSONArray("desktops").getJSONObject(0).getString("backgroundImage"))
        assertEquals("retained", result.raw.getString("newerUserField"))
        assertTrue(result.raw.getJSONObject("future").getBoolean("keep"))
        assertEquals(listOf("pkg/.Main"), result.favorites)
        assertEquals(favoritesBefore, root.resolve(ConfigStorage.FAVORITES_NAME).readText())
    }

    @Test
    fun separateInstancesSerializeTransformsAndReadTheCommittedLatestFields() {
        val root = configuredRoot("two-instances")
        val firstStorage = ConfigStorage(root, root, root)
        val secondStorage = ConfigStorage(root, root, root)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val first = thread(isDaemon = true) {
            try {
                firstStorage.update(root, { true }) { document ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    document.put("firstUserField", 1)
                }
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val second = thread(isDaemon = true) {
            try {
                secondStarted.countDown()
                secondStorage.update(root.resolve("."), { true }) { document ->
                    secondEntered.countDown()
                    assertEquals(1, document.getInt("firstUserField"))
                    document.put("secondUserField", 2)
                }
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        try {
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            first.join(5_000)
            second.join(5_000)
        }
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        failure.get()?.let { throw AssertionError("Concurrent update failed", it) }
        val result = firstStorage.read(root)
        assertEquals(1, result.raw.getInt("firstUserField"))
        assertEquals(2, result.raw.getInt("secondUserField"))
    }

    @Test
    fun fullWriteUsesTheSameRootLockAsUpdateAcrossInstances() {
        val root = configuredRoot("write-versus-update")
        val firstStorage = ConfigStorage(root, root, root)
        val secondStorage = ConfigStorage(root, root, root)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writeStarted = CountDownLatch(1)
        val writeFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val update = thread(isDaemon = true) {
            try {
                firstStorage.update(root, { true }) { document ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    document.put("updated", true)
                }
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val write = thread(isDaemon = true) {
            try {
                writeStarted.countDown()
                secondStorage.write(root, """{"explicitReplacement":true,"desktops":[]}""")
                writeFinished.countDown()
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        try {
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
            assertFalse(writeFinished.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            update.join(5_000)
            write.join(5_000)
        }
        assertFalse(update.isAlive)
        assertFalse(write.isAlive)
        failure.get()?.let { throw AssertionError("Concurrent write failed", it) }
        assertTrue(secondStorage.read(root).raw.getBoolean("explicitReplacement"))
    }

    @Test
    fun revokedAuthoritySkipsReadingTransformingAndStaging() {
        val root = temporary.newFolder("already-revoked")
        val storage = ConfigStorage(root, root, root)
        var transformed = false

        assertNull(storage.update(root, { false }) { transformed = true; it })

        assertFalse(transformed)
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun authorityRevokedAfterStagingKeepsTheOriginalDocumentAndCleansTheStage() {
        val root = configuredRoot("revoked-stage")
        val storage = ConfigStorage(root, root, root)
        val original = root.resolve(ConfigStorage.CONFIG_NAME).readText()
        val authority = AtomicBoolean(true)
        var staged = false

        val result = storage.update(root, {
            if (root.listFiles()!!.any { it.name.startsWith(".${ConfigStorage.CONFIG_NAME}-") }) {
                staged = true
                authority.set(false)
            }
            authority.get()
        }) { document -> document.put("staleRemoteField", true) }

        assertTrue(staged)
        assertNull(result)
        assertEquals(original, root.resolve(ConfigStorage.CONFIG_NAME).readText())
        assertFalse(root.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun failedAtomicRenameDoesNotCopyIntoOrDamageTheExistingDestination() {
        val root = temporary.newFolder("rename-failure")
        val target = root.resolve(ConfigStorage.CONFIG_NAME).apply { mkdir() }
        val marker = target.resolve("keep.txt").apply { writeText("user data") }
        val storage = ConfigStorage(root, root, root)

        assertThrows(IllegalStateException::class.java) {
            storage.write(root, """{"desktops":[]}""")
        }

        assertTrue(target.isDirectory)
        assertEquals("user data", marker.readText())
        assertFalse(root.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun uniqueStagesLeaveAnUnrelatedFixedTemporaryFileUntouched() {
        val root = configuredRoot("unique-stages")
        val storage = ConfigStorage(root, root, root)
        val unrelated = root.resolve("${ConfigStorage.CONFIG_NAME}.tmp").apply { writeText("unrelated writer") }
        val stageNames = mutableSetOf<String>()

        repeat(2) { version ->
            storage.update(root, {
                root.listFiles()!!.filter { it.name.startsWith(".${ConfigStorage.CONFIG_NAME}-") }
                    .forEach { stageNames += it.name }
                true
            }) { document -> document.put("version", version) }
        }

        assertEquals(2, stageNames.size)
        assertEquals("unrelated writer", unrelated.readText())
        assertEquals(1, storage.read(root).raw.getInt("version"))
    }

    private fun configuredRoot(name: String) = temporary.newFolder(name).also { root ->
        root.resolve(ConfigStorage.CONFIG_NAME).writeText(
            """{"future":{"keep":true},"desktops":[{"backgroundImage":"old.jpg","widgets":[]}]}""",
        )
        root.resolve(ConfigStorage.FAVORITES_NAME).writeText("""["pkg/.Main"]""")
    }
}
