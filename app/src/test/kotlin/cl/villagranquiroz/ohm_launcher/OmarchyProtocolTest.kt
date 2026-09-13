package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmarchyProtocolTest {
    @Test
    fun resolvesEverySupportedRestRouteByMethodAndExactPath() {
        assertEquals(OmarchyRestRoute.DISCOVER, OmarchyRestRoute.resolve("GET", "/omarchy/discover"))
        assertEquals(OmarchyRestRoute.CLIPBOARD_GET, OmarchyRestRoute.resolve("GET", "/omarchy/clipboard"))
        assertEquals(OmarchyRestRoute.CLIPBOARD_PUT, OmarchyRestRoute.resolve("PUT", "/omarchy/clipboard"))
        assertEquals(OmarchyRestRoute.FILE_UPLOAD, OmarchyRestRoute.resolve("POST", "/omarchy/file"))
        assertEquals(OmarchyRestRoute.FILE_DOWNLOAD, OmarchyRestRoute.resolve("GET", "/omarchy/file"))
        assertEquals(OmarchyRestRoute.FILES_LIST, OmarchyRestRoute.resolve("GET", "/omarchy/files"))
        assertEquals(OmarchyRestRoute.INPUT, OmarchyRestRoute.resolve("POST", "/omarchy/input"))
        assertEquals(OmarchyRestRoute.THEME_GET, OmarchyRestRoute.resolve("GET", "/omarchy/theme"))
        assertEquals(OmarchyRestRoute.THEME_PUT, OmarchyRestRoute.resolve("PUT", "/omarchy/theme"))
        assertEquals(OmarchyRestRoute.SCREEN_START, OmarchyRestRoute.resolve("POST", "/omarchy/screen/start"))
        assertEquals(OmarchyRestRoute.SCREEN_STOP, OmarchyRestRoute.resolve("POST", "/omarchy/screen/stop"))
        assertEquals(OmarchyRestRoute.PHOTOS_BACKUP, OmarchyRestRoute.resolve("POST", "/omarchy/photos/backup"))
        assertNull(OmarchyRestRoute.resolve("POST", "/omarchy/clipboard"))
        assertNull(OmarchyRestRoute.resolve("GET", "/omarchy/discover/extra"))
    }

    @Test
    fun requestModelDecodesQueryAndRejectsAmbiguousInput() {
        val request = OmarchyRestRequest.fromUri(
            "get",
            "/omarchy/file?path=%2Fsdcard%2FMy+Photo.jpg",
        )
        assertEquals(OmarchyRestRoute.FILE_DOWNLOAD, request?.route)
        assertEquals("/sdcard/My Photo.jpg", request?.query?.get("path"))
        assertNull(OmarchyRestRequest.fromUri("GET", "/omarchy/file?path=one&path=two"))
        assertNull(OmarchyRestRequest.fromUri("GET", "/omarchy/file?bad=%ZZ"))
        assertNull(OmarchyRestRequest.fromUri("GET", "/omarchy/file#fragment"))
    }

    @Test
    fun clipboardPayloadRequiresBoundedStringAndRoundTrips() {
        val payload = ClipboardPayload.parse(JSONObject("""{"text":"hello"}"""))

        assertEquals("hello", payload?.text)
        assertEquals("hello", payload?.toJson()?.getString("text"))
        assertNull(ClipboardPayload.parse(JSONObject("""{"text":2}""")))
        assertNull(ClipboardPayload.parse(JSONObject()))
        assertNull(ClipboardPayload.parse(JSONObject().put("text", "x".repeat(ClipboardPayload.MAX_TEXT_LENGTH + 1))))
    }

    @Test
    fun discoverAndFileResponsesUseTheDartWireContract() {
        val discover = DiscoverResponse(
            name = "OhmLauncher",
            model = "Android",
            version = 1,
            lanIp = "192.168.1.10",
            port = 8753,
            capabilities = listOf("clipboard", "file", "files", "theme", "screen", "photos", "input"),
        ).toJson()
        assertEquals("192.168.1.10", discover.getString("lan_ip"))
        assertEquals(8753, discover.getInt("port"))
        assertEquals("input", discover.getJSONArray("capabilities").getString(6))

        val entry = OmarchyFileEntry("Camera", "/sdcard/DCIM/Camera", true, 0, 1234)
        val files = FilesResponse("/sdcard/DCIM", "/sdcard", listOf(entry)).toJson()
        val encodedEntry = files.getJSONArray("entries").getJSONObject(0)
        assertTrue(encodedEntry.getBoolean("isDir"))
        assertEquals(1234L, encodedEntry.getLong("modified"))
    }

    @Test
    fun standardResponsesHaveStableStatusAndErrorBodies() {
        val unsupported = OmarchyApiResponse.unsupported("screen")
        assertEquals(501, unsupported.statusCode)
        assertEquals("unsupported", unsupported.body.getString("error"))
        assertEquals("screen", unsupported.body.getString("capability"))

        val notFound = OmarchyApiResponse.notFound("/omarchy/nope")
        assertEquals(404, notFound.statusCode)
        assertEquals("/omarchy/nope", notFound.body.getString("path"))
        assertEquals(true, OmarchyApiResponse.ok().body.getBoolean("ok"))
    }

    @Test
    fun peerHelloAddsTypeWithoutMutatingDiscoveryResponse() {
        val response = DiscoverResponse("Phone", "Android", 1, "10.0.0.2", 8753, listOf("input"))
        val hello = response.toPeerHelloJson()

        assertEquals("peer_hello", hello.getString("type"))
        assertEquals("Phone", hello.getString("name"))
        assertTrue(!response.toJson().has("type"))
    }
}
