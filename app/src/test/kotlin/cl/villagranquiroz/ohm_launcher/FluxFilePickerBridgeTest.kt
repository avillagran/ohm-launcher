package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.*
import org.junit.Test
import java.util.PriorityQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FluxFilePickerBridgeTest {
    private data class Link(val id: String, val cert: ByteArray, var paired: Boolean = true,
                            var open: Boolean = true, var closed: Int = 0)
    private class Clock : FluxFilePickerRecovery.Scheduler {
        private data class Job(val time: Long, val order: Long, val task: () -> Unit, var cancelled: Boolean = false)
        private val queue = PriorityQueue<Job>(compareBy<Job> { it.time }.thenBy { it.order })
        var now = 0L
        private var order = 0L
        override fun execute(task: () -> Unit) { schedule(0, task) }
        override fun schedule(delayMs: Long, task: () -> Unit): FluxFilePickerRecovery.Cancel {
            val job = Job(now + delayMs, order++, task)
            queue.add(job)
            return FluxFilePickerRecovery.Cancel { job.cancelled = true }
        }
        fun advance(ms: Long = 0) {
            val end = now + ms
            while (queue.peek()?.time?.let { it <= end } == true) {
                val job = queue.remove()
                now = job.time
                if (!job.cancelled) job.task()
            }
            now = end
        }
    }
    private class Fixture {
        val clock = Clock()
        val a = Link("desk", byteArrayOf(1, 2))
        val mapped = mutableMapOf("desk" to a)
        val sent = mutableListOf<Pair<Link, String>>()
        val results = mutableListOf<Result<Unit>>()
        val errors = mutableListOf<FluxFilePickerRecovery.Failure>()
        val recovery = FluxFilePickerRecovery(clock, { true }, { mapped.values.toList() },
            { it.id }, { it.cert }, { it.paired }, { it.open }, { mapped[it.id] === it })
        val bridge = FluxFilePickerBridge<Link, String>(recovery, { it.id }, { it.cert },
            { it.closed++ }, { session, uri, finished ->
                check(mapped[session.id] === session && session.paired && session.open) { "stale transfer gate" }
                sent.add(session to uri)
                finished(Result.success(Unit))
            }, { results.add(it) }, { errors.add(it) }, closeExecutor = { task -> task() })
        var current: FluxFilePickerBridge.Launch? = null
        fun launch(owner: Link, closeAfter: Boolean): FluxFilePickerBridge.Launch? =
            bridge.launch(owner, closeAfter).also { if (it != null) current = it }
        fun returned(uri: String?) = bridge.returned(requireNotNull(current), uri)
    }

    @Test fun lateDuplicateFromCompletedLaunchCannotDeliverToNextPeer() {
        val f = Fixture()
        val b = Link("other", byteArrayOf(3))
        f.mapped[b.id] = b
        val first = requireNotNull(f.launch(f.a, closeAfter = false))
        f.bridge.returned(first, "content://a")
        f.clock.advance()
        val second = requireNotNull(f.launch(b, closeAfter = false))
        f.bridge.returned(first, "content://a-duplicate")
        f.clock.advance()
        assertEquals(listOf(f.a to "content://a"), f.sent)
        f.bridge.returned(second, "content://b")
        f.clock.advance()
        assertEquals(listOf(f.a to "content://a", b to "content://b"), f.sent)
    }

    @Test fun pickerReturnUsesReplacementOnlyAndDoesNotCloseIt() {
        val f = Fixture()
        assertNotNull(f.launch(f.a, closeAfter = true))
        f.a.open = false
        f.mapped.remove("desk")
        f.bridge.disconnected()
        f.returned("content://picked")
        f.clock.advance()
        assertTrue(f.sent.isEmpty())
        val wrong = Link("desk", byteArrayOf(9, 2))
        f.mapped["desk"] = wrong
        f.bridge.registered()
        f.clock.advance()
        assertTrue(f.sent.isEmpty())
        val b = Link("desk", byteArrayOf(1, 2))
        f.mapped["desk"] = b
        f.bridge.registered()
        f.clock.advance()
        assertEquals(listOf(b to "content://picked"), f.sent)
        assertEquals(1, f.results.size)
        assertEquals(1, f.a.closed)
        assertEquals(0, b.closed)
        assertEquals(0, wrong.closed)
        f.clock.advance(30_000)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun forgetThenRepairCannotSendThePickedUri() {
        val f = Fixture()
        assertNotNull(f.launch(f.a, closeAfter = true))
        f.a.open = false
        f.returned("content://picked")
        f.clock.advance()
        f.bridge.revoked("desk")
        f.mapped["desk"] = Link("desk", byteArrayOf(1, 2))
        f.bridge.registered()
        f.clock.advance()
        assertTrue(f.sent.isEmpty())
        assertEquals(listOf(FluxFilePickerRecovery.Failure.REVOKED), f.errors)
        assertEquals(1, f.a.closed)
        assertEquals(0, f.mapped.getValue("desk").closed)
    }

    @Test fun timeoutClearsBusyStateAndNeverClosesUnrelatedLink() {
        val f = Fixture()
        assertNotNull(f.launch(f.a, closeAfter = true))
        f.a.open = false
        f.mapped.remove("desk")
        f.returned("content://picked")
        f.clock.advance(30_000)
        assertEquals(listOf(FluxFilePickerRecovery.Failure.TIMED_OUT), f.errors)
        assertEquals(1, f.a.closed)
        assertNotNull(f.launch(Link("other", byteArrayOf(3)), closeAfter = false))
        f.returned(null)
        f.clock.advance()
        assertTrue(f.sent.isEmpty())
    }

    @Test fun timedOutLaunchCannotCaptureNextPeerCallback() {
        val f = Fixture()
        val aLaunch = requireNotNull(f.launch(f.a, closeAfter = false))
        f.a.open = false
        f.returned("content://a")
        f.clock.advance(30_000)
        val b = Link("other", byteArrayOf(3))
        f.mapped[b.id] = b
        val bLaunch = requireNotNull(f.launch(b, closeAfter = false))
        f.bridge.returned(aLaunch, "content://a-late")
        f.clock.advance()
        assertTrue(f.sent.isEmpty())
        f.bridge.returned(bLaunch, "content://b")
        f.clock.advance()
        assertEquals(listOf(b to "content://b"), f.sent)
    }

    @Test fun destroyingPickerPreventsLateUriDispatch() {
        val f = Fixture()
        assertNotNull(f.launch(f.a, closeAfter = true))
        f.bridge.destroy()
        f.returned("content://picked")
        f.clock.advance()
        assertTrue(f.sent.isEmpty())
        assertEquals(1, f.a.closed)
        assertEquals(listOf(FluxFilePickerRecovery.Failure.DESTROYED), f.errors)
    }

    @Test fun cancelledPickerDoesNotClosePersistentPeer() {
        val f = Fixture()
        assertNotNull(f.launch(f.a, closeAfter = false))
        f.returned(null)
        f.clock.advance()
        assertTrue(f.sent.isEmpty())
        assertEquals(0, f.a.closed)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun forgetBeforePickerReturnDiscardsItsUriEvenAfterRepair() {
        val f = Fixture()
        assertNotNull(f.launch(f.a, closeAfter = true))
        f.mapped.remove("desk")
        f.bridge.revoked("desk")
        f.mapped["desk"] = Link("desk", byteArrayOf(1, 2))
        f.bridge.registered()
        f.returned("content://old-picker")
        f.clock.advance()
        assertTrue(f.sent.isEmpty())
        assertEquals(listOf(FluxFilePickerRecovery.Failure.REVOKED), f.errors)
        assertEquals(1, f.a.closed)
        assertEquals(0, f.mapped.getValue("desk").closed)
    }

    @Test fun revokedPickerMustDrainItsOwnCallbackBeforeAnotherLaunch() {
        val f = Fixture()
        val b = Link("other", byteArrayOf(3))
        f.mapped[b.id] = b
        val aLaunch = requireNotNull(f.launch(f.a, closeAfter = true))
        f.bridge.revoked(f.a.id)
        assertNull("A's still-open DocumentsUI owns the next callback", f.launch(b, closeAfter = false))
        f.returned("content://a-private-file")
        assertNotNull(f.launch(b, closeAfter = false))
        f.bridge.returned(aLaunch, "content://a-late-duplicate")
        f.clock.advance()
        assertTrue(f.sent.isEmpty())
        f.returned("content://b-file")
        f.clock.advance()
        assertEquals(listOf(b to "content://b-file"), f.sent)
    }

    @Test fun blockedCloseCannotHoldRevocationCallerOrPickerCallback() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bridge = FluxFilePickerBridge<Link, String>(f.recovery, { it.id }, { it.cert },
            { entered.countDown(); release.await() }, { _, _, _ -> error("revoked selection must not submit") },
            { f.results.add(it) }, { f.errors.add(it) })
        val launch = requireNotNull(bridge.launch(f.a, closeAfter = true))
        val caller = Thread { bridge.revoked(f.a.id) }.apply { start() }
        try {
            assertTrue("close must run, but not on revocation caller", entered.await(2, TimeUnit.SECONDS))
            caller.join(200)
            assertFalse("revocation caller blocked in socket close", caller.isAlive)
            assertNull(bridge.launch(Link("other", byteArrayOf(3)), closeAfter = false))
            bridge.returned(launch, "content://revoked")
            f.clock.advance()
            assertNotNull(bridge.launch(Link("other", byteArrayOf(3)), closeAfter = false))
            assertEquals(listOf(FluxFilePickerRecovery.Failure.REVOKED), f.errors)
        } finally {
            release.countDown()
            caller.join(2_000)
        }
    }

    @Test fun blockedCloseCannotStarveRecoveryTimeoutScheduler() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bridge = FluxFilePickerBridge<Link, String>(f.recovery, { it.id }, { it.cert },
            { entered.countDown(); release.await() }, { _, _, _ -> error("disconnected selection must not submit") },
            { f.results.add(it) }, { f.errors.add(it) })
        val launch = requireNotNull(bridge.launch(f.a, closeAfter = true))
        f.a.open = false
        bridge.returned(launch, "content://waiting")
        val scheduler = Thread { f.clock.advance(30_000) }.apply { start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            scheduler.join(200)
            assertFalse("recovery scheduler blocked in socket close", scheduler.isAlive)
            assertEquals(listOf(FluxFilePickerRecovery.Failure.TIMED_OUT), f.errors)
        } finally {
            release.countDown()
            scheduler.join(2_000)
        }
    }

    @Test fun recreatedActivityCannotApplyOldLaunchToNewSelection() {
        val f = Fixture()
        val old = requireNotNull(f.launch(f.a, closeAfter = true))
        f.bridge.destroy()
        val f2 = Fixture()
        val fresh = requireNotNull(f2.launch(f2.a, closeAfter = false))
        f2.bridge.returned(old, "content://prior-activity")
        f2.clock.advance()
        assertTrue(f2.sent.isEmpty())
        f2.bridge.returned(fresh, "content://new-activity")
        f2.clock.advance()
        assertEquals(listOf(f2.a to "content://new-activity"), f2.sent)
    }

    @Test fun transferGateRevokesBeforeDeferredCloseEvenForRejectedOwner() {
        val f = Fixture()
        val closing = mutableListOf<() -> Unit>()
        val revoked = mutableListOf<Link>()
        val bridge = FluxFilePickerBridge<Link, String>(f.recovery, { it.id }, { it.cert },
            { it.closed++ }, { _, _, _ -> error("not selected") }, { f.results.add(it) },
            { f.errors.add(it) }, revoke = { revoked.add(it) }, closeExecutor = { closing.add(it) })
        val b = Link("other", byteArrayOf(3))
        assertNotNull(bridge.launch(f.a, closeAfter = true))
        assertNull(bridge.launch(b, closeAfter = true))
        bridge.reject(b, closeAfter = true)
        assertEquals(listOf(b), revoked)
        assertEquals(0, b.closed)
        assertEquals(1, closing.size)
        bridge.revoked(f.a.id)
        assertEquals(listOf(b, f.a), revoked)
        assertEquals(0, f.a.closed)
        closing.forEach { it() }
        assertEquals(1, b.closed)
        assertEquals(1, f.a.closed)
    }

}
