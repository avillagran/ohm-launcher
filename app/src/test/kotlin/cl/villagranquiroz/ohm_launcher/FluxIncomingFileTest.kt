package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class FluxIncomingFileTest {
    @After fun allCleanupReservationsReturnBeforeNextTest() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (FluxIncomingFileReceiver.activeCleanupReservations != 0 && System.nanoTime() < deadline)
            Thread.sleep(1)
        assertEquals("incoming-file cleanup did not release its admission permits", 0,
            FluxIncomingFileReceiver.activeCleanupReservations)
    }
    private fun frame(name: String = "photo.jpg", size: Long = 3, port: Int = 1739): String = JSONObject()
        .put("id", 42).put("type", "kdeconnect.share.request")
        .put("body", JSONObject().put("filename", name).put("open", false)
            .put("numberOfFiles", 1).put("totalPayloadSize", size))
        .put("payloadSize", size).put("payloadTransferInfo", JSONObject().put("port", port)).toString()

    @Test fun fileOfferDispatchRequiresPairedExactLiveSession() {
        val offers = mutableListOf<FluxIncomingFile>()
        FluxIncomingFileGate.deliver(frame(), paired = false, live = true) { offers.add(it) }
        FluxIncomingFileGate.deliver(frame(), paired = true, live = false) { offers.add(it) }
        FluxIncomingFileGate.deliver(frame(name = "../escape"), paired = true, live = true) { offers.add(it) }
        assertTrue(offers.isEmpty())
        FluxIncomingFileGate.deliver(frame(), paired = true, live = true) { offers.add(it) }
        assertEquals(listOf(FluxIncomingFile("photo.jpg", 3, 1739)), offers)
    }

    @Test fun parsesOnlySingleBoundedSafeDirectOffer() {
        assertEquals("photo.jpg", FluxIncomingFileParser.parse(frame())?.name)
        for (name in listOf("", ".", "..", "../x", "x/y", "x\\y", "a\u0000b", "a\nb", "x".repeat(256), "\ud800"))
            assertNull(name, FluxIncomingFileParser.parse(frame(name)))
        for (size in listOf(0L, -1L, 256L * 1024 * 1024 + 1))
            assertNull(FluxIncomingFileParser.parse(frame(size = size)))
        for (port in listOf(0, 1738, 1765, 65535)) assertNull(FluxIncomingFileParser.parse(frame(port = port)))
        assertNotNull(FluxIncomingFileParser.parse(frame(size = 256L * 1024 * 1024)))
    }

    @Test fun rejectsConfusedMetadataAndUnsupportedTunnel() {
        val original = JSONObject(frame())
        val bad = listOf(
            JSONObject(frame()).put("type", "kdeconnect.share.request.update"),
            JSONObject(frame()).put("payloadSize", 4),
            JSONObject(frame()).put("payloadSize", 3.5),
            JSONObject(frame()).put("payloadTransferInfo", JSONObject().put("port", 1739).put("tunnel", true)),
            JSONObject(frame()).put("body", JSONObject(original.getJSONObject("body").toString()).put("open", true)),
            JSONObject(frame()).put("body", JSONObject(original.getJSONObject("body").toString()).put("numberOfFiles", 2)),
            JSONObject(frame()).put("body", JSONObject(original.getJSONObject("body").toString()).put("text", "x")),
            JSONObject(frame()).put("body", JSONObject(original.getJSONObject("body").toString()).put("totalPayloadSize", 4)),
            JSONObject(frame()).put("id", "42"),
        )
        bad.forEach { assertNull(it.toString(), FluxIncomingFileParser.parse(it.toString())) }
        assertNull(FluxIncomingFileParser.parse(frame() + " trailing"))
        assertNull(FluxIncomingFileParser.parse("x".repeat(9000)))
    }

    @Test fun fileOfferRejectsAmbiguousEndpointEvenOutsideParser() {
        assertThrows(IllegalArgumentException::class.java) {
            FluxIncomingFile("safe.txt", 3, port = 1739, tunnel = "a".repeat(24))
        }
    }

    @Test fun tunnelOfferRequiresExclusiveValidTokenAndSafeFileMetadata() {
        val base = JSONObject(frame())
        val transfer = JSONObject().put("tunnel", "a".repeat(24))
        val valid = JSONObject(base.toString()).put("payloadTransferInfo", transfer)
        assertEquals(FluxIncomingFile("photo.jpg", 3, 0, "a".repeat(24)),
            FluxIncomingFileParser.parse(valid.toString()))
        for (value in listOf("", "A".repeat(24), "z".repeat(24), "a".repeat(23), "a".repeat(25), "../x")) {
            assertNull(value, FluxIncomingFileParser.parse(JSONObject(base.toString())
                .put("payloadTransferInfo", JSONObject().put("tunnel", value)).toString()))
        }
        for (bad in listOf(
            JSONObject().put("port", 1739).put("tunnel", "a".repeat(24)),
            JSONObject().put("tunnel", 42), JSONObject().put("tunnel", "a".repeat(24)).put("extra", 1),
        )) assertNull(FluxIncomingFileParser.parse(JSONObject(base.toString()).put("payloadTransferInfo", bad).toString()))
        assertNull(FluxIncomingFileParser.parse(JSONObject(valid.toString()).put("payloadSize", 4).toString()))
    }

    @Test fun tunnelListenerIsAnnouncedAfterConsentAndStagesMutualTlsPayload() {
        val directory = Files.createTempDirectory("flux-incoming-tunnel").toFile()
        val desktop = FluxIdentity.createMaterial("a".repeat(32))
        val phone = FluxIdentity.createMaterial("b".repeat(32))
        val session = Any()
        val callback = CountDownLatch(1)
        val desktopError = AtomicReference<Throwable?>()
        val announced = AtomicReference<Pair<String, Int>>()
        val receiver = FluxIncomingFileReceiver(directory, session, { session }, { true },
            InetAddress.getLoopbackAddress(), desktop.certificate.encoded,
            { FluxIdentity.createTlsContext(phone) }, sendTunnel = { id, port ->
                announced.set(id to port)
                callback.countDown()
            })
        val id = "c".repeat(24)
        val token = receiver.offer(FluxIncomingFile("safe.txt", 3, tunnel = id))
        assertNull("must await consent", announced.get())
        val desktopWorker = Thread {
            try {
                assertTrue(callback.await(3, TimeUnit.SECONDS))
                val port = announced.get()!!.second
                assertTrue(port in 1739..1764)
                Socket(InetAddress.getLoopbackAddress(), port).use { raw ->
                    val tls = FluxIdentity.createTlsContext(desktop).socketFactory.createSocket(
                        raw, raw.inetAddress.hostAddress, raw.port, false) as javax.net.ssl.SSLSocket
                    tls.use {
                        it.useClientMode = true
                        it.enabledProtocols = arrayOf("TLSv1.2")
                        it.startHandshake()
                        assertArrayEquals(phone.certificate.encoded,
                            (it.session.peerCertificates[0] as java.security.cert.X509Certificate).encoded)
                        it.outputStream.write(byteArrayOf(1, 2, 3))
                        it.outputStream.flush()
                    }
                }
            } catch (e: Throwable) { desktopError.set(e) }
        }.apply { isDaemon = true; start() }
        try {
            val staged = receiver.accept(token)
            assertEquals(id, announced.get()!!.first)
            assertArrayEquals(byteArrayOf(1, 2, 3), staged.readBytes())
            desktopWorker.join(3000)
            assertFalse(desktopWorker.isAlive)
            assertNull(desktopError.get()?.toString(), desktopError.get())
            staged.delete()
        } finally { receiver.revoke(); desktopWorker.join(3000); directory.deleteRecursively() }
    }

    @Test fun tunnelWrongDesktopPinCannotStageBytes() {
        val directory = Files.createTempDirectory("flux-tunnel-wrong-pin").toFile()
        val desktop = FluxIdentity.createMaterial("a".repeat(32))
        val phone = FluxIdentity.createMaterial("b".repeat(32))
        val other = FluxIdentity.createMaterial("c".repeat(32))
        val session = Any()
        val announced = CountDownLatch(1)
        val port = java.util.concurrent.atomic.AtomicInteger()
        val receiver = FluxIncomingFileReceiver(directory, session, { session }, { true },
            InetAddress.getLoopbackAddress(), other.certificate.encoded,
            { FluxIdentity.createTlsContext(phone) }, sendTunnel = { _, opened ->
                port.set(opened); announced.countDown()
            })
        val worker = Thread {
            if (announced.await(3, TimeUnit.SECONDS)) runCatching {
                Socket(InetAddress.getLoopbackAddress(), port.get()).use { raw ->
                    val tls = FluxIdentity.createTlsContext(desktop).socketFactory.createSocket(
                        raw, raw.inetAddress.hostAddress, raw.port, false) as javax.net.ssl.SSLSocket
                    tls.use { it.useClientMode = true; it.startHandshake(); it.outputStream.write(byteArrayOf(1, 2, 3)) }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            assertThrows(IllegalStateException::class.java) {
                receiver.accept(receiver.offer(FluxIncomingFile("safe.txt", 3, tunnel = "d".repeat(24))))
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { receiver.revoke(); worker.join(3000); directory.deleteRecursively() }
    }

    @Test fun tunnelRevokeClosesWaitingListenerWithoutBlockingCallerOrAnotherDeadline() {
        val directory = Files.createTempDirectory("flux-tunnel-revoke").toFile()
        val session = Any()
        val firstReady = CountDownLatch(1)
        val firstAnnounced = java.util.concurrent.atomic.AtomicBoolean()
        val firstClosing = CountDownLatch(1)
        val firstClosed = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val secondClosed = CountDownLatch(1)
        val skippedBinding = java.util.concurrent.atomic.AtomicBoolean()
        val firstStage = AtomicReference("not started")
        val first = FluxIncomingFileReceiver(directory, session, { session }, { true },
            InetAddress.getLoopbackAddress(), byteArrayOf(1), { error("No TLS expected") },
            listenerFactory = { port ->
                firstStage.set("binding $port")
                // A binding constructor calls virtual close() on BindException. Such a close
                // must not enter this test's intentionally blocked cleanup hook.
                val bound = object : ServerSocket() {
                    override fun close() {
                        val listening = isBound
                        super.close()
                        if (listening) {
                            firstClosing.countDown()
                            try { releaseClose.await() } finally { firstClosed.countDown() }
                        }
                    }
                }
                try {
                    // Exercise a failed candidate on every run, independent of TIME_WAIT.
                    if (port == 1739) {
                        skippedBinding.set(true)
                        throw java.net.BindException("Reserved test candidate")
                    }
                    bound.bind(java.net.InetSocketAddress(port), 1)
                    bound
                } catch (failure: Throwable) { bound.close(); throw failure }
            }, sendTunnel = { _, _ ->
                firstStage.set("listening")
                firstAnnounced.set(true)
                firstReady.countDown()
            })
        val second = FluxIncomingFileReceiver(directory, session, { session }, { true },
            InetAddress.getLoopbackAddress(), byteArrayOf(1), { error("No TLS expected") },
            offerTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(300),
            listenerFactory = { port ->
                val bound = object : ServerSocket() {
                    override fun close() {
                        val listening = isBound
                        super.close()
                        if (listening) secondClosed.countDown()
                    }
                }
                try { bound.bind(java.net.InetSocketAddress(port), 1); bound }
                catch (failure: Throwable) { bound.close(); throw failure }
            }, sendTunnel = { _, _ -> })
        val firstFailure = AtomicReference<Throwable?>()
        val secondFailure = AtomicReference<Throwable?>()
        val firstFinished = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val firstWorker = Thread {
            try { first.accept(first.offer(FluxIncomingFile("first", 3, tunnel = "a".repeat(24)))) }
            catch (e: Throwable) { firstFailure.set(e) }
            finally { firstReady.countDown(); firstFinished.countDown() }
        }.apply { isDaemon = true; start() }
        // Arm the second deadline only after the first cleanup worker is demonstrably blocked.
        val secondWorker = Thread {
            try { second.accept(second.offer(FluxIncomingFile("second", 3, tunnel = "b".repeat(24)))) }
            catch (e: Throwable) { secondFailure.set(e) }
            finally { secondFinished.countDown() }
        }.apply { isDaemon = true }
        try {
            val ready = firstReady.await(2, TimeUnit.SECONDS)
            assertTrue("first listener not ready; stage=${firstStage.get()}, failure=${firstFailure.get()}", ready)
            firstFailure.get()?.let { throw AssertionError("first listener failed at ${firstStage.get()}", it) }
            assertTrue("first accept ended without announcing its listener", firstAnnounced.get())
            assertTrue("failed bind candidate was not exercised", skippedBinding.get())
            val withdrawing = Thread { first.revoke() }.apply { isDaemon = true; start() }
            assertTrue("first cleanup never entered", firstClosing.await(2, TimeUnit.SECONDS))
            withdrawing.join(500)
            assertFalse("revocation blocked behind listener close", withdrawing.isAlive)
            secondWorker.start()
            val independentlyClosed = secondClosed.await(2, TimeUnit.SECONDS)
            assertTrue("second deadline blocked behind first close; failure=${secondFailure.get()}", independentlyClosed)
            assertEquals("first close must remain blocked until explicitly released", 1L, firstClosed.count)
            releaseClose.countDown()
            assertTrue("first close did not finish", firstClosed.await(2, TimeUnit.SECONDS))
            assertTrue("first accept did not exit", firstFinished.await(2, TimeUnit.SECONDS))
            assertTrue("second accept did not exit", secondFinished.await(2, TimeUnit.SECONDS))
            firstWorker.join(2000); secondWorker.join(2000)
            assertFalse(firstWorker.isAlive); assertFalse(secondWorker.isAlive)
            assertNotNull(firstFailure.get()); assertNotNull(secondFailure.get())
        } finally {
            releaseClose.countDown()
            first.revoke(); second.revoke()
            firstWorker.join(2000); secondWorker.join(2000)
            directory.deleteRecursively()
        }
    }

    @Test fun exactCopyRejectsTruncationAndExtraBytes() {
        val output = ByteArrayOutputStream()
        FluxIncomingFileReceiver.copyExact(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output, 3) {}
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        assertThrows(EOFException::class.java) {
            FluxIncomingFileReceiver.copyExact(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 3) {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            FluxIncomingFileReceiver.copyExact(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), ByteArrayOutputStream(), 3) {}
        }
    }

    @Test fun offerRequiresExplicitAcceptAndExpiresFromOfferTime() {
        val directory = Files.createTempDirectory("flux-incoming-test").toFile()
        val session = Any()
        var now = 0L
        var calls = 0
        val receiver = FluxIncomingFileReceiver(directory, session, { session }, { true },
            InetAddress.getLoopbackAddress(), byteArrayOf(1), { error("No TLS expected") },
            clockNanos = { now }, socket = { calls++; Socket() })
        try {
            val token = receiver.offer(requireNotNull(FluxIncomingFileParser.parse(frame())))
            assertEquals(0, calls)
            now = TimeUnit.SECONDS.toNanos(21)
            assertThrows(IllegalStateException::class.java) { receiver.accept(token) }
            assertEquals(0, calls)
        } finally { receiver.revoke(); directory.deleteRecursively() }
    }

    @Test fun expiredUnacceptedOfferDoesNotRevokeItsLiveControlSession() {
        val directory = Files.createTempDirectory("flux-incoming-expire").toFile()
        val session = Any()
        var now = 0L
        val receiver = FluxIncomingFileReceiver(directory, session, { session }, { true },
            InetAddress.getLoopbackAddress(), byteArrayOf(1), { error("No TLS expected") },
            clockNanos = { now }, offerTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(10))
        try {
            val file = requireNotNull(FluxIncomingFileParser.parse(frame()))
            val old = receiver.offer(file)
            now = TimeUnit.MILLISECONDS.toNanos(11)
            Thread.sleep(40) // Allow the short scheduled deadline to run.
            assertThrows(IllegalStateException::class.java) { receiver.accept(old) }
            val next = receiver.offer(file)
            assertNotEquals(old, next)
        } finally { receiver.revoke(); directory.deleteRecursively() }
    }

    @Test fun blockedCloseCannotDelayAnotherReceiversDeadlineOrRevocation() {
        val firstDirectory = Files.createTempDirectory("flux-incoming-first").toFile()
        val secondDirectory = Files.createTempDirectory("flux-incoming-second").toFile()
        val session = Any()
        val firstConnecting = CountDownLatch(1)
        val secondConnecting = CountDownLatch(1)
        val firstClosing = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val secondClosed = CountDownLatch(1)
        fun blockedSocket(entered: CountDownLatch, onClose: () -> Unit) = object : Socket() {
            override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
                entered.countDown()
                while (!isClosed) Thread.sleep(1)
                throw java.net.SocketException("closed")
            }
            override fun close() { super.close(); onClose() }
        }
        val firstSocket = blockedSocket(firstConnecting) {
            firstClosing.countDown()
            assertTrue("release first close", releaseClose.await(3, TimeUnit.SECONDS))
        }
        val secondSocket = blockedSocket(secondConnecting) { secondClosed.countDown() }
        val first = FluxIncomingFileReceiver(firstDirectory, session, { session }, { true },
            InetAddress.getLoopbackAddress(), byteArrayOf(1), { error("No TLS expected") },
            offerTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(100), socket = { firstSocket })
        val second = FluxIncomingFileReceiver(secondDirectory, session, { session }, { true },
            InetAddress.getLoopbackAddress(), byteArrayOf(1), { error("No TLS expected") },
            offerTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(200), socket = { secondSocket })
        val failures = listOf(AtomicReference<Throwable?>(), AtomicReference<Throwable?>())
        val workers = listOf(first to failures[0], second to failures[1]).map { (receiver, failure) ->
            val token = receiver.offer(FluxIncomingFile("safe.txt", 3, 1739))
            Thread { try { receiver.accept(token) } catch (e: Throwable) { failure.set(e) } }
                .apply { isDaemon = true; start() }
        }
        try {
            assertTrue(firstConnecting.await(2, TimeUnit.SECONDS))
            assertTrue(secondConnecting.await(2, TimeUnit.SECONDS))
            assertTrue("first deadline did not close socket", firstClosing.await(2, TimeUnit.SECONDS))
            assertTrue("second deadline stalled behind close", secondClosed.await(2, TimeUnit.SECONDS))
            val revoked = Thread { first.revoke() }.apply { isDaemon = true; start() }
            revoked.join(500)
            assertFalse("UI revocation blocked by socket close", revoked.isAlive)
            releaseClose.countDown()
            workers.forEach { it.join(2000); assertFalse(it.isAlive) }
            failures.forEach { assertNotNull(it.get()) }
        } finally {
            releaseClose.countDown()
            first.revoke(); second.revoke()
            workers.forEach { it.join(2000) }
            firstDirectory.deleteRecursively(); secondDirectory.deleteRecursively()
        }
    }

    @Test fun blockedClosesHaveBoundedWorkersAndRejectNewPayloadsWithoutStallingDeadlines() {
        val directory = Files.createTempDirectory("flux-incoming-saturation").toFile()
        val session = Any()
        val release = CountDownLatch(1)
        val secondClosing = CountDownLatch(1)
        val dialed = java.util.concurrent.atomic.AtomicInteger()
        val activeCloses = java.util.concurrent.atomic.AtomicInteger()
        val peakCloses = java.util.concurrent.atomic.AtomicInteger()
        val workers = mutableListOf<Thread>()
        val receivers = mutableListOf<FluxIncomingFileReceiver>()
        fun receiver(): FluxIncomingFileReceiver = FluxIncomingFileReceiver(
            directory, session, { session }, { true }, InetAddress.getLoopbackAddress(),
            byteArrayOf(1), { error("No TLS expected") },
            offerTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(250),
            socket = {
                val ordinal = dialed.incrementAndGet()
                object : Socket() {
                    override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
                        while (!isClosed) Thread.sleep(1)
                        throw java.net.SocketException("closed")
                    }
                    override fun close() {
                        super.close()
                        val count = activeCloses.incrementAndGet()
                        peakCloses.accumulateAndGet(count) { old, new -> maxOf(old, new) }
                        if (ordinal == 2) secondClosing.countDown()
                        try { release.await() } finally { activeCloses.decrementAndGet() }
                    }
                }
            },
        ).also { receivers.add(it) }
        try {
            val first = receiver()
            val token = first.offer(FluxIncomingFile("safe.txt", 3, 1739))
            workers.add(Thread { runCatching { first.accept(token) } }.apply { isDaemon = true; start() })
            val start = System.nanoTime()
            while (dialed.get() == 0 && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2)) Thread.sleep(1)
            assertEquals(1, dialed.get())
            first.revoke()
            val second = receiver()
            val secondToken = second.offer(FluxIncomingFile("safe.txt", 3, 1739))
            workers.add(Thread { runCatching { second.accept(secondToken) } }.apply { isDaemon = true; start() })
            assertTrue("independent second deadline stalled", secondClosing.await(2, TimeUnit.SECONDS))
            for (index in 0 until 2) {
                val next = receiver()
                val nextToken = next.offer(FluxIncomingFile("safe.txt", 3, 1739))
                workers.add(Thread { runCatching { next.accept(nextToken) } }.apply { isDaemon = true; start() })
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (dialed.get() < index + 3 && System.nanoTime() < until) Thread.sleep(1)
                assertEquals(index + 3, dialed.get())
                next.revoke()
            }
            for (index in 0 until 24) {
                val next = receiver()
                val nextToken = next.offer(FluxIncomingFile("safe.txt", 3, 1739))
                val result = AtomicReference<Throwable?>()
                val rejected = Thread {
                    try { next.accept(nextToken) } catch (e: Throwable) { result.set(e) }
                }.apply { isDaemon = true; start() }
                workers.add(rejected)
                rejected.join(200)
                assertFalse("saturated accept blocked", rejected.isAlive)
                assertTrue("saturated accept was not rejected", result.get() is IllegalStateException)
                next.revoke()
            }
            assertTrue("unbounded sockets: ${dialed.get()}", dialed.get() <= 4)
            assertTrue("unbounded blocked closes: ${peakCloses.get()}", peakCloses.get() <= 12)
            assertTrue("unbounded pending cleanup jobs", FluxIncomingFileReceiver.cleanupPendingJobs <= 12)
            val refused = receiver()
            val refusedToken = refused.offer(FluxIncomingFile("safe.txt", 3, 1739))
            assertThrows(IllegalStateException::class.java) { refused.accept(refusedToken) }
            assertEquals("capacity exhaustion must not dial", 4, dialed.get())
        } finally {
            release.countDown()
            receivers.forEach { it.revoke() }
            workers.forEach { it.join(2000) }
            directory.deleteRecursively()
        }
    }

    @Test fun receiverCannotReuseStateUntilItsPreviousCloseCompletes() {
        val directory = Files.createTempDirectory("flux-incoming-reuse").toFile()
        val session = Any()
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(2)
        val receiver = FluxIncomingFileReceiver(directory, session, { session }, { true },
            InetAddress.getLoopbackAddress(), byteArrayOf(1), { error("No TLS expected") },
            socket = {
                object : Socket() {
                    override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
                        throw java.net.SocketException("synthetic connect failure")
                    }
                    override fun close() {
                        closing.countDown()
                        release.await(3, TimeUnit.SECONDS)
                        super.close()
                        closed.countDown()
                    }
                }
            })
        try {
            val file = FluxIncomingFile("safe.txt", 3, 1739)
            val first = receiver.offer(file)
            assertThrows(java.net.SocketException::class.java) { receiver.accept(first) }
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { receiver.offer(file) }
            release.countDown()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (closed.count != 1L && System.nanoTime() < until) Thread.sleep(1)
            assertEquals(1L, closed.count)
            val second = receiver.offer(file)
            assertThrows(java.net.SocketException::class.java) { receiver.accept(second) }
            assertTrue(closed.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            receiver.revoke()
            directory.deleteRecursively()
        }
    }

    @Test fun explicitRevokeReturnsWhileSocketCloseIsBlocked() {
        val directory = Files.createTempDirectory("flux-incoming-revoke").toFile()
        val session = Any()
        val connecting = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val raw = object : Socket() {
            override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
                connecting.countDown()
                while (!isClosed) Thread.sleep(1)
                throw java.net.SocketException("closed")
            }
            override fun close() {
                super.close()
                closing.countDown()
                releaseClose.await(3, TimeUnit.SECONDS)
            }
        }
        val receiver = FluxIncomingFileReceiver(directory, session, { session }, { true },
            InetAddress.getLoopbackAddress(), byteArrayOf(1), { error("No TLS expected") },
            socket = { raw })
        val token = receiver.offer(FluxIncomingFile("safe.txt", 3, 1739))
        val worker = Thread { runCatching { receiver.accept(token) } }.apply { isDaemon = true; start() }
        try {
            assertTrue(connecting.await(2, TimeUnit.SECONDS))
            val revocation = Thread { receiver.revoke() }.apply { isDaemon = true; start() }
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            revocation.join(500)
            assertFalse("synchronous authority withdrawal must not wait for close", revocation.isAlive)
            assertThrows(IllegalStateException::class.java) {
                receiver.offer(FluxIncomingFile("later.txt", 3, 1739))
            }
        } finally {
            releaseClose.countDown()
            receiver.revoke()
            worker.join(2000)
            directory.deleteRecursively()
        }
    }

    @Test fun sameReceiverCannotReuseCleanupReservationBeforeBlockedCloseFinishes() {
        val directory = Files.createTempDirectory("flux-incoming-reuse").toFile()
        val session = Any()
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val receiver = FluxIncomingFileReceiver(directory, session, { session }, { true },
            InetAddress.getLoopbackAddress(), byteArrayOf(1), { error("No TLS expected") },
            socket = {
                object : Socket() {
                    override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
                        throw java.net.SocketException("dial refused")
                    }
                    override fun close() {
                        super.close()
                        closing.countDown()
                        release.await()
                    }
                }
            })
        try {
            val file = FluxIncomingFile("safe.txt", 3, 1739)
            assertThrows(java.net.SocketException::class.java) { receiver.accept(receiver.offer(file)) }
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { receiver.offer(file) }
            release.countDown()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var next: String? = null
            while (next == null && System.nanoTime() < until) {
                next = runCatching { receiver.offer(file) }.getOrNull()
                if (next == null) Thread.sleep(1)
            }
            assertNotNull("reservation was not returned after close", next)
        } finally {
            release.countDown()
            receiver.revoke()
            directory.deleteRecursively()
        }
    }

    @Test fun staleSessionAndRevokedOfferNeverDial() {
        val directory = Files.createTempDirectory("flux-incoming-test").toFile()
        val session = Any()
        var mapped: Any? = session
        var calls = 0
        val receiver = FluxIncomingFileReceiver(directory, session, { mapped }, { true },
            InetAddress.getLoopbackAddress(), byteArrayOf(1), { error("No TLS expected") },
            socket = { calls++; Socket() })
        try {
            val file = requireNotNull(FluxIncomingFileParser.parse(frame()))
            val old = receiver.offer(file)
            val next = receiver.offer(file)
            assertThrows(IllegalStateException::class.java) { receiver.accept(old) }
            mapped = Any()
            assertThrows(IllegalStateException::class.java) { receiver.accept(next) }
            mapped = session
            val revoked = receiver.offer(file)
            receiver.revoke()
            assertThrows(IllegalStateException::class.java) { receiver.accept(revoked) }
            assertEquals(0, calls)
        } finally { receiver.revoke(); directory.deleteRecursively() }
    }

    @Test fun revokeClosesSocketWhileConnectBlocked() {
        val directory = Files.createTempDirectory("flux-incoming-test").toFile()
        val session = Any()
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val socket = object : Socket() {
            override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
                entered.countDown()
                assertTrue(closed.await(2, TimeUnit.SECONDS))
                throw java.net.SocketException("closed")
            }
            override fun close() { super.close(); closed.countDown() }
        }
        val receiver = FluxIncomingFileReceiver(directory, session, { session }, { true },
            InetAddress.getLoopbackAddress(), byteArrayOf(1), { error("No TLS expected") }, socket = { socket })
        try {
            val token = receiver.offer(requireNotNull(FluxIncomingFileParser.parse(frame())))
            val result = AtomicReference<Throwable?>()
            val worker = Thread { try { receiver.accept(token) } catch (e: Throwable) { result.set(e) } }
            worker.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            receiver.revoke()
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertNotNull(result.get())
            assertTrue(closed.count == 0L)
        } finally { receiver.revoke(); directory.deleteRecursively() }
    }

    /** Real mutual TLS on loopback; never mocks the peer certificate or decrypted stream. */
    private fun exchange(payload: ByteArray, declared: Long, wrongPin: Boolean = false,
                         stopBeforePayload: Boolean = false,
                         action: (FluxIncomingFileReceiver, String, File, CountDownLatch) -> Unit) {
        val directory = Files.createTempDirectory("flux-incoming-tls").toFile()
        val id = "a".repeat(32)
        val desktop = FluxIdentity.createMaterial(id)
        val phone = FluxIdentity.createMaterial("b".repeat(32))
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val serverResult = AtomicReference<Throwable?>()
            val listening = CountDownLatch(1)
            val release = CountDownLatch(1)
            val server = Thread {
                try {
                    listener.accept().use { raw ->
                        val secure = FluxIdentity.createTlsContext(desktop).socketFactory.createSocket(
                            raw, raw.inetAddress.hostAddress, raw.port, false) as javax.net.ssl.SSLSocket
                        secure.use { tls ->
                            tls.useClientMode = false
                            tls.needClientAuth = true
                            tls.enabledProtocols = arrayOf("TLSv1.2")
                            tls.startHandshake()
                            listening.countDown()
                            if (stopBeforePayload) { assertTrue(release.await(3, TimeUnit.SECONDS)) }
                            tls.outputStream.write(payload)
                            tls.outputStream.flush()
                        }
                    }
                } catch (e: Throwable) { serverResult.set(e); listening.countDown() }
            }.apply { isDaemon = true }
            val session = Any()
            val receiver = FluxIncomingFileReceiver(directory, session, { session }, { true },
                InetAddress.getLoopbackAddress(),
                if (wrongPin) phone.certificate.encoded else desktop.certificate.encoded,
                { FluxIdentity.createTlsContext(phone) })
            val token = receiver.offer(FluxIncomingFile("safe.txt", declared, listener.localPort))
            try {
                server.start()
                action(receiver, token, directory, listening)
                server.join(4000)
                assertFalse("server stuck", server.isAlive)
                if (!wrongPin && !stopBeforePayload) assertNull("server TLS failure", serverResult.get())
            } finally {
                release.countDown()
                receiver.revoke()
                listener.close()
                directory.deleteRecursively()
            }
        }
    }

    @Test fun realTlsPinAndExactPayloadStagePrivateFile() {
        exchange(byteArrayOf(1, 2, 3), 3) { receiver, token, _, _ ->
            val staged = receiver.accept(token)
            assertArrayEquals(byteArrayOf(1, 2, 3), staged.readBytes())
            assertEquals("flux-incoming-", staged.name.take(14))
            assertThrows(IllegalStateException::class.java) { receiver.accept(token) }
            staged.delete()
        }
    }

    @Test fun wrongDesktopCertificateCannotWriteStagedBytes() {
        exchange(byteArrayOf(1, 2, 3), 3, wrongPin = true) { receiver, token, directory, _ ->
            assertThrows(IllegalStateException::class.java) { receiver.accept(token) }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun truncatedAndOversizedTlsPayloadsLeaveNoPartialFile() {
        for (payload in listOf(byteArrayOf(1), byteArrayOf(1, 2, 3, 4))) {
            exchange(payload, 3) { receiver, token, directory, _ ->
                assertThrows(Exception::class.java) { receiver.accept(token) }
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            }
        }
    }

    @Test fun revokeInterruptsBlockedTlsReadAndDeletesPartialFile() {
        exchange(byteArrayOf(1, 2, 3), 3, stopBeforePayload = true) { receiver, token, directory, listening ->
            val failure = AtomicReference<Throwable?>()
            val worker = Thread { try { receiver.accept(token) } catch (e: Throwable) { failure.set(e) } }
            worker.start()
            assertTrue(listening.await(3, TimeUnit.SECONDS))
            receiver.revoke()
            worker.join(3000)
            assertFalse(worker.isAlive)
            assertNotNull(failure.get())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }
}
