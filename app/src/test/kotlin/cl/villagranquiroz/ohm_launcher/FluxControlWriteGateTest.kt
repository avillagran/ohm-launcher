package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class FluxControlWriteGateTest {
    @Test fun revocationWhileBasicActionWaitsForSerializerPreventsItsWrite() {
        val serializer = Any()
        val authorized = AtomicBoolean(true)
        val priorWriteEntered = CountDownLatch(1)
        val releasePriorWrite = CountDownLatch(1)
        val queued = CountDownLatch(1)
        val wrote = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val priorWriter = Thread {
            synchronized(serializer) {
                priorWriteEntered.countDown()
                releasePriorWrite.await()
            }
        }
        val queuedWriter = Thread {
            assertTrue(authorized.get()) // The UI's earlier check does not authorize a later write.
            queued.countDown()
            try { FluxControlWriteGate.write(serializer, authorized::get) { wrote.set(true) } }
            catch (error: Exception) { failure.set(error) }
        }
        priorWriter.start()
        try {
            assertTrue(priorWriteEntered.await(2, TimeUnit.SECONDS))
            queuedWriter.start()
            assertTrue(queued.await(2, TimeUnit.SECONDS))
            authorized.set(false)
            releasePriorWrite.countDown()
            priorWriter.join(2000)
            queuedWriter.join(2000)
            assertFalse(priorWriter.isAlive)
            assertFalse(queuedWriter.isAlive)
            assertFalse(wrote.get())
            assertTrue(failure.get() is IllegalStateException)
        } finally {
            releasePriorWrite.countDown()
            priorWriter.join(2000)
            queuedWriter.join(2000)
        }
    }
}
