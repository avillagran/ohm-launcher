package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class FluxRemoteInputTest {
    private val requestA = "123e4567-e89b-42d3-a456-426614174000"
    private val requestB = "123e4567-e89b-42d3-a456-426614174001"
    @Test fun stalePeerLossCannotCancelNewPeerApproval() {
        val old = Any()
        val replacement = Any()
        assertTrue(FluxInputApprovalState.lost(old, old))
        assertFalse(FluxInputApprovalState.lost(replacement, old))
        assertFalse(FluxInputApprovalState.lost(null, old))
    }
    @Test fun pointerMotionUsesOneKdeConnectActionWithBoundedDeltas() {
        val frame = FluxRemoteInputProtocol.packet(FluxRemoteInputProtocol.move(4.5, -2.0), 123L)
        val packet = JSONObject(frame.trimEnd())
        assertTrue(frame.endsWith("\n"))
        assertEquals("kdeconnect.mousepad.request", packet.getString("type"))
        assertEquals(123L, packet.getLong("id"))
        assertEquals(4.5, packet.getJSONObject("body").getDouble("dx"), 0.0)
        assertEquals(-2.0, packet.getJSONObject("body").getDouble("dy"), 0.0)
        assertEquals(2, packet.getJSONObject("body").length())
        assertThrows(IllegalArgumentException::class.java) { FluxRemoteInputProtocol.move(2001.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { FluxRemoteInputProtocol.move(Double.NaN, 1.0) }
    }

    @Test fun eachActionHasExactlyTheDocumentedWireFields() {
        fun body(action: FluxRemoteInputProtocol.Action) =
            JSONObject(FluxRemoteInputProtocol.packet(action, 1).trim()).getJSONObject("body")
        assertEquals(setOf("dx", "dy", "scroll"), body(FluxRemoteInputProtocol.scroll(1.0, -3.0)).keys().asSequence().toSet())
        assertTrue(body(FluxRemoteInputProtocol.scroll(1.0, -3.0)).getBoolean("scroll"))
        for (button in FluxRemoteInputProtocol.Click.entries) {
            val click = body(FluxRemoteInputProtocol.click(button))
            assertEquals(1, click.length())
            assertTrue(click.getBoolean(button.field))
        }
        assertTrue(body(FluxRemoteInputProtocol.hold()).getBoolean("singlehold"))
        assertTrue(body(FluxRemoteInputProtocol.release()).getBoolean("singlerelease"))
        val mods = FluxRemoteInputProtocol.Modifiers(ctrl = true, alt = true, shift = true, superKey = true)
        val key = body(FluxRemoteInputProtocol.special(FluxRemoteInputProtocol.SpecialKey.ENTER, mods))
        assertEquals(12, key.getInt("specialKey"))
        assertEquals(setOf("specialKey", "ctrl", "alt", "shift", "super"), key.keys().asSequence().toSet())
        assertEquals(32, body(FluxRemoteInputProtocol.special(FluxRemoteInputProtocol.SpecialKey.F12)).getInt("specialKey"))
        assertEquals("a", body(FluxRemoteInputProtocol.text("a", mods)).getString("key"))
        assertThrows(IllegalArgumentException::class.java) { FluxRemoteInputProtocol.scroll(0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { FluxRemoteInputProtocol.move(Double.POSITIVE_INFINITY, 2.0) }
    }

    @Test fun textAllowsUnicodeButRejectsControlsOversizeAndInvalidSurrogates() {
        fun body(value: String) = JSONObject(FluxRemoteInputProtocol.packet(FluxRemoteInputProtocol.text(value), 1).trim()).getJSONObject("body")
        assertEquals("🙂你好", body("🙂你好").getString("key"))
        for (value in listOf("", "x\n", "x\u0000", "\ufffd", "\ud800", "x".repeat(4097))) {
            assertThrows(IllegalArgumentException::class.java) { FluxRemoteInputProtocol.text(value) }
        }
        assertThrows(IllegalArgumentException::class.java) { FluxRemoteInputProtocol.packet(FluxRemoteInputProtocol.text("界".repeat(3000)), 1) }
    }

    @Test fun statusAcceptsOnlyAuthenticatedBooleanShape() {
        assertTrue(FluxRemoteInputProtocol.enabled("flux.input", JSONObject().put("enabled", true).put("requestId", requestA), requestA))
        assertFalse(FluxRemoteInputProtocol.enabled("flux.input", JSONObject().put("enabled", false).put("requestId", requestA), requestA))
        for (body in listOf(JSONObject(), JSONObject().put("enabled", "true"),
            JSONObject().put("enabled", true).put("other", true))) {
            assertThrows(IllegalArgumentException::class.java) { FluxRemoteInputProtocol.enabled("flux.input", body, requestA) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            FluxRemoteInputProtocol.enabled("kdeconnect.mousepad.request", JSONObject().put("enabled", true), requestA)
        }
    }

    private inner class Fixture {
        val peers = ConcurrentHashMap<String, Any>()
        val session = Any().also { peers["desktop"] = it }
        val serializer = Any()
        var now = 10L
        var paired = true
        var accepts = true
        var direct = true
        val aborted = AtomicInteger()
        val gate = FluxRemoteInputGate(peers, "desktop", session, serializer,
            pairedOpen = { paired }, peerAcceptsRequest = { accepts }, directEdition = { direct },
            elapsedMs = { now }, abortWrite = { aborted.incrementAndGet() })
        init { gate.beginRequest(requestA) }
        fun status(enabled: Boolean, id: String = requestA) =
            gate.receiveStatus("flux.input", JSONObject().put("enabled", enabled).put("requestId", id))
        fun send(token: Long): String {
            var frame = ""
            gate.send(token, FluxRemoteInputProtocol.click(FluxRemoteInputProtocol.Click.LEFT)) { frame = it }
            return frame
        }
    }

    @Test fun canceledAResponseCannotApproveBOrDisableItsTouchpad() {
        val f = Fixture()
        f.gate.cancelRequest(requestA)
        f.gate.beginRequest(requestB)
        assertEquals(null, f.status(true, requestA))
        assertThrows(IllegalStateException::class.java) { f.gate.credentialVerified() }
        assertEquals(true, f.status(true, requestB))
        val token = f.gate.credentialVerified()
        assertEquals(null, f.status(false, requestA))
        assertTrue(f.send(token).isNotEmpty())
        f.gate.cancelRequest(requestA)
        assertTrue(f.send(token).isNotEmpty())
        assertEquals(false, f.status(false, requestB))
        assertThrows(IllegalStateException::class.java) { f.send(token) }
    }
    @Test fun missingMalformedAndUnsolicitedStatusNeverGrantsOrClosesNewRequest() {
        val f = Fixture()
        f.gate.cancelRequest(requestA)
        assertEquals(null, f.status(true))
        f.gate.beginRequest(requestB)
        assertEquals(null, f.gate.receiveStatus("flux.input", JSONObject().put("enabled", true)))
        assertThrows(IllegalStateException::class.java) { f.gate.credentialVerified() }
        f.status(true, requestB)
        val token = f.gate.credentialVerified()
        assertEquals(null, f.gate.receiveStatus("flux.input", JSONObject().put("enabled", false)))
        assertTrue(f.send(token).isNotEmpty())
        assertEquals(null, f.gate.receiveStatus("flux.input", JSONObject().put("enabled", false).put("requestId", requestA).put("extra", 1)))
        assertTrue(f.send(token).isNotEmpty())
        assertEquals(false, f.gate.receiveStatus("flux.input", JSONObject().put("enabled", true).put("requestId", requestB).put("extra", 1)))
        assertThrows(IllegalStateException::class.java) { f.send(token) }
    }
    @Test fun blockedSocketWriteCannotDelayWithdrawalOrLetLateAEnableB() {
        val f = Fixture()
        f.status(true)
        val tokenA = f.gate.credentialVerified()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            runCatching { f.gate.send(tokenA, FluxRemoteInputProtocol.hold()) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            } }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val withdrawn = CountDownLatch(1)
            val cancel = Thread { f.gate.cancelRequest(requestA); withdrawn.countDown() }
            cancel.start()
            assertTrue("withdrawal waited for socket writer", withdrawn.await(2, TimeUnit.SECONDS))
            f.gate.beginRequest(requestB)
            assertEquals(null, f.status(true, requestA))
            assertThrows(IllegalStateException::class.java) { f.gate.credentialVerified() }
            f.status(true, requestB)
            val tokenB = f.gate.credentialVerified()
            assertEquals(null, f.status(false, requestA))
            cancel.join(2000)
            release.countDown()
            worker.join(2000)
            assertTrue(f.send(tokenB).isNotEmpty())
        } finally { release.countDown(); worker.join(2000) }
    }
    @Test fun gateRequiresPeerStatusCapabilityPairingAndFreshDeviceCredential() {
        val f = Fixture()
        assertThrows(IllegalStateException::class.java) { f.gate.credentialVerified() }
        f.status(true)
        val token = f.gate.credentialVerified()
        assertEquals("kdeconnect.mousepad.request", JSONObject(f.send(token).trim()).getString("type"))
        f.now += 299_999
        assertTrue(f.send(token).isNotEmpty())
        f.now++
        assertThrows(IllegalStateException::class.java) { f.send(token) }
        f.now++
        val renewed = f.gate.credentialVerified()
        assertThrows(IllegalStateException::class.java) { f.send(token) }
        assertTrue(f.send(renewed).isNotEmpty())
        f.status(false)
        assertThrows(IllegalStateException::class.java) { f.send(renewed) }
        assertThrows(IllegalStateException::class.java) { f.gate.credentialVerified() }
        f.status(true)
        f.gate.closeView()
        assertThrows(IllegalStateException::class.java) { f.send(renewed) }
        val another = f.gate.credentialVerified()
        f.accepts = false
        assertThrows(IllegalStateException::class.java) { f.send(another) }
        f.accepts = true
        f.paired = false
        assertThrows(IllegalStateException::class.java) { f.send(another) }
        f.paired = true
        f.direct = false
        assertThrows(IllegalStateException::class.java) { f.send(another) }
        f.direct = true
        f.peers["desktop"] = Any()
        assertThrows(IllegalStateException::class.java) { f.send(another) }
    }

    @Test fun desktopDisableRevokesCredentialAndAbortsBlockedWrite() {
        val f = Fixture()
        f.status(true)
        val old = f.gate.credentialVerified()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            f.gate.send(old, FluxRemoteInputProtocol.hold()) {
                entered.countDown(); release.await(5, TimeUnit.SECONDS)
            }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val disabled = CountDownLatch(1)
            val thread = Thread { f.status(false); disabled.countDown() }
            thread.start()
            assertTrue("status revocation blocked on socket write", disabled.await(2, TimeUnit.SECONDS))
            assertEquals(1, f.aborted.get())
            f.status(true)
            assertThrows(IllegalStateException::class.java) { f.send(old) }
            thread.join(2000)
        } finally { release.countDown(); worker.join(2000) }
        assertTrue(f.send(f.gate.credentialVerified()).isNotEmpty())
    }

    @Test fun malformedDesktopStatusCannotKeepAnOldEnableGrant() {
        val f = Fixture()
        f.status(true)
        val token = f.gate.credentialVerified()
        assertEquals(false, f.gate.receiveStatus("flux.input", JSONObject().put("enabled", "true").put("requestId", requestA)))
        assertThrows(IllegalStateException::class.java) { f.send(token) }
    }

    @Test fun queuedWriteCannotReviveAfterViewCloseAndReopen() {
        val f = Fixture()
        f.status(true)
        val token = f.gate.credentialVerified()
        val entered = CountDownLatch(1)
        val done = CountDownLatch(1)
        val writes = AtomicInteger()
        val error = AtomicReference<Throwable?>()
        val worker = Thread {
            entered.countDown()
            try { f.gate.send(token, FluxRemoteInputProtocol.hold()) { writes.incrementAndGet() } }
            catch (t: Throwable) { error.set(t) }
            finally { done.countDown() }
        }
        synchronized(f.serializer) {
            worker.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.gate.closeView()
            f.gate.credentialVerified()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(error.get() is IllegalStateException)
        assertEquals(0, writes.get())
        worker.join(2000)
    }

    @Test fun blockedWriteDoesNotDelayViewRevocation() {
        val f = Fixture()
        f.status(true)
        val token = f.gate.credentialVerified()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            f.gate.send(token, FluxRemoteInputProtocol.hold()) {
                entered.countDown(); release.await(5, TimeUnit.SECONDS)
            }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val done = CountDownLatch(1)
            val closer = Thread { f.gate.closeView(); done.countDown() }
            closer.start()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(1, f.aborted.get())
            closer.join(2000)
        } finally { release.countDown(); worker.join(2000) }
    }
}
