package cl.villagranquiroz.ohm_launcher

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmarchyPeerClientTest {
    @Test
    fun parsesCanonicalThemeCatalog() {
        val payload = """{"current":"nord","themes":[{"id":"nord","label":"Nord","preview":"/omarchy/themes/nord/preview"},{"id":"tokyo-night","label":"Tokyo Night","preview":"/omarchy/themes/tokyo-night/preview"}]}"""

        val catalog = withServer(payload) { OmarchyPeerClient().fetchThemes(it) }

        assertEquals("nord", catalog?.current)
        assertEquals(listOf("nord", "tokyo-night"), catalog?.themes?.map { it.id })
        assertEquals("Tokyo Night", catalog?.themes?.last()?.label)
        assertEquals(null, withServer("not-json") { OmarchyPeerClient().fetchThemes(it) })
    }

    @Test
    fun selectionPostsExactOpaqueThemeId() {
        val response = """{"name":"nord","mode":"dark","colors":{"accent":"#81a1c1"}}"""
        val received = arrayOfNulls<String>(1)
        val palette = withRecordingServer(response, received) {
            OmarchyPeerClient().selectTheme(it, "nord")
        }

        assertEquals("nord", palette?.name)
        val request = received[0].orEmpty()
        assertTrue(request.startsWith("POST /omarchy/themes/select HTTP/1.1"))
        assertEquals("nord", JSONObject(request.substringAfter("\n\n")).getString("id"))
    }

    @Test
    fun parsesBackgroundCatalogWithCurrentTypeAndPath() {
        val payload = """{"current":{"type":"image","path":"nord/lake.png"},"backgrounds":[{"id":"nord/lake.png","label":"Lake","type":"image","preview":"/ignored","hasPreview":true},{"id":"audio/bars","label":"Bars","type":"audio","preview":"","hasPreview":false}]}"""

        val catalog = withServer(payload) { OmarchyPeerClient().fetchBackgrounds(it) }

        assertEquals("image", catalog?.current?.type)
        assertEquals("nord/lake.png", catalog?.current?.path)
        assertEquals(listOf("nord/lake.png", "audio/bars"), catalog?.backgrounds?.map { it.id })
        assertTrue(catalog!!.backgrounds.first().hasPreview)
        assertFalse(catalog.backgrounds.last().hasPreview)
    }

    @Test
    fun backgroundPreviewUsesUrlEncodedId() {
        val received = arrayOfNulls<String>(1)
        val bytes = withRecordingBinaryServer("preview".toByteArray(), received) { peer ->
            OmarchyPeerClient().fetchBackgroundPreview(
                peer,
                OmarchyBackgroundChoice("folder/sky blue.png", "Sky", "image", "ignored", true),
            )
        }

        assertEquals("preview".toByteArray().toList(), bytes?.toList())
        assertTrue(received[0]!!.startsWith("GET /omarchy/backgrounds/folder%2Fsky%20blue.png/preview HTTP/1.1"))
    }

    @Test
    fun backgroundSelectionPostsIdAndRequiresSuccess() {
        val received = arrayOfNulls<String>(1)
        val selected = withRecordingServer("{\"ok\":true}", received) {
            OmarchyPeerClient().selectBackground(it, "audio/bars")
        }

        assertTrue(selected)
        val request = received[0].orEmpty()
        assertTrue(request.startsWith("POST /omarchy/backgrounds/select HTTP/1.1"))
        assertEquals("audio/bars", JSONObject(request.substringAfter("\n\n")).getString("id"))
        assertFalse(withServer("{\"ok\":false}") { OmarchyPeerClient().selectBackground(it, "audio/bars") })
    }

    @Test
    fun notifyUsesContentLengthAndSendsLauncherEndpoint() {
        val server = ServerSocket(0)
        val received = arrayOfNulls<String>(1)
        val done = CountDownLatch(1)
        Thread {
            server.use {
                val socket = it.accept()
                socket.use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    val request = reader.readLine()
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine()
                        if (line.isNullOrEmpty()) break
                        val separator = line.indexOf(':')
                        headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                    }
                    val body = CharArray(headers.getValue("content-length").toInt())
                    reader.read(body)
                    received[0] = "$request\n${headers["transfer-encoding"]}\n${String(body)}"
                    client.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray(),
                    )
                }
            }
            done.countDown()
        }.start()

        val peer = OmarchyPeer("127.0.0.1", server.localPort, "lab")
        val result = OmarchyPeerClient().notify(peer, "192.168.1.100", 8753, "Phone")

        assertTrue(result)
        assertTrue(done.await(2, TimeUnit.SECONDS))
        val parts = received[0]!!.split('\n')
        assertEquals("POST /omarchy/link HTTP/1.1", parts[0])
        assertEquals("null", parts[1])
        val json = JSONObject(parts[2])
        assertEquals("192.168.1.100", json.getString("ip"))
        assertEquals(8753, json.getInt("port"))
        assertEquals("Phone", json.getString("name"))
    }

    @Test
    fun probeReadsConnectedFlagAndRejectsMalformedResponse() {
        assertTrue(withServer("{\"connected\":true}") { OmarchyPeerClient().probe(it) })
        assertEquals(false, withServer("{\"connected\":false}") { OmarchyPeerClient().probeStatus(it) })
        assertEquals(null, withServer("not-json") { OmarchyPeerClient().probeStatus(it) })
        assertFalse(withServer("not-json") { OmarchyPeerClient().probe(it) })
    }

    @Test
    fun fetchesCanonicalThemeFromConnectedOmarchyPeer() {
        val payload = """{"name":"Nord","mode":"dark","colors":{"accent":"#81a1c1"}}"""

        val theme = withServer(payload) { OmarchyPeerClient().fetchTheme(it) }

        assertEquals("Nord", theme?.name)
        assertEquals("#81a1c1", theme?.color("accent"))
        assertEquals(null, withServer("not-json") { OmarchyPeerClient().fetchTheme(it) })
    }

    @Test
    fun downloadsAndVerifiesCanonicalThemeBackground() {
        val bytes = "canonical-background".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val directory = Files.createTempDirectory("omarchy-background-test-").toFile()
        try {
            val file = withBinaryServer(bytes) { peer ->
                OmarchyPeerClient().fetchThemeBackground(
                    peer,
                    OmarchyThemeBackground("lake.png", "image/png", sha),
                    directory,
                )
            }

            assertEquals(bytes.toList(), file?.readBytes()?.toList())
            assertTrue(file?.extension == "png")
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun <T> withServer(responseBody: String, call: (OmarchyPeer) -> T): T {
        val server = ServerSocket(0)
        Thread {
            server.use {
                it.accept().use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val bytes = responseBody.toByteArray()
                    client.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes,
                    )
                }
            }
        }.start()
        return call(OmarchyPeer("127.0.0.1", server.localPort, "lab"))
    }

    private fun <T> withBinaryServer(responseBody: ByteArray, call: (OmarchyPeer) -> T): T {
        val server = ServerSocket(0)
        Thread {
            server.use {
                it.accept().use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    client.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${responseBody.size}\r\nConnection: close\r\n\r\n".toByteArray() + responseBody,
                    )
                }
            }
        }.start()
        return call(OmarchyPeer("127.0.0.1", server.localPort, "lab"))
    }

    private fun <T> withRecordingServer(
        responseBody: String,
        received: Array<String?>,
        call: (OmarchyPeer) -> T,
    ): T {
        val server = ServerSocket(0)
        Thread {
            server.use {
                it.accept().use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    val requestLine = reader.readLine()
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine()
                        if (line.isNullOrEmpty()) break
                        val separator = line.indexOf(':')
                        headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                    }
                    val body = CharArray(headers["content-length"]?.toInt() ?: 0)
                    reader.read(body)
                    received[0] = "$requestLine\n\n${String(body)}"
                    val bytes = responseBody.toByteArray()
                    client.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes,
                    )
                }
            }
        }.start()
        return call(OmarchyPeer("127.0.0.1", server.localPort, "lab"))
    }

    private fun <T> withRecordingBinaryServer(
        responseBody: ByteArray,
        received: Array<String?>,
        call: (OmarchyPeer) -> T,
    ): T {
        val server = ServerSocket(0)
        Thread {
            server.use {
                it.accept().use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    received[0] = reader.readLine()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    client.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${responseBody.size}\r\nConnection: close\r\n\r\n".toByteArray() + responseBody,
                    )
                }
            }
        }.start()
        return call(OmarchyPeer("127.0.0.1", server.localPort, "lab"))
    }
}
