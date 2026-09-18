package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OmarchyBackgroundSyncStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun persistsAndLoadsCatalogWithConstrainedLocalPreview() {
        val root = temporary.newFolder("config")
        val preview = root.resolve("shared/background-previews/sky.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val payload = JSONObject(
            """{"current":{"id":"wallpaper/sky.png","type":"image","path":"sky.png"},"backgrounds":[{"id":"wallpaper/sky.png","label":"Sky","type":"image","previewPath":${JSONObject.quote(preview.absolutePath)}}]}""",
        )

        val store = OmarchyBackgroundSyncStore(root)
        val catalog = store.persist(payload)

        assertEquals("wallpaper/sky.png", catalog?.current?.id)
        assertEquals(preview.canonicalPath, catalog?.backgrounds?.single()?.previewPath)
        assertEquals(catalog, store.load())
        assertTrue(root.resolve("omarchy_background_catalog.json").isFile)
        assertEquals(listOf<Byte>(1, 2, 3), store.loadPreview(catalog!!.backgrounds.single())?.toList())
    }

    @Test
    fun rejectsCatalogWhenPreviewEscapesSharedDirectory() {
        val root = temporary.newFolder("config")
        val outside = temporary.newFile("outside.jpg")
        val payload = JSONObject(
            """{"current":{"id":"safe","type":"image","path":"safe"},"backgrounds":[{"id":"safe","label":"Safe","type":"image","previewPath":${JSONObject.quote(outside.absolutePath)}}]}""",
        )

        val store = OmarchyBackgroundSyncStore(root)

        assertNull(store.persist(payload))
        assertNull(store.load())
    }

    @Test
    fun currentObjectIdIsPreservedForCarouselSelection() {
        val root = temporary.newFolder("config")
        val payload = JSONObject(
            """{"current":{"id":"second","type":"audio","path":"bars"},"backgrounds":[{"id":"first","label":"First","type":"image"},{"id":"second","label":"Second","type":"audio"}]}""",
        )

        val catalog = OmarchyBackgroundSyncStore(root).persist(payload)

        assertEquals("second", catalog?.current?.id)
        assertEquals(listOf("first", "second"), catalog?.backgrounds?.map { it.id })
    }

    @Test
    fun pendingSelectionIsOnlyClearedByMatchingAcknowledgement() {
        val pending = OmarchyPendingSelection()

        pending.select("second")
        assertTrue(pending.snapshot().getBoolean("pending"))
        assertEquals("second", pending.snapshot().getString("id"))

        pending.acknowledge(JSONObject().put("id", "first"))
        assertEquals("second", pending.snapshot().getString("id"))

        pending.acknowledge(JSONObject().put("id", "second"))
        assertEquals(false, pending.snapshot().getBoolean("pending"))
    }
}