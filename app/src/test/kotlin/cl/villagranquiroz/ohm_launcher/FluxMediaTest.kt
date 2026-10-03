package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class FluxMediaTest {
    @Test fun blockedWriterDoesNotHoldPeerMapOrDelayConsentWithdrawal() {
        val peers = ConcurrentHashMap<String, Any>()
        val session = Any().also { peers["id"] = it }
        val consent = FluxMediaConsent()
        val token = consent.enable()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val aborted = AtomicInteger()
        val writer = FluxMediaWriter(peers, "id", session, Any(), consent,
            eligible = { true }, abort = { aborted.incrementAndGet(); release.countDown() })
        val thread = Thread {
            runCatching { writer.send(token) { entered.countDown(); release.await(5, TimeUnit.SECONDS) } }
        }
        thread.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val revoked = CountDownLatch(1)
            val revoker = Thread {
                synchronized(peers) { peers.remove("id", session); writer.withdraw() }
                writer.abortActive()
                revoked.countDown()
            }
            revoker.start()
            assertTrue("revoke waited for blocked writer", revoked.await(2, TimeUnit.SECONDS))
            assertFalse(consent.accepts(token))
            assertEquals(1, aborted.get())
            revoker.join(2000)
        } finally { release.countDown(); thread.join(2000) }
    }

    @Test fun queuedWriteRechecksExactSessionAndGenerationInsideSerializer() {
        val peers = ConcurrentHashMap<String, Any>()
        val session = Any().also { peers["id"] = it }
        val consent = FluxMediaConsent()
        val token = consent.enable()
        val serializer = Any()
        val writer = FluxMediaWriter(peers, "id", session, serializer, consent,
            eligible = { true }, abort = {})
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val writes = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            ready.countDown()
            try { writer.send(token) { writes.incrementAndGet() } }
            catch (e: Throwable) { failure.set(e) }
            finally { done.countDown() }
        }
        synchronized(serializer) {
            thread.start()
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            writer.disable()
            consent.enable()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(failure.get() is IllegalStateException)
        assertEquals(0, writes.get())
        thread.join(2000)
    }
    @Test fun queuedWriteWaitingOnSocketRejectsRevokedPeer() {
        val peers = ConcurrentHashMap<String, Any>()
        val session = Any().also { peers["id"] = it }
        val consent = FluxMediaConsent()
        val token = consent.enable()
        val serializer = Any()
        val writer = FluxMediaWriter(peers, "id", session, serializer, consent,
            eligible = { true }, abort = {})
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val writes = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            ready.countDown()
            try { writer.send(token) { writes.incrementAndGet() } }
            catch (e: Throwable) { failure.set(e) }
            finally { done.countDown() }
        }
        synchronized(serializer) {
            thread.start()
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            synchronized(peers) { peers.remove("id", session); writer.withdraw() }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(failure.get() is IllegalStateException)
        assertEquals(0, writes.get())
        thread.join(2000)
    }

    @Test fun closingViewAbortsBlockedWriteWithoutWaitingForSerializer() {
        val peers = ConcurrentHashMap<String, Any>()
        val session = Any().also { peers["id"] = it }
        val consent = FluxMediaConsent()
        val token = consent.enable()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = FluxMediaWriter(peers, "id", session, Any(), consent,
            eligible = { true }, abort = { release.countDown() })
        val thread = Thread { writer.send(token) { entered.countDown(); release.await(5, TimeUnit.SECONDS) } }
        thread.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closed = CountDownLatch(1)
            val closer = Thread { writer.disable(); closed.countDown() }
            closer.start()
            assertTrue("view close waited for socket write", closed.await(2, TimeUnit.SECONDS))
            assertFalse(consent.accepts(token))
            closer.join(2000)
        } finally { release.countDown(); thread.join(2000) }
    }

    @Test fun followUpRefreshKeepsOriginalCommandGeneration() {
        val peers = ConcurrentHashMap<String, Any>()
        val session = Any().also { peers["id"] = it }
        val consent = FluxMediaConsent()
        val token = consent.enable()
        val writes = AtomicInteger()
        val writer = FluxMediaWriter(peers, "id", session, Any(), consent,
            eligible = { true }, abort = {})
        writer.send(token) { writes.incrementAndGet() }
        writer.disable()
        consent.enable()
        assertThrows(IllegalStateException::class.java) {
            writer.send(token) { writes.incrementAndGet() }
        }
        assertEquals(1, writes.get())
    }

    @Test fun finalAuthorizationRejectsChangedPinAndReplacement() {
        val peers = ConcurrentHashMap<String, Any>()
        val session = Any().also { peers["id"] = it }
        val consent = FluxMediaConsent()
        val token = consent.enable()
        var pinnedOpen = true
        val writes = AtomicInteger()
        val writer = FluxMediaWriter(peers, "id", session, Any(), consent,
            eligible = { pinnedOpen }, abort = {})
        pinnedOpen = false
        assertThrows(IllegalStateException::class.java) { writer.send(token) { writes.incrementAndGet() } }
        pinnedOpen = true
        peers["id"] = Any()
        assertThrows(IllegalStateException::class.java) { writer.send(token) { writes.incrementAndGet() } }
        assertEquals(0, writes.get())
    }

    @Test fun queuedResponseFromClosedViewCannotEnterReopenedView() {
        val consent = FluxMediaConsent()
        assertFalse(consent.enabled)
        val first = consent.enable()
        assertTrue(consent.accepts(first))
        consent.disable()
        assertFalse(consent.accepts(first))
        val second = consent.enable()
        assertFalse(consent.accepts(first))
        assertTrue(consent.accepts(second))
    }
    @Test fun listAndIncrementalUpdates() {
        val state = FluxMediaState()
        assertTrue(state.receive(JSONObject().put("playerList", JSONArray().put("Spotify").put("VLC"))))
        assertEquals(listOf("Spotify", "VLC"), state.players().map { it.name })
        assertTrue(state.receive(JSONObject().put("player", "Spotify").put("title", "Song")
            .put("artist", "Artist").put("album", "Album").put("nowPlaying", "Artist - Song")
            .put("isPlaying", true).put("pos", 1500).put("length", 4000)
            .put("canPause", true).put("canSeek", true).put("canGoNext", true).put("volume", 72)))
        assertTrue(state.receive(JSONObject().put("player", "Spotify").put("pos", 2100)))
        val player = state.players().first()
        assertEquals("Song", player.title)
        assertEquals("Artist", player.artist)
        assertEquals("Album", player.album)
        assertEquals(2100L, player.position)
        assertEquals(72, player.volume)
        assertTrue(player.playing)
        assertTrue(player.canPause)
        assertTrue(state.receive(JSONObject().put("playerList", JSONArray().put("VLC"))))
        assertEquals(listOf("VLC"), state.players().map { it.name })
    }

    @Test fun longMediaDurationsRemainValidWithoutNumericCoercion() {
        val state = FluxMediaState()
        assertTrue(state.receive(JSONObject().put("player", "Long recording")
            .put("title", "Chapter").put("pos", 90_000_000L).put("length", 120_000_000L)))
        assertEquals(90_000_000L, state.players().single().position)
        assertEquals(120_000_000L, state.players().single().length)
        assertFalse(state.receive(JSONObject().put("player", "Long recording").put("pos", 1.5)))
        assertFalse(state.receive(JSONObject().put("player", "Long recording").put("pos", "90000000")))
        assertEquals(90_000_000L, state.players().single().position)
        assertEquals(90_000_000L, FluxMediaProtocol.position("Long recording", 90_000_000L).getLong("SetPosition"))
    }

    @Test fun rejectsMalformedAndOversizedWithoutMutating() {
        val state = FluxMediaState()
        assertTrue(state.receive(JSONObject().put("playerList", JSONArray().put("VLC"))))
        for (body in listOf(
            JSONObject().put("playerList", JSONArray().put("x".repeat(200))),
            JSONObject().put("playerList", JSONArray().put(42)),
            JSONObject().put("playerList", JSONArray().apply { repeat(33) { put("p$it") } }),
            JSONObject().put("player", "VLC").put("volume", 101),
            JSONObject().put("player", "VLC").put("isPlaying", "true"),
            JSONObject().put("player", "VLC").put("pos", -1),
            JSONObject().put("player", "new").put("title", "x".repeat(5000)),
        )) assertFalse(state.receive(body))
        assertEquals(listOf("VLC"), state.players().map { it.name })
        assertNull(state.players().single().volume)
    }

    @Test fun optionalVolumeAndExactCommands() {
        val state = FluxMediaState()
        assertTrue(state.receive(JSONObject().put("player", "VLC").put("canPlay", true)))
        assertNull(state.players().single().volume)
        assertEquals("kdeconnect.mpris.request", FluxMediaProtocol.REQUEST)
        assertTrue(FluxMediaProtocol.playerList().getBoolean("requestPlayerList"))
        assertTrue(FluxMediaProtocol.refresh("VLC").getBoolean("requestNowPlaying"))
        assertTrue(FluxMediaProtocol.refresh("VLC").getBoolean("requestVolume"))
        assertEquals("PlayPause", FluxMediaProtocol.action("VLC", "PlayPause").getString("action"))
        for (command in listOf("Play", "Pause", "Next", "Previous", "Stop"))
            assertEquals(command, FluxMediaProtocol.action("VLC", command).getString("action"))
        assertThrows(IllegalArgumentException::class.java) { FluxMediaProtocol.action("VLC", "Seek") }
        assertEquals(2300L, FluxMediaProtocol.position("VLC", 2300).getLong("SetPosition"))
        assertFalse(FluxMediaProtocol.position("VLC", 2300).has("Seek"))
        assertEquals(50, FluxMediaProtocol.volume("VLC", 50).getInt("setVolume"))
        assertThrows(IllegalArgumentException::class.java) { FluxMediaProtocol.volume("VLC", 101) }
    }

    @Test fun seekRequiresCurrentPlayerCapabilityAndKnownDuration() {
        val state = FluxMediaState()
        state.receive(JSONObject().put("player", "mpv").put("canSeek", true).put("length", 90_000))
        assertEquals(90_000L, FluxMediaControls.seek(state, "mpv", 100_000).getLong("SetPosition"))
        assertEquals(0L, FluxMediaControls.seek(state, "mpv", -50).getLong("SetPosition"))
        state.receive(JSONObject().put("player", "mpv").put("length", Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE / 1000, FluxMediaControls.seek(state, "mpv", Long.MAX_VALUE).getLong("SetPosition"))
        assertThrows(IllegalArgumentException::class.java) { FluxMediaControls.seek(state, "unknown", 10) }
        state.receive(JSONObject().put("player", "mpv").put("canSeek", false))
        assertThrows(IllegalArgumentException::class.java) { FluxMediaControls.seek(state, "mpv", 10) }
        state.receive(JSONObject().put("player", "mpv").put("canSeek", true).put("length", 0))
        assertThrows(IllegalArgumentException::class.java) { FluxMediaControls.seek(state, "mpv", 10) }
    }

    @Test fun seekSliderKeepsMillisecondsAndShowsTheSameSelectedPosition() {
        val state = FluxMediaState()
        state.receive(JSONObject().put("player", "mpv").put("canSeek", true).put("length", 180_000))
        val halfway = FluxMediaControls.positionAtProgress(180_000, 500, 1000)
        assertEquals(90_000L, halfway)
        assertEquals(90L, halfway / 1000)
        assertEquals(90_000L, FluxMediaControls.seek(state, "mpv", halfway).getLong("SetPosition"))
        val fractional = FluxMediaControls.positionAtProgress(90_500, 500, 1000)
        assertEquals(45_250L, fractional)
        assertEquals(45L, fractional / 1000)
    }

    @Test fun seekSliderInterpolationHandlesLimitsWithoutOverflow() {
        assertEquals(0L, FluxMediaControls.positionAtProgress(Long.MAX_VALUE, -1, 1000))
        assertEquals(Long.MAX_VALUE / 2, FluxMediaControls.positionAtProgress(Long.MAX_VALUE, 500, 1000))
        assertEquals(Long.MAX_VALUE, FluxMediaControls.positionAtProgress(Long.MAX_VALUE, 1001, 1000))
        assertEquals(0L, FluxMediaControls.positionAtProgress(0, 500, 1000))
        assertThrows(IllegalArgumentException::class.java) {
            FluxMediaControls.positionAtProgress(1000, 500, 0)
        }
    }

    @Test fun volumeRequiresAdvertisedWritableVolumeAndBounds() {
        val state = FluxMediaState()
        state.receive(JSONObject().put("player", "mpv").put("canSeek", true))
        assertThrows(IllegalArgumentException::class.java) { FluxMediaControls.volume(state, "mpv", 50) }
        state.receive(JSONObject().put("player", "mpv").put("volume", 40))
        assertEquals(0, FluxMediaControls.volume(state, "mpv", -1).getInt("setVolume"))
        assertEquals(100, FluxMediaControls.volume(state, "mpv", 101).getInt("setVolume"))
        state.receive(JSONObject().put("player", "mpv").put("title", "New track").put("canSeek", true))
        assertNull(state.players().single().volume) // complete upstream snapshot omitted non-writable volume
        assertThrows(IllegalArgumentException::class.java) { FluxMediaControls.volume(state, "mpv", 20) }
        state.receive(JSONObject().put("playerList", JSONArray()))
        assertThrows(IllegalArgumentException::class.java) { FluxMediaControls.volume(state, "mpv", 20) }
    }

    @Test fun controlWritesRefreshPlaybackButQueriesDoNot() {
        for (body in listOf(FluxMediaProtocol.action("mpv", "Pause"),
            FluxMediaProtocol.position("mpv", 123), FluxMediaProtocol.volume("mpv", 70)))
            assertTrue(FluxMediaControls.needsRefresh(body))
        assertFalse(FluxMediaControls.needsRefresh(FluxMediaProtocol.playerList()))
        assertFalse(FluxMediaControls.needsRefresh(FluxMediaProtocol.refresh("mpv")))
    }

    @Test fun staleSessionAndPlayCannotSendOrReceive() {
        val peers = ConcurrentHashMap<String, Any>()
        val old = Any()
        val current = Any()
        peers["id"] = current
        var sent = false
        assertThrows(IllegalStateException::class.java) {
            FluxMediaGate.withCurrent(peers, "id", old, true, false) { sent = true }
        }
        assertThrows(IllegalStateException::class.java) {
            FluxMediaGate.withCurrent(peers, "id", current, false, false) { sent = true }
        }
        assertThrows(IllegalStateException::class.java) {
            FluxMediaGate.withCurrent(peers, "id", current, true, true) { sent = true }
        }
        assertFalse(sent)
        assertThrows(IllegalStateException::class.java) {
            FluxMediaGate.withCurrent(peers, "id", current, true, false, false) { sent = true }
        }
        assertFalse(sent)
        FluxMediaGate.withCurrent(peers, "id", current, true, false) { sent = true }
        assertTrue(sent)
        peers.remove("id")
        assertThrows(IllegalStateException::class.java) {
            FluxMediaGate.withCurrent(peers, "id", current, true, false) { sent = true }
        }
    }
}
