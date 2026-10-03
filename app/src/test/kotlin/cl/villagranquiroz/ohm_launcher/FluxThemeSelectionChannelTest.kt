package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FluxThemeSelectionChannelTest {
    @Test fun disabledSelectionAdvertisesNeitherDirectionInBothEditions() {
        val id = "1234567890abcdef1234567890abcdef"
        for (play in listOf(false, true)) {
            val body = JSONObject(FluxWire.identity(id, "Ohm", playStore = play)).getJSONObject("body")
            val outgoing = body.getJSONArray("outgoingCapabilities")
            val incoming = body.getJSONArray("incomingCapabilities")
            assertEquals(!play, (0 until outgoing.length()).any { outgoing.getString(it) == FluxThemeSelection.SELECT })
            assertEquals(!play, (0 until incoming.length()).any { incoming.getString(it) == FluxThemeSelection.SELECTED })
        }
    }

    @Test fun desktopMustAdvertiseBothDirections() {
        val id = "1234567890abcdef1234567890abcdef"
        val identity = JSONObject(FluxWire.identity(id, "desktop"))
        val body = identity.getJSONObject("body")
        val incoming = body.getJSONArray("incomingCapabilities")
        val outgoing = body.getJSONArray("outgoingCapabilities")
        assertFalse(FluxWire.supportsThemeSelection(identity.toString()))
        incoming.put(FluxThemeSelection.SELECT)
        assertFalse(FluxWire.supportsThemeSelection(identity.toString()))
        outgoing.put(FluxThemeSelection.SELECTED)
        assertTrue(FluxWire.supportsThemeSelection(identity.toString()))
        body.put("incomingCapabilities", org.json.JSONArray().put(FluxThemeSelection.SELECTED))
        assertFalse(FluxWire.supportsThemeSelection(identity.toString()))
        body.remove("outgoingCapabilities")
        assertFalse(FluxWire.supportsThemeSelection(identity.toString()))
    }

    @Test fun revokedBlockedWriterCannotDelayReplacementOrDeliverOldAck() {
        val a = Any(); val b = Any(); live[peer] = a; consent = true
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocked = FluxThemeSelectionChannel<Any>(true, { live[it] }, { paired }, { open },
            { capable }, { consent }, { installed }, {
                entered.countDown()
                check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
            }, { results.add(it) })
        blocked.bind(peer, a)
        val worker = Thread { blocked.request(peer, a, "Tokyo Night", idA, 1) }
        worker.start()
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
        live[peer] = b
        blocked.revoke(peer, a)
        blocked.bind(peer, b)
        assertFalse(blocked.receive(peer, a, ack(idA, true, "Tokyo Night")))
        release.countDown(); worker.join(3000)
        assertFalse(worker.isAlive)
        assertTrue(results.isEmpty())
    }

    private val peer = "peer"
    private val idA = "123e4567-e89b-42d3-a456-426614174000"
    private val idB = "123e4567-e89b-42d3-a456-426614174001"
    private val installed = setOf("Tokyo Night", "Gruvbox")
    private val live = mutableMapOf<String, Any>()
    private val writes = mutableListOf<String>()
    private val results = mutableListOf<FluxThemeSelection.Result>()
    private var paired = true
    private var open = true
    private var consent = false
    private var capable = true
    private var direct = true
    private val channel get() = FluxThemeSelectionChannel<Any>(direct,
        { peerId -> live[peerId] }, { paired }, { open }, { capable }, { consent },
        { installed }, { frame -> writes.add(frame) }, { results.add(it) })

    @Test fun explicitConsentAndCapabilitiesAreRequiredBeforeNetwork() {
        val session = Any(); live[peer] = session
        val state = channel; state.bind(peer, session)
        assertFalse(state.request(peer, session, "Tokyo Night", idA, 1))
        consent = true; capable = false
        assertFalse(state.request(peer, session, "Tokyo Night", idA, 1))
        capable = true; direct = false
        val play = channel; play.bind(peer, session)
        assertFalse(play.request(peer, session, "Tokyo Night", idA, 1))
        assertTrue(writes.isEmpty())
    }

    @Test fun exactSessionAndInstalledIdGateWriteAndAck() {
        val a = Any(); val b = Any(); live[peer] = a; consent = true
        val state = channel; state.bind(peer, a)
        assertFalse(state.request(peer, b, "Tokyo Night", idA, 1))
        assertFalse(state.request(peer, a, "not installed", idA, 1))
        assertTrue(state.request(peer, a, "Tokyo Night", idA, 1))
        assertEquals(1, writes.size)
        assertFalse(state.receive(peer, b, ack(idA, true, "Tokyo Night")))
        live[peer] = b; state.bind(peer, b); state.revoke(peer, a)
        assertFalse(state.receive(peer, a, ack(idA, true, "Tokyo Night")))
        assertTrue(state.request(peer, b, "Gruvbox", idB, 2))
        assertFalse(state.receive(peer, b, ack(idA, true, "Tokyo Night")))
        assertFalse(state.receive(peer, b, "{bad}\n"))
        assertTrue(state.receive(peer, b, ack(idB, true, "Gruvbox")))
        assertEquals(listOf(FluxThemeSelection.Result(idB, true, "Gruvbox")), results)
        assertFalse(state.receive(peer, b, ack(idB, true, "Gruvbox")))
    }

    @Test fun timedOutRequestCanBeRetriedButLateOldAckCannotCompleteNewRequest() {
        val a = Any(); live[peer] = a; consent = true
        val state = channel; state.bind(peer, a)
        assertTrue(state.request(peer, a, "Tokyo Night", idA, 1))
        state.cancel(peer, a)
        assertFalse(state.receive(peer, a, ack(idA, true, "Tokyo Night")))
        assertTrue(state.request(peer, a, "Gruvbox", idB, 2))
        assertFalse(state.receive(peer, a, ack(idA, true, "Tokyo Night")))
        assertTrue(state.receive(peer, a, ack(idB, true, "Gruvbox")))
    }

    @Test fun unpairAndWithdrawalRejectPendingAck() {
        val a = Any(); live[peer] = a; consent = true
        val state = channel; state.bind(peer, a)
        assertTrue(state.request(peer, a, "Tokyo Night", idA, 1))
        consent = false
        assertFalse(state.receive(peer, a, ack(idA, true, "Tokyo Night")))
        consent = true; paired = false
        assertFalse(state.receive(peer, a, ack(idA, true, "Tokyo Night")))
        assertTrue(results.isEmpty())
    }

    private fun ack(id: String, ok: Boolean, current: String) = JSONObject().put("id", 2)
        .put("type", FluxThemeSelection.SELECTED).put("body", JSONObject().put("version", 1)
            .put("requestId", id).put("ok", ok).put("current", current)).toString() + "\n"
}
