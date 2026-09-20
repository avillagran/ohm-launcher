package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalApiServerTest {
    private var server: LocalApiServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun servesHealthOnLoopbackWithCorsHeaders() {
        startServer()

        val response = request("GET", "/health")

        assertEquals(200, response.code)
        assertEquals(true, response.json.getBoolean("ok"))
        assertEquals("OhmLauncher", response.json.getString("name"))
        assertEquals("*", response.allowOrigin)
        assertTrue(server!!.isRunning)
    }

    @Test
    fun delegatesOmarchyDiscoverToInjectedAdapter() {
        startServer(
            omarchyAdapter = object : OmarchyApiAdapter {
                override fun handle(request: OmarchyRestRequest): OmarchyApiResponse {
                    assertEquals(OmarchyRestRoute.DISCOVER, request.route)
                    return OmarchyApiResponse.ok(
                        DiscoverResponse(
                            name = "Phone",
                            model = "Android",
                            version = 1,
                            lanIp = "192.168.1.9",
                            port = 8753,
                            capabilities = listOf("clipboard", "files"),
                        ).toJson(),
                    )
                }
            },
        )

        val response = request("GET", "/omarchy/discover")

        assertEquals(200, response.code)
        assertEquals("Phone", response.json.getString("name"))
        assertEquals("192.168.1.9", response.json.getString("lan_ip"))
    }

    @Test
    fun rejectsProtectedRequestsBeforeReadingTheirBodyWhenSessionTokenIsMissing() {
        startServer(sessionToken = "paired-secret")

        val missing = request("POST", "/command", "not-json")
        val wrong = request("GET", "/omarchy/discover", headers = mapOf("X-Omarchy-Link-Token" to "wrong"))
        val accepted = request("GET", "/health", headers = mapOf("X-Omarchy-Link-Token" to "paired-secret"))

        assertEquals(401, missing.code)
        assertEquals("unauthorized", missing.json.getString("error"))
        assertEquals(401, wrong.code)
        assertEquals(200, accepted.code)
    }

    @Test
    fun permitsQueryTokenOnlyForScreenFrameImageLoading() {
        val frames = LatestScreenFrameStore().apply {
            update(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2), 1220, 2712)
        }
        startServer(screenFrames = frames, sessionToken = "paired-secret")

        assertEquals(200, request("GET", "/omarchy/screen/frame?token=paired-secret").code)
        assertEquals(401, request("GET", "/health?token=paired-secret").code)
    }

    @Test
    fun rejectsWebSocketUpgradeWithoutTheSessionToken() {
        startServer(omarchyAdapter = discoverAdapter(), sessionToken = "paired-secret")

        assertTrue(rawWebSocketStatus().contains(" 401 "))
        assertTrue(
            rawWebSocketStatus(
                requestHeaders = validWebSocketHeaders() + "X-Omarchy-Link-Token: paired-secret",
            ).contains(" 101 "),
        )
    }

    @Test
    fun servesLatestScreenFrameForReversePullTransport() {
        val frames = LatestScreenFrameStore().apply {
            update(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2), 1220, 2712)
        }
        startServer(screenFrames = frames)

        val status = request("GET", "/omarchy/screen/status")
        val frame = request("GET", "/omarchy/screen/frame")

        assertEquals(200, status.code)
        assertEquals(1L, status.json.getLong("frames"))
        assertEquals(1220, status.json.getInt("w"))
        assertEquals(2712, status.json.getInt("h"))
        assertEquals(200, frame.code)
        assertEquals("image/jpeg", frame.contentType)
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2), frame.bytes)
    }

    @Test
    fun upgradesOmarchyWebSocketAndSendsDiscoverAsPeerHello() {
        startServer(
            omarchyAdapter = object : OmarchyApiAdapter {
                override fun handle(request: OmarchyRestRequest): OmarchyApiResponse {
                    assertEquals(OmarchyRestRoute.DISCOVER, request.route)
                    return OmarchyApiResponse.ok(
                        DiscoverResponse("Phone", "Pixel", 1, "192.168.1.9", 8753, listOf("input", "screen")).toJson(),
                    )
                }
            },
        )

        WebSocketTestClient(server!!.boundPort).use { client ->
            assertTrue(client.statusLine.contains(" 101 "))
            assertEquals(webSocketAccept(TEST_WS_KEY), client.headers["sec-websocket-accept"])
            val hello = JSONObject(client.readText())
            assertEquals("peer_hello", hello.getString("type"))
            assertEquals("Phone", hello.getString("name"))
            assertEquals("Pixel", hello.getString("model"))
            assertEquals("192.168.1.9", hello.getString("lan_ip"))
        }
    }

    @Test
    fun rejectsInvalidWebSocketUpgradeVersionAndKey() {
        startServer(omarchyAdapter = discoverAdapter())

        assertTrue(rawWebSocketStatus(requestHeaders = emptyList()).contains(" 400 "))
        assertTrue(rawWebSocketStatus(method = "POST").contains(" 400 "))
        assertTrue(rawWebSocketStatus(httpVersion = "HTTP/1.0").contains(" 400 "))
        assertTrue(
            rawWebSocketStatus(
                requestHeaders = validWebSocketHeaders().map {
                    if (it.startsWith("Sec-WebSocket-Version:")) "Sec-WebSocket-Version: 12" else it
                },
            ).contains(" 426 "),
        )
        assertTrue(
            rawWebSocketStatus(
                requestHeaders = validWebSocketHeaders().map {
                    if (it.startsWith("Sec-WebSocket-Key:")) "Sec-WebSocket-Key: not-a-key" else it
                },
            ).contains(" 400 "),
        )
    }

    @Test
    fun handlesJsonPingInputAndScreenCommands() {
        val requests = mutableListOf<OmarchyRestRequest>()
        startServer(
            omarchyAdapter = object : OmarchyApiAdapter {
                override fun handle(request: OmarchyRestRequest): OmarchyApiResponse {
                    requests += request
                    return when (request.route) {
                        OmarchyRestRoute.DISCOVER -> OmarchyApiResponse.ok(discoverJson())
                        OmarchyRestRoute.INPUT -> OmarchyApiResponse.ok(
                            JSONObject().put("ok", true).put("action", request.body.getString("action")),
                        )
                        else -> OmarchyApiResponse.ok()
                    }
                }
            },
        )

        WebSocketTestClient(server!!.boundPort).use { client ->
            client.readText()
            client.sendText("""{"type":"ping"}""")
            assertEquals("pong", JSONObject(client.readText()).getString("type"))
            client.sendText("""{"type":"input","action":"key","key":"home"}""")
            val inputResult = JSONObject(client.readText())
            assertEquals("input_result", inputResult.getString("type"))
            assertTrue(inputResult.getBoolean("ok"))
            assertEquals("key", inputResult.getString("action"))
            client.sendText("""{"type":"screen_start"}""")
            assertEquals("screen_started", JSONObject(client.readText()).getString("type"))
            client.sendText("""{"type":"screen_stop"}""")
            assertEquals("screen_stopped", JSONObject(client.readText()).getString("type"))
        }

        assertEquals(
            listOf(OmarchyRestRoute.DISCOVER, OmarchyRestRoute.INPUT, OmarchyRestRoute.SCREEN_START, OmarchyRestRoute.SCREEN_STOP),
            requests.map { it.route },
        )
    }

    @Test
    fun handlesProtocolPingPongAndCloseFrames() {
        startServer(omarchyAdapter = discoverAdapter())

        WebSocketTestClient(server!!.boundPort).use { client ->
            client.readText()
            val pingBytes = byteArrayOf(0, 1, 2, 3)
            client.sendFrame(0x9, pingBytes)
            val pong = client.readFrame()
            assertEquals(0xA, pong.opcode)
            assertArrayEquals(pingBytes, pong.payload)

            val closePayload = byteArrayOf(0x03, 0xE8.toByte())
            client.sendFrame(0x8, closePayload)
            val close = client.readFrame()
            assertEquals(0x8, close.opcode)
            assertArrayEquals(closePayload, close.payload)
            assertEquals(-1, client.readByte())
        }
    }

    @Test
    fun closesOnUnmaskedMalformedAndOversizedFrames() {
        startServer(omarchyAdapter = discoverAdapter())

        WebSocketTestClient(server!!.boundPort).use { client ->
            client.readText()
            client.sendFrame(0x1, "{}".toByteArray(), masked = false)
            assertEquals(1002, client.readCloseCode())
        }
        WebSocketTestClient(server!!.boundPort).use { client ->
            client.readText()
            client.sendFrame(0x1, "{}".toByteArray(), fin = false)
            assertEquals(1002, client.readCloseCode())
        }
        WebSocketTestClient(server!!.boundPort).use { client ->
            client.readText()
            client.sendDeclaredLength(0x1, 1_048_577)
            assertEquals(1009, client.readCloseCode())
        }
        WebSocketTestClient(server!!.boundPort).use { client ->
            client.readText()
            client.sendFrame(0x1, byteArrayOf(0xC3.toByte(), 0x28))
            assertEquals(1007, client.readCloseCode())
        }
    }

    @Test
    fun broadcastsSuccessfulClipboardAndScreenRestEventsToAllClients() {
        startServer(
            omarchyAdapter = object : OmarchyApiAdapter {
                override fun handle(request: OmarchyRestRequest): OmarchyApiResponse =
                    if (request.route == OmarchyRestRoute.DISCOVER) OmarchyApiResponse.ok(discoverJson()) else OmarchyApiResponse.ok()
            },
        )

        WebSocketTestClient(server!!.boundPort).use { first ->
            WebSocketTestClient(server!!.boundPort).use { second ->
                first.readText()
                second.readText()
                assertEquals(200, request("PUT", "/omarchy/clipboard", """{"text":"shared"}""").code)
                for (client in listOf(first, second)) {
                    val event = JSONObject(client.readText())
                    assertEquals("clipboard_changed", event.getString("type"))
                    assertEquals("shared", event.getString("text"))
                }
                assertEquals(200, request("POST", "/omarchy/screen/start", "{}").code)
                assertEquals("screen_started", JSONObject(first.readText()).getString("type"))
                assertEquals("screen_started", JSONObject(second.readText()).getString("type"))
                assertEquals(200, request("POST", "/omarchy/screen/stop", "{}").code)
                assertEquals("screen_stopped", JSONObject(first.readText()).getString("type"))
                assertEquals("screen_stopped", JSONObject(second.readText()).getString("type"))
            }
        }
    }

    @Test
    fun serializesConcurrentBroadcastFramesWithoutCorruption() {
        startServer(omarchyAdapter = discoverAdapter())
        val clients = List(3) { WebSocketTestClient(server!!.boundPort).also { it.readText() } }
        try {
            val executor = Executors.newFixedThreadPool(4)
            repeat(100) { index ->
                executor.execute {
                    when (index % 3) {
                        0 -> server!!.broadcastClipboardChanged("value-$index")
                        1 -> server!!.broadcastScreenStarted()
                        else -> server!!.broadcastScreenStopped()
                    }
                }
            }
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

            for (client in clients) {
                val events = List(100) { JSONObject(client.readText()).getString("type") }
                assertEquals(34, events.count { it == "clipboard_changed" })
                assertEquals(33, events.count { it == "screen_started" })
                assertEquals(33, events.count { it == "screen_stopped" })
            }
        } finally {
            clients.forEach(WebSocketTestClient::close)
        }
    }

    @Test
    fun parsesOmarchyClipboardGetAndPutPayloads() {
        val requests = mutableListOf<OmarchyRestRequest>()
        startServer(
            omarchyAdapter = object : OmarchyApiAdapter {
                override fun handle(request: OmarchyRestRequest): OmarchyApiResponse {
                    requests += request
                    return when (request.route) {
                        OmarchyRestRoute.CLIPBOARD_GET -> OmarchyApiResponse.ok(ClipboardPayload("from phone").toJson())
                        OmarchyRestRoute.CLIPBOARD_PUT -> OmarchyApiResponse.ok()
                        else -> OmarchyApiResponse.notFound(request.path)
                    }
                }
            },
        )

        val get = request("GET", "/omarchy/clipboard")
        val put = request("PUT", "/omarchy/clipboard", """{"text":"from desktop"}""")
        val invalid = request("PUT", "/omarchy/clipboard", """{"text":7}""")

        assertEquals("from phone", get.json.getString("text"))
        assertEquals(200, put.code)
        assertEquals("from desktop", requests[1].body.getString("text"))
        assertEquals(400, invalid.code)
        assertEquals("invalid_clipboard", invalid.json.getString("error"))
        assertEquals(2, requests.size)
    }

    @Test
    fun uploadsAndDownloadsOmarchyFilesWithoutCorruptingBinaryBytes() {
        var uploadedName = ""
        var uploadedBytes = byteArrayOf()
        val downloadBytes = byteArrayOf(0, 13, 10, -1, 42)
        startServer(
            omarchyAdapter = object : OmarchyApiAdapter {
                override fun handle(request: OmarchyRestRequest) = OmarchyApiResponse.notFound(request.path)

                override fun uploadFile(name: String, bytes: ByteArray): OmarchyApiResponse {
                    uploadedName = name
                    uploadedBytes = bytes
                    return OmarchyApiResponse.ok(JSONObject().put("name", name))
                }

                override fun downloadFile(path: String): OmarchyFileDownload {
                    assertEquals("/sdcard/My Photo.bin", path)
                    return OmarchyFileDownload(downloadBytes, "application/octet-stream", "My Photo.bin")
                }
            },
        )
        val uploadBytes = byteArrayOf(0, -1, 13, 10, 7)
        val boundary = "ohm-test-boundary"
        val multipart = multipartFile(boundary, "photo.bin", uploadBytes)

        val upload = request(
            "POST",
            "/omarchy/file",
            rawBody = multipart,
            contentType = "multipart/form-data; boundary=$boundary",
        )
        val download = request("GET", "/omarchy/file?path=%2Fsdcard%2FMy+Photo.bin")

        assertEquals(200, upload.code)
        assertEquals("photo.bin", uploadedName)
        assertArrayEquals(uploadBytes, uploadedBytes)
        assertEquals(200, download.code)
        assertArrayEquals(downloadBytes, download.bytes)
        assertEquals("application/octet-stream", download.contentType)
        assertTrue(download.contentDisposition.orEmpty().contains("My Photo.bin"))
        assertEquals(400, request("GET", "/omarchy/file").code)
    }

    @Test
    fun delegatesFilesThemeInputAndScreenRoutesWithParsedRequests() {
        val requests = mutableListOf<OmarchyRestRequest>()
        startServer(
            omarchyAdapter = object : OmarchyApiAdapter {
                override fun handle(request: OmarchyRestRequest): OmarchyApiResponse {
                    requests += request
                    return when (request.route) {
                        OmarchyRestRoute.FILES_LIST -> OmarchyApiResponse.ok(
                            FilesResponse(request.query.getValue("path"), "/", emptyList()).toJson(),
                        )
                        OmarchyRestRoute.THEME_GET -> OmarchyApiResponse.ok(JSONObject().put("name", "tokyo-night"))
                        OmarchyRestRoute.SCREEN_START -> OmarchyApiResponse.ok(JSONObject().put("stream", "ready"))
                        else -> OmarchyApiResponse.ok()
                    }
                }
            },
        )

        assertEquals("/sdcard", request("GET", "/omarchy/files").json.getString("path"))
        assertEquals("tokyo-night", request("GET", "/omarchy/theme").json.getString("name"))
        assertEquals(200, request("PUT", "/omarchy/theme", """{"name":"catppuccin"}""").code)
        assertEquals(200, request("POST", "/omarchy/input", """{"action":"key","key":"home"}""").code)
        assertEquals("ready", request("POST", "/omarchy/screen/start", "{}").json.getString("stream"))
        assertEquals(200, request("POST", "/omarchy/screen/stop", "{}").code)

        assertEquals("catppuccin", requests[2].body.getString("name"))
        assertEquals("key", requests[3].body.getString("action"))
        assertEquals(
            listOf(
                OmarchyRestRoute.FILES_LIST,
                OmarchyRestRoute.THEME_GET,
                OmarchyRestRoute.THEME_PUT,
                OmarchyRestRoute.INPUT,
                OmarchyRestRoute.SCREEN_START,
                OmarchyRestRoute.SCREEN_STOP,
            ),
            requests.map { it.route },
        )
    }

    @Test
    fun rejectsOversizedMalformedAndAmbiguousOmarchyRequests() {
        startServer(
            omarchyAdapter = object : OmarchyApiAdapter {
                override fun handle(request: OmarchyRestRequest) = OmarchyApiResponse.ok()
            },
        )

        val oversized = JSONObject().put("name", "x".repeat(1_048_577)).toString()

        assertEquals(413, request("PUT", "/omarchy/theme", oversized).code)
        assertEquals("invalid_json", request("PUT", "/omarchy/theme", "{").json.getString("error"))
        assertEquals(400, request("GET", "/omarchy/file?path=one&path=two").code)
        assertEquals(404, request("GET", "/omarchy/nope").code)
        assertEquals(404, request("POST", "/omarchy/clipboard", "{}").code)
    }

    @Test
    fun rejectsUnsafeMultipartFileNamesBeforeCallingAdapter() {
        var uploads = 0
        startServer(
            omarchyAdapter = object : OmarchyApiAdapter {
                override fun handle(request: OmarchyRestRequest) = OmarchyApiResponse.ok()
                override fun uploadFile(name: String, bytes: ByteArray): OmarchyApiResponse {
                    uploads++
                    return OmarchyApiResponse.ok()
                }
            },
        )
        val boundary = "safe-boundary"

        val response = request(
            "POST",
            "/omarchy/file",
            rawBody = multipartFile(boundary, "../escape.bin", byteArrayOf(1)),
            contentType = "multipart/form-data; boundary=$boundary",
        )

        assertEquals(400, response.code)
        assertEquals("bad_multipart", response.json.getString("error"))
        assertEquals(0, uploads)
    }

    @Test
    fun delegatesCommandsAndSerializesShellResult() {
        var receivedCommand = ""
        var receivedArgs: List<String>? = null
        startServer(
            onCommand = CommandHandler { command, args ->
                receivedCommand = command
                receivedArgs = args
                ShellResult(3, "out", "err", "embedded")
            },
        )

        val response = request("POST", "/command", """{"command":"tool","args":["a",2]}""")

        assertEquals(200, response.code)
        assertEquals("tool", receivedCommand)
        assertEquals(listOf("a", "2"), receivedArgs)
        assertEquals(3, response.json.getInt("exitCode"))
        assertEquals("embedded", response.json.getString("via"))
        assertEquals("missing_command", request("POST", "/command", "{}").json.getString("error"))
    }

    @Test
    fun injectsWidgetsUsingJsonAsDefaultFormat() {
        var injected: Pair<String, String>? = null
        startServer(onInjectWidget = WidgetHandler { source, format -> injected = source to format })

        val response = request("POST", "/widget", """{"source":"{widget}"}""")

        assertEquals(200, response.code)
        assertEquals("{widget}" to "json", injected)
        assertTrue(response.json.getBoolean("ok"))
        assertEquals("missing_source", request("POST", "/widget", "{}").json.getString("error"))
    }

    @Test
    fun delegatesAiPromptAndValidHistoryAndSerializesGeneratedWidget() {
        var receivedPrompt = ""
        var receivedHistory: List<AiMessage>? = null
        startServer(
            onChat = ChatHandler { prompt, history ->
                receivedPrompt = prompt
                receivedHistory = history
                AiResponse("done", "Text { text: \"ok\" }", "qml")
            },
        )

        val response = request(
            "POST",
            "/ai",
            """{"prompt":"build","history":[{"role":"user","content":"first"},{"role":3,"content":"ignored"}]}""",
        )

        assertEquals(200, response.code)
        assertEquals("build", receivedPrompt)
        assertEquals(listOf(AiMessage("user", "first")), receivedHistory)
        assertEquals("done", response.json.getString("text"))
        assertEquals("Text { text: \"ok\" }", response.json.getString("widgetSource"))
        assertEquals("qml", response.json.getString("widgetFormat"))
    }

    @Test
    fun reportsMissingOrUnconfiguredAi() {
        startServer()

        assertEquals("ai_not_configured", request("POST", "/ai", """{"prompt":"hello"}""").json.getString("error"))
        assertEquals(501, request("POST", "/ai", """{"prompt":"hello"}""").code)

        server?.close()
        startServer(onChat = ChatHandler { _, _ -> AiResponse("unused") })
        assertEquals("missing_prompt", request("POST", "/ai", "{}").json.getString("error"))
    }

    @Test
    fun listsBinsUsingGetAndPost() {
        startServer(onListBins = ListBinsHandler { listOf(mapOf("name" to "herdr", "size" to 12)) })

        for (method in listOf("GET", "POST")) {
            val response = request(method, "/bins", if (method == "POST") "{}" else null)
            assertEquals(200, response.code)
            assertEquals("herdr", response.json.getJSONArray("bins").getJSONObject(0).getString("name"))
        }
    }

    @Test
    fun installsDecodedBinaryAndRejectsUnsafeInput() {
        var installedName = ""
        var installedBytes = byteArrayOf()
        startServer(onInstallBin = InstallBinHandler { name, bytes ->
            installedName = name
            installedBytes = bytes
            mapOf("ok" to true, "name" to name)
        })
        val payload = byteArrayOf(0, 1, 2, 127, -1)

        val response = request(
            "POST",
            "/install-bin",
            JSONObject().put("name", "tool-1.0").put("base64", Base64.getEncoder().encodeToString(payload)).toString(),
        )

        assertEquals(200, response.code)
        assertEquals("tool-1.0", installedName)
        assertArrayEquals(payload, installedBytes)
        assertEquals("invalid_name", request("POST", "/install-bin", """{"name":"../x","base64":"AA=="}""").json.getString("error"))
        assertEquals("missing_base64", request("POST", "/install-bin", """{"name":"safe"}""").json.getString("error"))
        assertEquals("bad_base64", request("POST", "/install-bin", """{"name":"safe","base64":"%%%"}""").json.getString("error"))
    }

    @Test
    fun streamsRawBinaryWithoutJsonOrBase64() {
        var bytes = byteArrayOf()
        startServer(onInstallBinRaw = InstallBinRawHandler { name, input ->
            bytes = input.readBytes()
            mapOf("ok" to true, "name" to name)
        })
        val payload = ByteArray(32_000) { (it % 251).toByte() }

        val response = request("POST", "/install-bin-raw?name=large-bin", rawBody = payload)

        assertEquals(200, response.code)
        assertArrayEquals(payload, bytes)
        assertEquals("large-bin", response.json.getString("name"))
        assertEquals("invalid_name", request("POST", "/install-bin-raw?name=..", rawBody = payload).json.getString("error"))
    }

    @Test
    fun acceptsChunkedRawBinaryBodies() {
        var bytes = byteArrayOf()
        startServer(onInstallBinRaw = InstallBinRawHandler { _, input ->
            bytes = input.readBytes()
            mapOf("ok" to true)
        })

        val status = Socket("127.0.0.1", server!!.boundPort).use { socket ->
            socket.soTimeout = 2_000
            val output = socket.getOutputStream()
            output.write(
                ("POST /install-bin-raw?name=chunked HTTP/1.1\r\n" +
                    "Host: localhost\r\nTransfer-Encoding: chunked\r\n\r\n" +
                    "3\r\nabc\r\n4\r\ndefg\r\n0\r\n\r\n").toByteArray(),
            )
            output.flush()
            BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
        }

        assertTrue(status.contains(" 200 "))
        assertArrayEquals("abcdefg".toByteArray(), bytes)
    }

    @Test
    fun delegatesUninstallAndQuake() {
        var uninstalled = ""
        val quakeStates = mutableListOf<Boolean>()
        startServer(
            onUninstallBin = UninstallBinHandler { name ->
                uninstalled = name
                mapOf("removed" to true)
            },
            onQuake = QuakeHandler { quakeStates += it },
        )

        assertEquals(200, request("POST", "/uninstall-bin", """{"name":"tool"}""").code)
        assertEquals("tool", uninstalled)
        assertEquals(true, request("POST", "/quake", "{}").json.getBoolean("open"))
        assertEquals(false, request("POST", "/quake", """{"open":false}""").json.getBoolean("open"))
        assertEquals(listOf(true, false), quakeStates)
    }

    @Test
    fun returnsProtocolErrorsAndUnsupportedResponses() {
        startServer()

        assertEquals(204, request("OPTIONS", "/command").code)
        assertEquals("method_not_allowed", request("PUT", "/health", "{}").json.getString("error"))
        assertEquals("not_found", request("POST", "/unknown", "{}").json.getString("error"))
        assertEquals(501, request("GET", "/bins").code)
        assertEquals("install_not_supported", request("POST", "/install-bin", """{"name":"x","base64":"AA=="}""").json.getString("error"))
        assertEquals("quake_not_supported", request("POST", "/quake", "{}").json.getString("error"))
    }

    @Test
    fun convertsCallbackFailuresToJsonServerErrors() {
        startServer(onCommand = CommandHandler { _, _ -> error("boom") })

        val response = request("POST", "/command", """{"command":"x"}""")

        assertEquals(500, response.code)
        assertEquals("server_error", response.json.getString("error"))
        assertTrue(response.json.getString("detail").contains("boom"))
    }

    @Test
    fun receivesAndListsOmarchyNotifyChannelMessages() {
        val messages = mutableListOf<OmarchyNotification>()
        startServer(notificationChannel = object : OmarchyNotificationChannel {
            override fun receive(payload: JSONObject): OmarchyNotification =
                OmarchyNotification.fromJson(payload, now = 77L).also(messages::add)

            override fun load(): List<OmarchyNotification> = messages.toList()
        })

        val accepted = request(
            "POST",
            "/omarchy/notify",
            """{"id":"hermes-1","title":"Hermes","message":"Build finished","source":"omarchy-hermes"}""",
        )
        val listed = request("GET", "/omarchy/notify")
        val rejected = request("POST", "/omarchy/notify", "{}")

        assertEquals(200, accepted.code)
        assertEquals("hermes-1", accepted.json.getString("id"))
        assertEquals(1, listed.json.getJSONArray("messages").length())
        assertEquals("Build finished", listed.json.getJSONArray("messages").getJSONObject(0).getString("message"))
        assertEquals(400, rejected.code)
    }

    @Test
    fun routesReverseSyncedBackgroundCatalogAndSelectionCallbacks() {
        var catalog: JSONObject? = null
        var acknowledged: JSONObject? = null
        startServer(
            onBackgroundCatalogPut = { catalog = it },
            onBackgroundSelectionGet = { JSONObject().put("pending", true).put("id", "wallpaper/sky.png") },
            onBackgroundSelectionAck = { acknowledged = it },
        )

        val put = request(
            "PUT",
            "/omarchy/backgrounds/catalog",
            """{"current":{"id":"wallpaper/sky.png"},"backgrounds":[]}""",
        )
        val selection = request("GET", "/omarchy/backgrounds/selection")
        val ack = request("PUT", "/omarchy/backgrounds/selection/ack", """{"id":"wallpaper/sky.png"}""")

        assertEquals(200, put.code)
        assertEquals("wallpaper/sky.png", catalog!!.getJSONObject("current").getString("id"))
        assertTrue(selection.json.getBoolean("pending"))
        assertEquals("wallpaper/sky.png", selection.json.getString("id"))
        assertEquals(200, ack.code)
        assertEquals("wallpaper/sky.png", acknowledged!!.getString("id"))
    }

    private fun startServer(
        onCommand: CommandHandler = CommandHandler { _, _ -> ShellResult(0, "", "", "embedded") },
        onInjectWidget: WidgetHandler = WidgetHandler { _, _ -> },
        onChat: ChatHandler? = null,
        onInstallBin: InstallBinHandler? = null,
        onInstallBinRaw: InstallBinRawHandler? = null,
        onListBins: ListBinsHandler? = null,
        onUninstallBin: UninstallBinHandler? = null,
        onQuake: QuakeHandler? = null,
        omarchyAdapter: OmarchyApiAdapter? = null,
        screenFrames: LatestScreenFrameStore? = null,
        notificationChannel: OmarchyNotificationChannel? = null,
        onBackgroundCatalogPut: ((JSONObject) -> Unit)? = null,
        onBackgroundSelectionGet: (() -> JSONObject)? = null,
        onBackgroundSelectionAck: ((JSONObject) -> Unit)? = null,
        sessionToken: String? = null,
    ) {
        server = LocalApiServer(
            port = 0,
            onCommand = onCommand,
            onInjectWidget = onInjectWidget,
            onChat = onChat,
            onInstallBin = onInstallBin,
            onInstallBinRaw = onInstallBinRaw,
            onListBins = onListBins,
            onUninstallBin = onUninstallBin,
            onQuake = onQuake,
            omarchyAdapter = omarchyAdapter,
            screenFrames = screenFrames,
            notificationChannel = notificationChannel,
            onBackgroundCatalogPut = onBackgroundCatalogPut,
            onBackgroundSelectionGet = onBackgroundSelectionGet,
            onBackgroundSelectionAck = onBackgroundSelectionAck,
            sessionToken = sessionToken,
        ).also { it.start() }
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        rawBody: ByteArray? = body?.toByteArray(),
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val connection = URL("http://127.0.0.1:${server!!.boundPort}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        headers.forEach(connection::setRequestProperty)
        if (rawBody != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(rawBody) }
        }
        val code = connection.responseCode
        val stream = if (code >= 400) connection.errorStream else connection.inputStream
        val bytes = stream?.use { it.readBytes() } ?: byteArrayOf()
        val response = Response(
            code,
            bytes,
            connection.getHeaderField("Access-Control-Allow-Origin"),
            connection.contentType,
            connection.getHeaderField("Content-Disposition"),
        )
        connection.disconnect()
        return response
    }

    private fun rawWebSocketStatus(
        method: String = "GET",
        httpVersion: String = "HTTP/1.1",
        requestHeaders: List<String> = validWebSocketHeaders(),
    ): String = Socket("127.0.0.1", server!!.boundPort).use { socket ->
        socket.soTimeout = 2_000
        socket.getOutputStream().apply {
            write(
                ("$method /omarchy/ws $httpVersion\r\n" + requestHeaders.joinToString("\r\n") + "\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII),
            )
            flush()
        }
        readAsciiLine(socket.getInputStream())
    }

    private fun discoverAdapter(): OmarchyApiAdapter = object : OmarchyApiAdapter {
        override fun handle(request: OmarchyRestRequest): OmarchyApiResponse =
            if (request.route == OmarchyRestRoute.DISCOVER) OmarchyApiResponse.ok(discoverJson())
            else OmarchyApiResponse.ok()
    }

    private fun multipartFile(boundary: String, name: String, bytes: ByteArray): ByteArray =
        ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray() +
            bytes + "\r\n--$boundary--\r\n".toByteArray()

    private data class Response(
        val code: Int,
        val bytes: ByteArray,
        val allowOrigin: String?,
        val contentType: String?,
        val contentDisposition: String?,
    ) {
        val json: JSONObject
            get() = if (bytes.isEmpty()) JSONObject() else JSONObject(bytes.toString(Charsets.UTF_8))
    }

    private class WebSocketTestClient(
        port: Int,
        requestHeaders: List<String> = listOf(
            "Host: localhost",
            "Upgrade: websocket",
            "Connection: Upgrade",
            "Sec-WebSocket-Version: 13",
            "Sec-WebSocket-Key: $TEST_WS_KEY",
        ),
    ) : Closeable {
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 2_000 }
        private val input = socket.getInputStream()
        private val output = socket.getOutputStream()
        val statusLine: String
        val headers: Map<String, String>

        init {
            output.write(
                ("GET /omarchy/ws HTTP/1.1\r\n" + requestHeaders.joinToString("\r\n") + "\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII),
            )
            output.flush()
            statusLine = readAsciiLine(input)
            headers = buildMap {
                while (true) {
                    val line = readAsciiLine(input)
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    put(line.substring(0, colon).lowercase(), line.substring(colon + 1).trim())
                }
            }
        }

        fun readText(): String {
            val frame = readFrame()
            assertEquals(1, frame.opcode)
            return frame.payload.toString(StandardCharsets.UTF_8)
        }

        fun sendText(text: String) = sendFrame(0x1, text.toByteArray(StandardCharsets.UTF_8))

        fun sendFrame(opcode: Int, payload: ByteArray, masked: Boolean = true, fin: Boolean = true) {
            output.write((if (fin) 0x80 else 0) or opcode)
            when {
                payload.size < 126 -> output.write((if (masked) 0x80 else 0) or payload.size)
                payload.size <= 0xffff -> {
                    output.write((if (masked) 0x80 else 0) or 126)
                    output.write(payload.size ushr 8)
                    output.write(payload.size)
                }
                else -> {
                    output.write((if (masked) 0x80 else 0) or 127)
                    repeat(4) { output.write(0) }
                    output.write(payload.size ushr 24)
                    output.write(payload.size ushr 16)
                    output.write(payload.size ushr 8)
                    output.write(payload.size)
                }
            }
            val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
            if (masked) output.write(mask)
            val encoded = if (masked) ByteArray(payload.size) { payload[it].toInt().xor(mask[it % 4].toInt()).toByte() } else payload
            output.write(encoded)
            output.flush()
        }

        fun sendDeclaredLength(opcode: Int, length: Long) {
            output.write(0x80 or opcode)
            output.write(0x80 or 127)
            for (shift in 56 downTo 0 step 8) output.write((length ushr shift).toInt())
            output.write(byteArrayOf(1, 2, 3, 4))
            output.flush()
        }

        fun readCloseCode(): Int {
            val frame = readFrame()
            assertEquals(0x8, frame.opcode)
            return ((frame.payload[0].toInt() and 0xff) shl 8) or (frame.payload[1].toInt() and 0xff)
        }

        fun readByte(): Int = input.read()

        fun readFrame(): WsFrame {
            val first = input.read()
            val second = input.read()
            check(first >= 0 && second >= 0)
            val length = when (second and 0x7f) {
                126 -> (input.read() shl 8) or input.read()
                127 -> {
                    var value = 0L
                    repeat(8) { value = (value shl 8) or input.read().toLong() }
                    value.toInt()
                }
                else -> second and 0x7f
            }
            return WsFrame(first and 0x0f, input.readExactly(length))
        }

        override fun close() = socket.close()
    }

    companion object {
        private const val TEST_WS_KEY = "dGhlIHNhbXBsZSBub25jZQ=="

        private fun validWebSocketHeaders(): List<String> = listOf(
            "Host: localhost",
            "Upgrade: websocket",
            "Connection: keep-alive, Upgrade",
            "Sec-WebSocket-Version: 13",
            "Sec-WebSocket-Key: $TEST_WS_KEY",
        )

        private fun discoverJson(): JSONObject =
            DiscoverResponse("Phone", "Pixel", 1, "192.168.1.9", 8753, listOf("input", "screen")).toJson()

        private fun webSocketAccept(key: String): String = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
        )

        private fun readAsciiLine(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                check(value >= 0)
                if (value == '\n'.code) return bytes.toString(StandardCharsets.US_ASCII.name()).removeSuffix("\r")
                bytes.write(value)
            }
        }

        private fun InputStream.readExactly(length: Int): ByteArray {
            val result = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = read(result, offset, length - offset)
                check(count >= 0)
                offset += count
            }
            return result
        }
    }

    private data class WsFrame(val opcode: Int, val payload: ByteArray)
}
