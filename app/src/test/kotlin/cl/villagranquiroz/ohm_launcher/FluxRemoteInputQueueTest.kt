package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FluxRemoteInputQueueTest {
    @Test fun stalledAbortCallbacksCannotBlockRevocationOrCreateUnlimitedWorkers() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(4)
        val completed = CountDownLatch(8)
        val revoked = AtomicInteger()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        try {
            repeat(12) {
                val queue = FluxRemoteInputQueue(send = {}, onRevoke = { revoked.incrementAndGet() },
                    onAbort = {
                        val count = active.incrementAndGet()
                        maximum.accumulateAndGet(count, ::maxOf)
                        entered.countDown()
                        try { release.await() }
                        finally { active.decrementAndGet(); completed.countDown() }
                    })
                assertTrue(queue.accepted)
                queue.close()
            }
            assertEquals(12, revoked.get())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(maximum.get() <= 4)
        } finally { release.countDown() }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
    }
    @Test fun stalledSendersHaveGlobalAdmissionBoundAndRecover() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(4)
        val senders = (1..4).map {
            FluxRemoteInputQueue(send = {
                entered.countDown()
                release.await()
            }, onAbort = {})
        }
        try {
            assertTrue(senders.all { it.accepted })
            senders.forEach { assertTrue(it.offer(FluxRemoteInputProtocol.move(1.0, 1.0))) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val rejected = FluxRemoteInputQueue(send = {}, onAbort = {})
            assertFalse(rejected.accepted)
            assertFalse(rejected.offer(FluxRemoteInputProtocol.move(1.0, 1.0)))
        } finally {
            senders.forEach { it.close() }
            release.countDown()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var recovered: FluxRemoteInputQueue? = null
        while (System.nanoTime() < deadline && recovered == null) {
            FluxRemoteInputQueue(send = {}, onAbort = {}).let {
                if (it.accepted) recovered = it else Thread.sleep(10)
            }
        }
        assertTrue(recovered != null)
        recovered?.close()
    }
    private val protocol = FluxRemoteInputProtocol
    private fun fields(action: FluxRemoteInputProtocol.Action) = action.body()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("timed out waiting for dispatcher", condition())
    }

    @Test fun motionSaturationIsBoundedAndControlsKeepTheirOrder() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val sent = CopyOnWriteArrayList<FluxRemoteInputProtocol.Action>()
        val aborts = AtomicInteger()
        val queue = FluxRemoteInputQueue(send = {
            if (fields(it).has("singleclick")) { entered.countDown(); unblock.await(3, TimeUnit.SECONDS) }
            sent.add(it)
        }, onAbort = { aborts.incrementAndGet() })
        try {
            assertTrue(queue.offer(protocol.click(FluxRemoteInputProtocol.Click.LEFT)))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(1000) { assertTrue(queue.offer(protocol.move(1.0, 1.0))) }
            assertTrue(queue.offer(protocol.hold()))
            assertTrue(queue.offer(protocol.scroll(2.0, 3.0)))
            assertTrue(queue.offer(protocol.release()))
            assertTrue(queue.pendingCount <= FluxRemoteInputQueue.MAX_PENDING)
            unblock.countDown()
            await { sent.size == 5 }
            assertEquals(1000.0, fields(sent[1]).getDouble("dx"), 0.0)
            assertTrue(fields(sent[2]).has("singlehold"))
            assertTrue(fields(sent[3]).has("scroll"))
            assertTrue(fields(sent[4]).has("singlerelease"))
        } finally { unblock.countDown(); queue.close() }
    }

    @Test fun fullControlQueueRejectsNewControlsWithoutReorderingAcceptedOnes() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val sent = CopyOnWriteArrayList<FluxRemoteInputProtocol.Action>()
        val queue = FluxRemoteInputQueue(send = {
            if (fields(it).has("singlehold")) { entered.countDown(); unblock.await(3, TimeUnit.SECONDS) }
            sent.add(it)
        }, onAbort = {})
        try {
            assertTrue(queue.offer(protocol.hold()))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(FluxRemoteInputQueue.MAX_PENDING - 1) {
                assertTrue(queue.offer(protocol.special(FluxRemoteInputProtocol.SpecialKey.ENTER)))
            }
            assertFalse(queue.offer(protocol.click(FluxRemoteInputProtocol.Click.RIGHT)))
            assertTrue(queue.pendingCount <= FluxRemoteInputQueue.MAX_PENDING)
            unblock.countDown()
            await { sent.size == FluxRemoteInputQueue.MAX_PENDING }
            assertTrue(sent.drop(1).all { fields(it).has("specialKey") })
        } finally { unblock.countDown(); queue.close() }
    }

    @Test fun releaseUsesReservedSlotWhenControlsSaturateQueue() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val sent = CopyOnWriteArrayList<FluxRemoteInputProtocol.Action>()
        val queue = FluxRemoteInputQueue(send = {
            if (fields(it).has("singlehold")) { entered.countDown(); unblock.await(3, TimeUnit.SECONDS) }
            sent.add(it)
        }, onAbort = {})
        try {
            assertTrue(queue.offer(protocol.hold()))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(FluxRemoteInputQueue.MAX_PENDING - 1) {
                assertTrue(queue.offer(protocol.special(FluxRemoteInputProtocol.SpecialKey.ENTER)))
            }
            assertTrue(queue.offer(protocol.release()))
            assertEquals(FluxRemoteInputQueue.MAX_PENDING, queue.pendingCount)
            unblock.countDown()
            await { sent.size == FluxRemoteInputQueue.MAX_PENDING + 1 }
            assertTrue(fields(sent.last()).has("singlerelease"))
        } finally { unblock.countDown(); queue.close() }
    }

    @Test fun closePrioritizesReleaseOverQueuedWorkThenRevokes() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val sent = CopyOnWriteArrayList<FluxRemoteInputProtocol.Action>()
        val aborted = CountDownLatch(1)
        val queue = FluxRemoteInputQueue(send = {
            sent.add(it)
            if (fields(it).has("singlehold")) { entered.countDown(); unblock.await(3, TimeUnit.SECONDS) }
        }, onAbort = { aborted.countDown() })
        try {
            assertTrue(queue.offer(protocol.hold()))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(100) { queue.offer(protocol.move(1.0, 1.0)) }
            queue.close()
            assertFalse(queue.offer(protocol.release()))
            unblock.countDown()
            assertTrue(aborted.await(2, TimeUnit.SECONDS))
            assertEquals(2, sent.size)
            assertTrue(fields(sent[1]).has("singlerelease"))
        } finally { unblock.countDown(); queue.close() }
    }

    @Test fun stalledWriterCannotBlockCloseOrRevocation() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val queue = FluxRemoteInputQueue(send = {
            entered.countDown(); unblock.await(5, TimeUnit.SECONDS)
        }, onAbort = { aborted.countDown() }, closeGraceMs = 50)
        try {
            assertTrue(queue.offer(protocol.hold()))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val start = System.nanoTime()
            queue.close()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500)
            assertTrue("stalled writer must be revoked", aborted.await(2, TimeUnit.SECONDS))
            assertFalse(queue.offer(protocol.move(1.0, 1.0)))
        } finally { unblock.countDown(); queue.close() }
    }

    @Test fun failedWriteRevokesAndDropsQueuedActions() {
        val aborted = CountDownLatch(1)
        val calls = AtomicInteger()
        val queue = FluxRemoteInputQueue(send = { calls.incrementAndGet(); throw IllegalStateException("revoked") },
            onAbort = { aborted.countDown() })
        assertTrue(queue.offer(protocol.hold()))
        assertTrue(aborted.await(2, TimeUnit.SECONDS))
        assertFalse(queue.offer(protocol.release()))
        assertEquals(1, calls.get())
    }
}
