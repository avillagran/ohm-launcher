package cl.villagranquiroz.ohm_launcher

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmarchyPeerClientTest {
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
}
