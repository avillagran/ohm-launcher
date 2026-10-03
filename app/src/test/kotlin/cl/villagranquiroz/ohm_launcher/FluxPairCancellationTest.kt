package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class FluxPairCancellationTest {
    @Test fun bestEffortUnpairRunsOffCallerBeforeSessionClosure() {
        val cancellation = FluxPairCancellation(1, 1)
        val caller = Thread.currentThread()
        val sendThread = AtomicReference<Thread>()
        val sequence = Collections.synchronizedList(mutableListOf<String>())
        val closed = CountDownLatch(1)
        try {
            cancellation.cancel(send = {
                sendThread.set(Thread.currentThread())
                sequence.add("unpair")
            }, finished = { sent ->
                assertTrue(sent)
                sequence.add("close")
                closed.countDown()
            })
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertNotSame(caller, sendThread.get())
            assertEquals(listOf("unpair", "close"), sequence.toList())
        } finally { cancellation.shutdown() }
    }

    @Test fun deadlineRevokesAndInterruptsExistingWriterWhileUnpairWaitsForSerializer() {
        val cancellation = FluxPairCancellation(1, 1)
        val serializer = Any()
        val authorized = AtomicBoolean(true)
        val writing = CountDownLatch(1)
        val rawClosed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val sendEntered = CountDownLatch(1)
        val sendExited = CountDownLatch(1)
        val wroteUnpair = AtomicBoolean(false)
        val callbacks = AtomicInteger()
        val writer = Thread {
            synchronized(serializer) { writing.countDown(); rawClosed.await() }
        }
        writer.start()
        try {
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            cancellation.cancel(send = {
                sendEntered.countDown()
                try { FluxControlWriteGate.write(serializer, authorized::get) { wroteUnpair.set(true) } }
                finally { sendExited.countDown() }
            }, finished = { sent ->
                assertFalse(sent)
                authorized.set(false)
                callbacks.incrementAndGet()
                rawClosed.countDown()
                finished.countDown()
            }, timeoutMs = 250)
            assertTrue(sendEntered.await(2, TimeUnit.SECONDS))
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            writer.join(2000)
            assertTrue(sendExited.await(2, TimeUnit.SECONDS))
            assertFalse(writer.isAlive)
            assertFalse(authorized.get())
            assertFalse(wroteUnpair.get())
            assertEquals(1, callbacks.get())
        } finally { rawClosed.countDown(); writer.join(2000); cancellation.shutdown() }
    }

    @Test fun saturatedCancellationRejectsWithoutCallerNetworkIoAndExpiresQueuedWork() {
        val cancellation = FluxPairCancellation(1, 1)
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstExpired = CountDownLatch(1)
        val queuedExpired = CountDownLatch(1)
        val rejected = CountDownLatch(1)
        val unexpectedWrite = AtomicBoolean(false)
        try {
            cancellation.cancel({ writing.countDown(); release.await() }, { firstExpired.countDown() }, 100)
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            cancellation.cancel({ unexpectedWrite.set(true) }, { queuedExpired.countDown() }, 100)
            cancellation.cancel({ unexpectedWrite.set(true) }, {
                assertFalse(it)
                rejected.countDown()
            }, 100)
            assertEquals("saturated admission must return synchronously", 0L, rejected.count)
            assertTrue(firstExpired.await(2, TimeUnit.SECONDS))
            assertTrue(queuedExpired.await(2, TimeUnit.SECONDS))
            assertFalse(unexpectedWrite.get())
            release.countDown()
        } finally { release.countDown(); cancellation.shutdown() }
    }
}
