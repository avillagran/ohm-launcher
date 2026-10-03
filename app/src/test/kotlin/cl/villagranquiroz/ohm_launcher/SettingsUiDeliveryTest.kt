package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.*
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class SettingsUiDeliveryTest {
    @Test fun destroyedActivityCannotPublishQueuedReloadButRecreatedActivityReadsEffectiveSettings() {
        val folder = TemporaryFolder().apply { create() }
        try {
            val store = LauncherSettingsStore(folder.newFile("settings.json").apply { writeText("{}") })
            val oldQueue = mutableListOf<() -> Unit>()
            val newQueue = mutableListOf<() -> Unit>()
            val oldRendered = mutableListOf<LauncherSettings>()
            val newRendered = mutableListOf<LauncherSettings>()
            var oldAlive = true
            SettingsUiDelivery({ oldQueue.add(it) }, store::read).post({ oldAlive }) { oldRendered.add(it) }
            oldAlive = false
            store.updateOmarchyTheme(JSONObject("""{"name":"Recreated","colors":{"background":"#123456"}}"""))
            SettingsUiDelivery({ newQueue.add(it) }, store::read).post({ true }) { newRendered.add(it) }
            oldQueue.single()()
            newQueue.single()()
            assertTrue(oldRendered.isEmpty())
            assertEquals("Recreated", OmarchyThemePalette.fromSettings(newRendered.single().raw)?.name)
        } finally { folder.delete() }
    }
    @Test fun delayedUiDeliveryReadsLatestThemeAndUnrelatedSettingInsteadOfWorkerSnapshot() {
        val folder = TemporaryFolder().apply { create() }
        try {
            val store = LauncherSettingsStore(folder.newFile("settings.json").apply {
                writeText("""{"omarchyTheme":{"name":"One","colors":{"background":"#111111"}},"omarchyBarMode":false}""")
            })
            val queued = mutableListOf<() -> Unit>()
            val delivered = mutableListOf<LauncherSettings>()
            val workerRead = CountDownLatch(1)
            val releaseWorker = CountDownLatch(1)
            val worker = Thread {
                store.read() // Simulate an earlier reload that saw A before the UI runnable runs.
                workerRead.countDown()
                check(releaseWorker.await(5, TimeUnit.SECONDS))
                SettingsUiDelivery({ queued.add(it) }, store::read)
                    .post({ true }) { delivered.add(it) }
            }.also { it.start() }
            try {
                assertTrue(workerRead.await(5, TimeUnit.SECONDS))
                store.updateOmarchyTheme(JSONObject("""{"name":"Two","colors":{"background":"#222222"}}"""))
                store.updateRaw { it.put("omarchyBarMode", true) }
                releaseWorker.countDown()
                worker.join(5000)
                queued.single()()
                assertEquals("Two", OmarchyThemePalette.fromSettings(delivered.single().raw)?.name)
                assertTrue(delivered.single().omarchyBarMode)
            } finally { releaseWorker.countDown(); worker.join(5000) }
        } finally { folder.delete() }
    }

    @Test fun destroyedOrRevokedDeliveryDoesNotPublish() {
        val queued = mutableListOf<() -> Unit>()
        var reads = 0
        var applied = false
        val delivery = SettingsUiDelivery({ queued.add(it) }) {
            reads++
            LauncherSettings.parse("{}")
        }
        delivery.post({ false }) { applied = true }
        queued.single()()
        assertFalse(applied)
        assertEquals(0, reads)
    }
}
