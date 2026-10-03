package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException

class FluxPairReplyDeadlineTest {
    @Test fun unrelatedPacketsDoNotExtendPairingWindow() {
        val ignored = FluxWire.packet(FluxWire.PING, JSONObject())
        val accepted = FluxWire.packet(FluxWire.PAIR, JSONObject().put("pair", true))
        val source = ByteArrayInputStream((ignored + ignored + accepted).toByteArray())
        var now = 0L
        val timeouts = mutableListOf<Int>()
        val input = object : InputStream() {
            override fun read(): Int = source.read().also { if (it == 10) now += 15_000 }
        }
        assertThrows(SocketTimeoutException::class.java) {
            FluxPairReplyDeadline.await(input, timeouts::add, { now })
        }
        assertTrue(timeouts.contains(30_000))
        assertTrue(timeouts.contains(15_000))
        assertEquals("late acceptance remains unread", accepted, source.readBytes().toString(Charsets.UTF_8))
    }

    @Test fun slowPartialFrameCannotResetPerByteReadTimeout() {
        var now = 0L
        var bytes = 0
        val input = object : InputStream() {
            override fun read(): Int { bytes++; now += 1000; return '{'.code }
        }
        assertThrows(SocketTimeoutException::class.java) {
            FluxPairReplyDeadline.await(input, {}, { now })
        }
        assertEquals(30, bytes)
    }

    @Test fun timelyPairAcceptanceAndRejectionArePreserved() {
        for (accepted in listOf(true, false)) {
            val input = FluxWire.packet(FluxWire.PAIR, JSONObject().put("pair", accepted)).byteInputStream()
            assertEquals(accepted, FluxPairReplyDeadline.await(input, {}, { 1234L }))
        }
    }
    @Test fun nativeReplyRequiresNativeBooleanAndCannotFallBackToLegacyReply() {
        val frames = FluxWire.packet(FluxWire.PAIR, JSONObject().put("pair", true)) +
            FluxWire.packet(FluxWire.PAIR, JSONObject().put("pair", "true"), FluxDialect.FLUX) +
            FluxWire.packet(FluxWire.PAIR, JSONObject().put("pair", false), FluxDialect.FLUX)
        assertFalse(FluxPairReplyDeadline.await(frames.byteInputStream(), {}, { 0L }, dialect = FluxDialect.FLUX))
        val legacy = FluxWire.packet(FluxWire.PAIR, JSONObject().put("pair", true))
        var now = 0L
        val source = legacy.byteInputStream()
        val input = object : InputStream() {
            override fun read(): Int = source.read().also { if (it == 10) now = 30_000L }
        }
        assertThrows(SocketTimeoutException::class.java) {
            FluxPairReplyDeadline.await(input, {}, { now }, dialect = FluxDialect.FLUX)
        }
    }

}
