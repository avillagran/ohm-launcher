package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class FluxScreenStopRegistryTest {
    @Test fun onlyOneActivityCanOwnCaptureAndOnlyItsOwnerCanReleaseIt() {
        val registry = FluxScreenStopRegistry { it() }
        val owner = Any()
        val id = checkNotNull(registry.register(owner) {})
        assertNull(registry.register(Any()) { fail("a second Activity must not capture") })
        assertFalse(registry.unregister(Any(), id))
        assertTrue(registry.isCurrent(id))
        assertTrue(registry.unregister(owner, id))
        assertNull(registry.currentId())
    }

    @Test fun queuedStopAndStaleMenuCannotStopReplacementCapture() {
        val dispatched = mutableListOf<() -> Unit>()
        val registry = FluxScreenStopRegistry { dispatched.add(it) }
        val oldOwner = Any()
        val replacement = Any()
        var oldClosed = 0
        var replacementClosed = 0
        var oldId = 0L
        oldId = checkNotNull(registry.register(oldOwner) {
            oldClosed++
            assertFalse(registry.unregister(oldOwner, oldId))
        })
        assertTrue(registry.stop(oldId))
        assertFalse(registry.stop(oldId))
        val newId = checkNotNull(registry.register(replacement) { replacementClosed++ })
        assertNotEquals(oldId, newId)
        dispatched.removeAt(0)()
        assertEquals(1, oldClosed)
        assertEquals(0, replacementClosed)
        assertTrue(registry.isCurrent(newId))
        assertFalse(registry.stop(oldId))
        assertTrue(registry.stop(newId))
        dispatched.removeAt(0)()
        assertEquals(1, replacementClosed)
    }

    @Test fun simultaneousNotificationStopAndProjectionEndConsumeOwnerAtMostOnce() {
        val registry = FluxScreenStopRegistry { it() }
        val owner = Any()
        val closes = AtomicInteger()
        val ready = CountDownLatch(3)
        val go = CountDownLatch(1)
        val id = checkNotNull(registry.register(owner) { closes.incrementAndGet() })
        val workers = List(3) { index -> Thread {
            ready.countDown()
            go.await()
            if (index == 2) registry.unregister(owner, id) else registry.stop(id)
        }.apply { start() } }
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            go.countDown()
            workers.forEach { it.join(2000); assertFalse(it.isAlive) }
            assertTrue(closes.get() in 0..1)
            assertNull(registry.currentId())
            assertFalse(registry.stop(id))
            val replacementId = checkNotNull(registry.register(Any()) {})
            assertFalse(registry.unregister(owner, id))
            assertTrue(registry.isCurrent(replacementId))
        } finally { go.countDown(); workers.forEach { it.join(2000) } }
    }

    @Test fun oldWireStopWaitingForControlWriterCannotStopReplacementOnSameSession() {
        val registry = FluxScreenStopRegistry { it() }
        val owner = Any()
        val oldId = checkNotNull(registry.register(owner) {})
        assertTrue(registry.unregister(owner, oldId))
        val serializer = Any()
        val waiting = CountDownLatch(1)
        val wrote = AtomicBoolean()
        val revoked = AtomicBoolean()
        val writer = Thread {
            waiting.countDown()
            try {
                FluxControlWriteGate.write(serializer, { registry.canSendStop(oldId) }) { wrote.set(true) }
            } catch (_: IllegalStateException) { revoked.set(true) }
        }
        synchronized(serializer) {
            writer.start()
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            val newId = checkNotNull(registry.register(Any()) {})
            assertFalse(registry.canSendStop(oldId))
            assertTrue(registry.isCurrent(newId))
        }
        writer.join(2000)
        assertFalse(writer.isAlive)
        assertFalse(wrote.get())
        assertTrue(revoked.get())
    }

    @Test fun queuedStartCannotAnnounceCaptureAfterConsentOwnerWasClosed() {
        val registry = FluxScreenStopRegistry { it() }
        val owner = Any()
        val id = checkNotNull(registry.register(owner) {})
        val serializer = Any()
        val waiting = CountDownLatch(1)
        val wrote = AtomicBoolean()
        val revoked = AtomicBoolean()
        val writer = Thread {
            waiting.countDown()
            try {
                FluxControlWriteGate.write(serializer, { registry.isCurrent(id) }) { wrote.set(true) }
            } catch (_: IllegalStateException) { revoked.set(true) }
        }
        synchronized(serializer) {
            writer.start()
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            assertTrue(registry.unregister(owner, id))
        }
        writer.join(2000)
        assertFalse(writer.isAlive)
        assertFalse(wrote.get())
        assertTrue(revoked.get())
    }

    @Test fun actionFromPreviousProcessCannotCloseCaptureInNewRegistry() {
        val oldProcess = FluxScreenStopRegistry(initialId = 1000L) { it() }
        val oldId = checkNotNull(oldProcess.register(Any()) {})
        val newProcess = FluxScreenStopRegistry(initialId = 2000L) { it() }
        var closed = false
        val newId = checkNotNull(newProcess.register(Any()) { closed = true })
        assertNotEquals(oldId, newId)
        assertFalse(newProcess.stop(oldId))
        assertTrue(newProcess.isCurrent(newId))
        assertFalse(closed)
    }

    @Test fun exhaustedIdRangeCannotWrapAndReuseOldPendingIntent() {
        val registry = FluxScreenStopRegistry(initialId = Long.MAX_VALUE - 1) { it() }
        val owner = Any()
        val id = checkNotNull(registry.register(owner) {})
        assertEquals(Long.MAX_VALUE, id)
        assertTrue(registry.unregister(owner, id))
        assertNull(registry.register(Any()) {})
        assertFalse(registry.stop(id))
    }
}
