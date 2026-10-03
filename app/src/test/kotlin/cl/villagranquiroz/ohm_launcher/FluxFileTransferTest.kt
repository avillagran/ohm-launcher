package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class FluxFileTransferTest {
    @Test fun shareFrameMatchesFluxReceiver() {
        val frame = JSONObject(FluxWire.payloadPacket(FluxWire.SHARE,
            FluxFileTransfer.body("report.pdf", 23), 23, 1740))
        assertEquals(FluxWire.SHARE, frame.getString("type"))
        assertEquals("report.pdf", frame.getJSONObject("body").getString("filename"))
        assertFalse(frame.getJSONObject("body").getBoolean("open"))
        assertEquals(1, frame.getJSONObject("body").getInt("numberOfFiles"))
        assertEquals(23, frame.getJSONObject("body").getLong("totalPayloadSize"))
        assertEquals(23, frame.getLong("payloadSize"))
        assertEquals(1740, frame.getJSONObject("payloadTransferInfo").getInt("port"))
        assertFalse(frame.getJSONObject("payloadTransferInfo").has("tunnel"))
    }

    @Test fun rejectsUnsafeNamesAndSizesBeforeAnnouncing() {
        for (name in listOf("", ".", "..", "../a", "a/b", "a\\b", "a\u0000b", "a\nb", "x".repeat(256))) {
            assertThrows(IllegalArgumentException::class.java) { FluxFileTransfer.body(name, 1) }
        }
        for (size in listOf(-1L, 0L, FluxFileTransfer.MAX_BYTES + 1)) {
            assertThrows(IllegalArgumentException::class.java) { FluxFileTransfer.body("ok.txt", size) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            FluxWire.payloadPacket(FluxWire.SHARE, JSONObject(), 1, 1738)
        }
    }

    @Test fun blockedProviderWorkTimesOutWithoutOccupyingCallerAndCannotResume() {
        val gate = FluxTransferGate { true }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Result<Unit>>()
        FluxFileTransfer.submit(gate, 100, {
            entered.countDown()
            release.await()
            gate.checkActive() // A late provider response must not announce anything.
        }) { outcome -> result.set(outcome); finished.countDown() }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(finished.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(result.get().isFailure)
        release.countDown()
        assertThrows(IllegalStateException::class.java) { gate.checkActive() }
    }

    @Test fun exactCopyRejectsShortAndLongSources() {
        val out = ByteArrayOutputStream()
        FluxFileTransfer.copyExact(ByteArrayInputStream(byteArrayOf(1, 2)), out, 2)
        assertEquals(listOf<Byte>(1, 2), out.toByteArray().toList())
        assertThrows(EOFException::class.java) {
            FluxFileTransfer.copyExact(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FluxFileTransfer.copyExact(ByteArrayInputStream(byteArrayOf(1, 2, 3)), ByteArrayOutputStream(), 2)
        }
        assertTrue(FluxFileTransfer.body("x", 1).getBoolean("open") == false)
    }
}
