package cl.villagranquiroz.ohm_launcher

import android.content.Intent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FluxPeerBridgeTest {
    @Test fun capabilityMatrixNeverClaimsFluxStyleOrPrivilegedActions() {
        for (playStore in listOf(true, false)) {
            assertEquals(!playStore, PeerActionPolicy.supports(PeerTransport.FLUX, PeerAction.SEND_TEXT, playStore))
            assertEquals(!playStore, PeerActionPolicy.supports(PeerTransport.OMARCHY_LINK, PeerAction.SEND_TEXT, playStore))
            for (action in listOf(PeerAction.PING, PeerAction.SEND_CLIPBOARD, PeerAction.SHARE_URL, PeerAction.REPORT_BATTERY, PeerAction.SEND_NOTIFICATION)) {
                assertEquals(!playStore, PeerActionPolicy.supports(PeerTransport.FLUX, action, playStore))
                assertFalse(PeerActionPolicy.supports(PeerTransport.OMARCHY_LINK, action, playStore))
            }
            for (action in PeerAction.entries - setOf(PeerAction.SEND_TEXT, PeerAction.PING,
                PeerAction.SEND_CLIPBOARD, PeerAction.SHARE_URL, PeerAction.REPORT_BATTERY, PeerAction.SEND_NOTIFICATION,
                PeerAction.RECEIVE_THEME_CATALOG)) {
                assertFalse(PeerActionPolicy.supports(PeerTransport.FLUX, action, playStore))
            }
            assertEquals(!playStore, PeerActionPolicy.supports(PeerTransport.FLUX, PeerAction.RECEIVE_THEME_CATALOG, playStore))
            assertFalse(PeerActionPolicy.supports(PeerTransport.OMARCHY_LINK, PeerAction.RECEIVE_THEME_CATALOG, playStore))
            for (action in listOf(PeerAction.THEME_SYNC, PeerAction.THEME_SELECT, PeerAction.BACKGROUND_SELECT)) {
                assertTrue(PeerActionPolicy.supports(PeerTransport.OMARCHY_LINK, action, playStore))
            }
            assertFalse(PeerActionPolicy.supports(PeerTransport.OMARCHY_LINK, PeerAction.SCREEN_SHARE, true))
        }
    }

    @Test fun fluxIdentityAndPairingPacketsAreProtocolEight() {
        val id = "92baf92fcbb34073aa190966c9de14e6"
        val outgoing = JSONObject(FluxWire.identity(id, "Ohm", id)).getJSONObject("body")
        assertEquals(8, outgoing.getInt("protocolVersion"))
        assertEquals(id, outgoing.getString("targetDeviceId"))
        assertEquals(8, outgoing.getInt("targetProtocolVersion"))
        assertEquals(FluxWire.SHARE, outgoing.getJSONArray("outgoingCapabilities").getString(0))
        assertEquals(FluxWire.MPRIS, outgoing.getJSONArray("incomingCapabilities").getString(0))
        val desktop = JSONObject(FluxWire.identity(id, "node"))
        desktop.getJSONObject("body").put("incomingCapabilities", org.json.JSONArray().put(FluxWire.SHARE))
        assertEquals("node", FluxWire.parseIdentity(desktop.toString(), id))
        desktop.getJSONObject("body").put("deviceId", "another123456789012345678901234567")
        assertThrows(IllegalArgumentException::class.java) { FluxWire.parseIdentity(desktop.toString(), id) }
        assertEquals(false, FluxWire.pairReply(FluxWire.packet(FluxWire.PAIR, JSONObject().put("pair", false))))
        assertEquals(true, FluxWire.pairReply(FluxWire.packet(FluxWire.PAIR, JSONObject().put("pair", true))))
    }

    @Test fun fluxVerificationKeyIsSymmetricAndTimestampBound() {
        val a = byteArrayOf(0, 1, 3)
        val b = byteArrayOf(0, 1, 2)
        val key = FluxWire.verificationKey(a, b, 1727260000)
        assertEquals(8, key.length)
        assertEquals(key, FluxWire.verificationKey(b, a, 1727260000))
        assertFalse(key == FluxWire.verificationKey(a, b, 1727260001))
    }

    @Test fun fluxLineParserBoundsInput() {
        assertEquals("hello", FluxWire.readLine("hello\nTLS".byteInputStream()))
        assertThrows(IllegalStateException::class.java) { FluxWire.readLine("abcdef\n".byteInputStream(), 4) }
        assertFalse(FluxWire.validId("short"))
    }

    @Test fun omarchyTextUsesAuthenticatedPutAndRequiresAcknowledgment() {
        val body = arrayOfNulls<String>(1)
        val success = withServer("{\"ok\":true}", body) { peer ->
            OmarchyPeerClient().sendText(peer, "hello \"desk\"")
        }
        assertTrue(success)
        assertTrue(body[0]!!.startsWith("PUT /omarchy/clipboard HTTP/1.1"))
        assertEquals("hello \"desk\"", JSONObject(body[0]!!.substringAfter("\n\n")).getString("text"))
        assertFalse(withServer("{\"ok\":false}", arrayOfNulls(1)) { OmarchyPeerClient().sendText(it, "test") })
        assertFalse(OmarchyPeerClient().sendText(OmarchyPeer("127.0.0.1", 1, "lab"), "test"))
    }

    private fun <T> withServer(response: String, body: Array<String?>, call: (OmarchyPeer) -> T): T {
        ServerSocket(0).use { server ->
            val worker = Thread {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    val request = reader.readLine()
                    var length = 0
                    while (true) {
                        val line = reader.readLine()
                        if (line.isNullOrEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val chars = CharArray(length)
                    var offset = 0
                    while (offset < chars.size) {
                        val count = reader.read(chars, offset, chars.size - offset)
                        if (count < 0) break
                        offset += count
                    }
                    body[0] = "$request\n\n${String(chars)}"
                    val data = response.toByteArray()
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray() + data)
                }
            }.apply { start() }
            val result = call(OmarchyPeer("127.0.0.1", server.localPort, "lab", "secret"))
            worker.join(3000)
            return result
        }
    }
}
