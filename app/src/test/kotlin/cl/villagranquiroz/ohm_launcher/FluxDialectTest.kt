package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FluxDialectTest {
    private val id = "1234567890abcdef1234567890abcdef"
    private val own = "abcdef1234567890abcdef1234567890"

    @Test fun authenticatedIdentityAndCapabilitiesUseOneDialectInBothEditions() {
        for (dialect in FluxDialect.entries) for (play in listOf(true, false)) {
            val packet = JSONObject(FluxWire.identity(id, "Desktop", playStore = play, dialect = dialect))
            assertEquals(dialect.identityType, packet.getString("type"))
            val body = packet.getJSONObject("body")
            assertEquals(8, body.getInt("protocolVersion"))
            val outgoing = strings(body.getJSONArray("outgoingCapabilities"))
            val incoming = strings(body.getJSONArray("incomingCapabilities"))
            assertTrue(FluxWire.type(FluxWire.SHARE, dialect) in outgoing)
            assertEquals(!play, FluxWallpaper.TYPE in incoming)
            assertEquals(!play, FluxWire.OMARCHY_THEME in incoming)
            assertEquals(!play, FluxWire.OMARCHY_THEME_SELECT in outgoing)
            assertEquals(!play, FluxWire.SCREEN in outgoing)
            if (dialect == FluxDialect.FLUX) assertFalse((incoming + outgoing).any { it.startsWith("kdeconnect.") })
            else assertTrue(FluxWire.SHARE in outgoing)
        }
    }

    @Test fun secureIdentityRejectsNamespaceIdVersionAndCapabilityDowngrades() {
        val good = FluxWire.identity(id, "Desktop", playStore = false, dialect = FluxDialect.FLUX)
        assertEquals("Desktop", FluxWire.parseIdentity(good, id, FluxDialect.FLUX))
        assertThrows(IllegalArgumentException::class.java) { FluxWire.parseIdentity(good, id, FluxDialect.LEGACY_KDE) }
        assertThrows(IllegalArgumentException::class.java) { FluxWire.parseIdentity(good, own, FluxDialect.FLUX) }
        for (badVersion in listOf<Any>(7, "8", 8.5)) {
            val message = JSONObject(good)
            message.getJSONObject("body").put("protocolVersion", badVersion)
            assertThrows(IllegalArgumentException::class.java) { FluxWire.parseIdentity(message.toString(), id, FluxDialect.FLUX) }
        }
        val mixed = JSONObject(good)
        mixed.getJSONObject("body").getJSONArray("outgoingCapabilities").put(FluxWire.MPRIS)
        assertThrows(IllegalArgumentException::class.java) { FluxWire.parseIdentity(mixed.toString(), id, FluxDialect.FLUX) }
        val legacy = JSONObject(FluxWire.identity(id, "Desktop", playStore = false))
        legacy.getJSONObject("body").getJSONArray("incomingCapabilities").put("flux.share.request")
        assertThrows(IllegalArgumentException::class.java) { FluxWire.parseIdentity(legacy.toString(), id) }
    }

    @Test fun plaintextTargetsAreCheckedBeforeChoosingTlsDialect() {
        for (dialect in FluxDialect.entries) {
            val frame = FluxWire.identity(id, "Desktop", own, dialect = dialect)
            assertEquals(FluxWire.PlainIdentity(id, dialect), FluxWire.plainIdentity(frame, own))
            assertThrows(IllegalArgumentException::class.java) { FluxWire.plainIdentity(frame, id) }
            val wrongTarget = JSONObject(frame)
            wrongTarget.getJSONObject("body").put("targetDeviceId", "unexpected")
            assertThrows(IllegalArgumentException::class.java) { FluxWire.plainIdentity(wrongTarget.toString(), own) }
            val stringVersion = JSONObject(frame)
            stringVersion.getJSONObject("body").put("targetProtocolVersion", "8")
            assertEquals(dialect, FluxWire.plainIdentity(stringVersion.toString(), own).dialect)
            stringVersion.getJSONObject("body").put("targetProtocolVersion", "7")
            assertThrows(IllegalArgumentException::class.java) { FluxWire.plainIdentity(stringVersion.toString(), own) }
        }
    }

    @Test fun nativePacketTranslationPreservesPayloadMetadataAndCustomExtensions() {
        val legacy = FluxWire.payloadPacket(FluxWire.SHARE, JSONObject().put("filename", "fixture.txt"), 3, 1739)
        val native = FluxWire.outgoing(legacy, FluxDialect.FLUX)
        val packet = JSONObject(native)
        assertEquals("flux.share.request", packet.getString("type"))
        assertEquals(3, packet.getInt("payloadSize"))
        assertEquals(1739, packet.getJSONObject("payloadTransferInfo").getInt("port"))
        val normalized = FluxWire.incoming(native, FluxDialect.FLUX)!!
        assertEquals(FluxWire.SHARE, normalized.getString("type"))
        assertEquals(JSONObject(legacy).getJSONObject("body").toString(), normalized.getJSONObject("body").toString())
        assertNull(FluxWire.incoming(legacy, FluxDialect.FLUX))
        assertNull(FluxWire.incoming(native, FluxDialect.LEGACY_KDE))
        for (type in listOf(FluxWallpaper.TYPE, FluxWire.INPUT_APPROVAL, FluxWire.OMARCHY_THEME_SELECT, FluxWire.SCREEN)) {
            val frame = FluxWire.packet(type, JSONObject().put("fixture", true))
            assertEquals(type, JSONObject(FluxWire.outgoing(frame, FluxDialect.FLUX)).getString("type"))
            assertEquals(type, FluxWire.incoming(frame, FluxDialect.FLUX)!!.getString("type"))
        }
    }

    @Test fun renamedMediaAndInputCapabilitiesAreNegotiatedFromSecureIdentity() {
        val packet = JSONObject(FluxWire.identity(id, "Desktop", playStore = false, dialect = FluxDialect.FLUX))
        packet.getJSONObject("body").getJSONArray("incomingCapabilities")
            .put("flux.mpris.request").put("flux.mousepad.request").put(FluxWire.INPUT_APPROVAL)
        assertTrue(FluxWire.supportsMedia(packet.toString()))
        assertTrue(FluxWire.supportsRemoteInput(packet.toString()))
        assertTrue(FluxWire.supportsInputApproval(packet.toString()))
        assertTrue(FluxWire.supportsWallpaper(packet.toString()))
    }

    @Test fun pairingTimestampRejectsOverflowAndOutdatedRequests() {
        val now = 1_800_000_000L
        for (valid in listOf(now - 1800, now, now + 1800)) assertTrue(FluxWire.pairTimestamp(valid, now))
        for (invalid in listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, now - 1801, now + 1801))
            assertFalse(FluxWire.pairTimestamp(invalid, now))
    }

    @Test fun certificateCommonNameAndExactPinAreBothRequired() {
        val original = FluxIdentity.createMaterial(id).certificate
        val replacement = FluxIdentity.createMaterial(id).certificate
        FluxWire.verifyCertificate(id, original, null)
        FluxWire.verifyCertificate(id, original, original.encoded)
        assertThrows(IllegalArgumentException::class.java) { FluxWire.verifyCertificate(own, original, null) }
        assertThrows(IllegalArgumentException::class.java) { FluxWire.verifyCertificate(id, replacement, original.encoded) }
    }

    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }
}
