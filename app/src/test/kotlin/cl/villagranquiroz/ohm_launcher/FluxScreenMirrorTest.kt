package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket

class FluxScreenMirrorTest {
    @Test fun replacedControlSessionCannotPassAnyMirrorBoundary() {
        val original = Any()
        var mapped: Any? = original
        var pairedOpen = true
        val authority = FluxScreenAuthorization(original, { mapped }, { pairedOpen })
        assertTrue(authority.active())
        for (boundary in listOf("consent", "foreground", "listener", "tls", "encoder", "frame")) {
            mapped = Any()
            assertFalse("$boundary must reject a replaced session", authority.active())
            mapped = original
        }
        pairedOpen = false
        assertFalse(authority.active())
    }

    @Test fun socketCloseDoesNotHoldStopOrDelayIndependentClose() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val otherClosed = CountDownLatch(1)
        val blocking = Closeable { entered.countDown(); unblock.await() }
        try {
            val call = Executors.newSingleThreadExecutor()
            try {
                val returned = call.submit { FluxScreenSocketCloser.close(blocking, Closeable { otherClosed.countDown() }) }
                returned.get(2, TimeUnit.SECONDS)
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertTrue("second socket close must not wait for the first", otherClosed.await(2, TimeUnit.SECONDS))
            } finally { call.shutdownNow() }
        } finally { unblock.countDown() }
    }
    @Test fun stalledSocketClosesNeverConsumeUnboundedThreads() {
        val entered = AtomicInteger()
        val firstFour = CountDownLatch(4)
        val release = CountDownLatch(1)
        try {
            repeat(32) {
                FluxScreenSocketCloser.close(Closeable {
                    entered.incrementAndGet()
                    firstFour.countDown()
                    release.await()
                })
            }
            assertTrue("four independent socket closes must start", firstFour.await(2, TimeUnit.SECONDS))
            assertEquals("blocked close workers must have a fixed cap", 4, entered.get())
        } finally { release.countDown() }
    }

    @Test fun saturationRejectsWithoutBlockingAndCapacityRecovers() {
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val rejectedClosed = CountDownLatch(1)
        val resumedClosed = CountDownLatch(1)
        try {
            repeat(4) {
                assertTrue(FluxScreenSocketCloser.close(Closeable { entered.countDown(); release.await() }))
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse("capacity must reject rather than queue a socket behind blocked closes",
                FluxScreenSocketCloser.close(Closeable { rejectedClosed.countDown() }))
            assertEquals(1L, rejectedClosed.count)
        } finally { release.countDown() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline && !FluxScreenSocketCloser.close(Closeable { resumedClosed.countDown() })) {
            Thread.yield()
        }
        assertTrue("completed closes restore capacity", resumedClosed.await(2, TimeUnit.SECONDS))
    }

    @Test fun startPacketHasDesktopScreenContract() {
        val packet = JSONObject(FluxScreenProtocol.start(1740, 480, 1072))
        assertEquals("flux.screen", packet.getString("type"))
        with(packet.getJSONObject("body")) {
            assertEquals("start", getString("state"))
            assertEquals(1740, getInt("port"))
            assertEquals(480, getInt("width"))
            assertEquals(1072, getInt("height"))
            assertEquals("h264", getString("codec"))
        }
        assertEquals("stop", JSONObject(FluxScreenProtocol.stop()).getJSONObject("body").getString("state"))
    }

    @Test fun frameDimensionsAreBoundedAndEncoderAligned() {
        assertEquals(480 to 1072, FluxScreenProtocol.fit(1080, 2400))
        assertEquals(640 to 352, FluxScreenProtocol.fit(640, 360))
        assertEquals(16 to 16, FluxScreenProtocol.fit(1, 1))
    }

    @Test fun replyOnlyAcceptsKnownScreenStates() {
        assertEquals(FluxScreenProtocol.Reply.Live, FluxScreenProtocol.reply("flux.screen", JSONObject().put("state", "live")))
        assertEquals(FluxScreenProtocol.Reply.Stop, FluxScreenProtocol.reply("flux.screen", JSONObject().put("state", "stop")))
        assertEquals(FluxScreenProtocol.Reply.Error, FluxScreenProtocol.reply("flux.screen", JSONObject().put("state", "error")))
        assertNull(FluxScreenProtocol.reply("kdeconnect.ping", JSONObject().put("state", "stop")))
        assertNull(FluxScreenProtocol.reply("flux.screen", JSONObject().put("state", "start")))
    }

    @Test fun annexBOutputsParameterSetsBeforeFirstIdrAndDropsUnusableFrames() {
        val framer = FluxScreenProtocol.Framer()
        assertNull(framer.frame(byteArrayOf(0, 0, 1, 0x41), false))
        framer.config(byteArrayOf(0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 0, 0, 0, 1, 0x65, 3),
            framer.frame(byteArrayOf(0x65, 3), true))
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x41), framer.frame(byteArrayOf(0, 0, 1, 0x41), false))
    }

    @Test fun streamPortAndCertificateMustMatchPinnedLiveControlPeer() {
        assertFalse(FluxScreenProtocol.authorized(1738, true, true, true))
        assertFalse(FluxScreenProtocol.authorized(1740, false, true, true))
        assertFalse(FluxScreenProtocol.authorized(1740, true, false, true))
        assertFalse(FluxScreenProtocol.authorized(1740, true, true, false))
        assertTrue(FluxScreenProtocol.authorized(1740, true, true, true))
    }

    @Test fun streamCertificateMustEqualBothControlPeerAndStoredPin() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(1, 2, 4)
        assertTrue(FluxScreenProtocol.samePeer(a, a, a))
        assertFalse(FluxScreenProtocol.samePeer(a, a, b))
        assertFalse(FluxScreenProtocol.samePeer(a, b, a))
        assertFalse(FluxScreenProtocol.samePeer(a, null, a))
    }

    @Test fun avccLengthPrefixedFramesBecomeAnnexB() {
        val f = FluxScreenProtocol.Framer()
        f.config(byteArrayOf(0, 0, 0, 2, 0x67, 1, 0, 0, 0, 2, 0x68, 2))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2,
            0, 0, 0, 1, 0x65, 3), f.frame(byteArrayOf(0, 0, 0, 2, 0x65, 3), true))
    }

    @Test fun tlsStreamAuthenticatesSameCertificateAsPairedControlLink() {
        val phone = FluxIdentity.createMaterial("a".repeat(32))
        val desktop = FluxIdentity.createMaterial("b".repeat(32))
        val attacker = FluxIdentity.createMaterial("c".repeat(32))
        val pool = Executors.newSingleThreadExecutor()
        try {
            for ((client, accepted) in listOf(desktop to true, attacker to false)) {
                ServerSocket(0).use { listener ->
                    listener.soTimeout = 3000
                    val receiving = pool.submit<Boolean> {
                        listener.accept().use { raw ->
                            try {
                                FluxScreenTransport.accept(raw, FluxIdentity.createTlsContext(phone),
                                    desktop.certificate.encoded, desktop.certificate.encoded).use { secure ->
                                    secure.inputStream.read() == 42
                                }
                            } catch (_: SecurityException) { false }
                        }
                    }
                    Socket("127.0.0.1", listener.localPort).use { raw ->
                        val tls = FluxIdentity.createTlsContext(client).socketFactory.createSocket(raw,
                            "127.0.0.1", listener.localPort, false) as SSLSocket
                        tls.use { secure ->
                            secure.useClientMode = true
                            secure.enabledProtocols = arrayOf("TLSv1.2")
                            secure.startHandshake()
                            if (accepted) secure.outputStream.write(42)
                        }
                    }
                    assertEquals(accepted, receiving.get(5, TimeUnit.SECONDS))
                }
            }
        } finally { pool.shutdownNow() }
    }
}
