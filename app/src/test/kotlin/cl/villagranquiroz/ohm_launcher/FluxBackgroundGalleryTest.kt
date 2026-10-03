package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FluxBackgroundGalleryTest {
    private val a = Any()
    private val b = Any()
    private var live: Any = a
    private var clock = 0L
    private val sent = mutableListOf<JSONObject>()
    private val catalogs = mutableListOf<FluxBackgroundGallery.Catalog>()
    private val results = mutableListOf<Boolean>()
    private val errors = mutableListOf<Any>()
    private val resultGuards = mutableListOf<() -> Boolean>()
    private val errorGuards = mutableListOf<() -> Boolean>()
    private var sendHook: ((Any, JSONObject, () -> Boolean) -> Unit)? = null
    private val operation = "a".repeat(32)
    private val nextOperation = "b".repeat(32)
    private val selectionOperation = "c".repeat(32)
    private val revision = "d".repeat(64)
    private val id = "1".repeat(64)
    private val jpeg by lazy { image("jpeg") }

    private fun image(format: String, width: Int = 32, height: Int = 18): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out))
        return out.toByteArray()
    }
    private fun client() = FluxBackgroundGallery<Any>(allowed = { it === live },
        send = { session, body, authority ->
            if (sendHook != null) sendHook!!.invoke(session, body, authority)
            else { check(authority()); sent.add(JSONObject(body.toString())) }
        }, onCatalog = { _, catalog -> catalogs.add(catalog) },
        onSelected = { _, ok, authority -> results.add(ok); resultGuards.add(authority) },
        onError = { session, authority -> errors.add(session); errorGuards.add(authority) }, now = { clock })
    private fun begin(operation: String = this.operation, count: Int = 1) = JSONObject()
        .put("kind", "gallery_begin").put("operation", operation).put("theme", "nord")
        .put("revision", revision).put("current", id).put("count", count)
    private fun item(operation: String = this.operation, id: String = this.id, bytes: ByteArray = jpeg) =
        JSONObject().put("kind", "gallery_item").put("operation", operation).put("id", id)
            .put("label", "Nord original").put("preview", Base64.getEncoder().encodeToString(bytes))
    private fun end(operation: String = this.operation) = JSONObject().put("kind", "gallery_end").put("operation", operation)
    private fun selected(operation: String = selectionOperation, ok: Boolean = true) =
        JSONObject().put("kind", "gallery_selected").put("operation", operation).put("ok", ok)
    private fun album(client: FluxBackgroundGallery<Any>, operation: String = this.operation): FluxBackgroundGallery.Catalog {
        assertTrue(client.request(a, operation))
        client.receive(a, begin(operation)); client.receive(a, item(operation)); client.receive(a, end(operation))
        return catalogs.last()
    }

    @Test fun catalogPublishesOnlyAtCompleteEndAndPreviewsAreDefensivelyOwned() {
        val client = client()
        assertTrue(client.request(a, operation))
        assertEquals(setOf("kind", "operation"), sent.single().keys().asSequence().toSet())
        client.receive(a, begin()); client.receive(a, item())
        assertTrue(catalogs.isEmpty())
        client.receive(a, end())
        val catalog = catalogs.single()
        assertEquals("nord", catalog.theme); assertEquals(revision, catalog.revision); assertEquals(id, catalog.current)
        assertArrayEquals(jpeg, catalog.items.single().preview)
        catalog.items.single().preview.fill(0)
        assertArrayEquals(jpeg, catalog.items.single().preview)
        assertThrows(UnsupportedOperationException::class.java) { (catalog.items as MutableList<*>).clear() }
        assertTrue(client.isCurrent(a, catalog))
        assertEquals(1, sent.size) // Receipt neither selects nor uploads a preview.
    }

    @Test fun foreignOperationsAndUnsupportedGalleryFramesNeverEchoOrDisplaceCurrentReceive() {
        val client = client()
        assertTrue(client.request(a, operation))
        assertTrue(client.receive(b, begin()))
        assertTrue(client.receive(a, begin(nextOperation)))
        assertTrue(client.receive(a, JSONObject().put("kind", "gallery_select").put("operation", operation)))
        assertTrue(client.receive(a, JSONObject().put("kind", "gallery_future").put("operation", operation)))
        assertFalse(client.receive(a, JSONObject().put("kind", "begin").put("operation", operation)))
        client.receive(a, begin()); client.receive(a, item()); client.receive(a, end())
        assertEquals(1, catalogs.size); assertTrue(errors.isEmpty()); assertEquals(1, sent.size)
    }

    @Test fun partialDuplicateAndMalformedCatalogsNeverPublish() {
        for (mode in listOf("incomplete", "duplicate", "oversized_count", "extra_key", "noninteger_count",
            "invalid_revision", "label", "png", "dimensions", "bytes", "base64")) {
            catalogs.clear(); errors.clear()
            val client = client(); assertTrue(client.request(a, operation))
            val begin = begin(count = if (mode in setOf("incomplete", "duplicate")) 2 else 1)
            when (mode) {
                "oversized_count" -> begin.put("count", FluxBackgroundGallery.MAX_ITEMS + 1)
                "extra_key" -> begin.put("path", "/unused")
                "noninteger_count" -> begin.put("count", 1.0)
                "invalid_revision" -> begin.put("revision", "bad")
            }
            client.receive(a, begin)
            val frame = item()
            when (mode) {
                "label" -> frame.put("label", "x".repeat(161))
                "png" -> frame.put("preview", Base64.getEncoder().encodeToString(image("png")))
                "dimensions" -> frame.put("preview", Base64.getEncoder().encodeToString(image("jpeg", 321, 180)))
                "bytes" -> frame.put("preview", Base64.getEncoder().encodeToString(jpeg.copyOf(FluxBackgroundGallery.MAX_PREVIEW_BYTES + 1)))
                "base64" -> frame.put("preview", frame.getString("preview") + "\n")
            }
            client.receive(a, frame)
            if (mode == "duplicate") client.receive(a, item())
            client.receive(a, end())
            assertTrue(mode, catalogs.isEmpty()); assertEquals(mode, listOf(a), errors)
            client.revoke(a)
        }
    }

    @Test fun emptyAlbumIsValidButCannotSelectAndCurrentNeedNotBeAnAlbumItem() {
        val client = client(); assertTrue(client.request(a, operation))
        client.receive(a, begin(count = 0).put("current", "")); client.receive(a, end())
        val catalog = catalogs.single(); assertTrue(catalog.items.isEmpty())
        assertFalse(client.select(a, catalog, id, selectionOperation))
        assertTrue(client.request(a, nextOperation))
        client.receive(a, begin(nextOperation).put("current", "2".repeat(64)))
        client.receive(a, item(nextOperation)); client.receive(a, end(nextOperation))
        val populated = catalogs.last()
        assertEquals("2".repeat(64), populated.current)
        assertTrue(client.isCurrent(a, populated)); assertFalse(client.isCurrent(a, catalog))
    }

    @Test fun selectUsesLatestOwnedCatalogAndFinalWriterAuthorityWithoutSendingPreview() {
        val client = client(); val old = album(client)
        val latest = album(client, nextOperation)
        assertFalse(client.select(a, old, id, "e".repeat(32)))
        assertFalse(client.select(b, latest, id, "f".repeat(32)))
        assertFalse(client.select(a, latest, "2".repeat(64), "f".repeat(32)))
        assertTrue(client.select(a, latest, id, selectionOperation))
        val packet = sent.last()
        assertEquals(setOf("kind", "operation", "theme", "revision", "id"), packet.keys().asSequence().toSet())
        assertEquals("gallery_select", packet.getString("kind")); assertEquals(revision, packet.getString("revision"))
        client.receive(a, selected(ok = false))
        assertEquals(listOf(false), results)
        sendHook = { _, _, authority -> client.revoke(a); assertFalse(authority()); check(authority()) }
        assertFalse(client.select(a, latest, id, "e".repeat(32)))
        assertFalse(client.isCurrent(a, latest)); assertEquals(listOf(false), results)
    }

    @Test fun catalogAndSelectedResponsesBeforeSendReturnKeepTheirExactMappedTokens() {
        val client = client()
        sendHook = { _, body, authority ->
            assertTrue(authority())
            if (body.getString("kind") == "gallery_request") {
                client.receive(a, begin()); client.receive(a, item()); client.receive(a, end())
            } else client.receive(a, selected())
        }
        assertTrue(client.request(a, operation))
        assertTrue(client.select(a, catalogs.single(), id, selectionOperation))
        assertEquals(listOf(true), results)
        client.receive(a, selected())
        assertEquals(listOf(true), results)
        assertFalse(client.request(a, operation)) // Reusing a nonce cannot admit delayed old frames.
    }

    @Test fun timeoutReplacementAndRevokeIgnoreLateResultsAndReleaseOnlyTheirOwnTokens() {
        val client = client(); assertTrue(client.request(a, operation))
        clock = FluxBackgroundGallery.TIMEOUT_NANOS
        client.expire(); client.expire()
        assertEquals(listOf(a), errors)
        client.receive(a, begin()); client.receive(a, item()); client.receive(a, end())
        assertTrue(catalogs.isEmpty())
        val catalog = album(client, nextOperation)
        assertTrue(client.select(a, catalog, id, selectionOperation))
        clock += FluxBackgroundGallery.TIMEOUT_NANOS
        client.expire(); client.receive(a, selected())
        assertEquals(listOf(false), results)
        live = b
        assertTrue(client.request(b, "e".repeat(32)))
        client.revoke(a)
        client.receive(a, begin(nextOperation)); client.receive(a, selected())
        client.receive(b, begin("e".repeat(32))); client.receive(b, item("e".repeat(32))); client.receive(b, end("e".repeat(32)))
        assertTrue(client.isCurrent(b, catalogs.last()))
        assertEquals(listOf(false), results)
        client.revoke(b)
    }

    @Test fun catalogCallbackRunsOutsideMonitorAndMayRevokeFromAnotherThread() {
        val released = CountDownLatch(1)
        lateinit var client: FluxBackgroundGallery<Any>
        client = FluxBackgroundGallery(allowed = { true }, send = { _, _, authority -> check(authority()) },
            onCatalog = { session, _ ->
                val worker = Thread { client.revoke(session); released.countDown() }.also { it.start() }
                assertTrue("callback held gallery monitor", released.await(2, TimeUnit.SECONDS))
                worker.join(2000)
            }, onSelected = { _, _, _ -> }, onError = { _, _ -> }, now = { clock })
        assertTrue(client.request(a, operation))
        client.receive(a, begin()); client.receive(a, item()); client.receive(a, end())
        assertEquals(0L, released.count)
    }

    @Test fun deferredErrorAndSelectionCallbackGuardsRejectNewRequestAndRevocation() {
        val client = client(); assertTrue(client.request(a, operation))
        clock = FluxBackgroundGallery.TIMEOUT_NANOS
        client.expire()
        val error = errorGuards.single(); assertTrue(error())
        val catalog = album(client, nextOperation)
        assertFalse("an old timeout must not close the reopened gallery", error())
        assertTrue(client.select(a, catalog, id, selectionOperation))
        client.receive(a, selected(ok = false))
        val rejected = resultGuards.single(); assertTrue(rejected())
        val replacement = album(client, "e".repeat(32))
        assertFalse("an old result must not report failure in the new gallery", rejected())
        assertTrue(client.select(a, replacement, id, "f".repeat(32)))
        client.receive(a, selected("f".repeat(32), ok = false))
        val revoked = resultGuards.last(); assertTrue(revoked())
        client.revoke(a)
        assertFalse(revoked())
    }
}
