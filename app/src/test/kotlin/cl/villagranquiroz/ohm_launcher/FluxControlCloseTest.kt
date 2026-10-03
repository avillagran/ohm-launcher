package cl.villagranquiroz.ohm_launcher

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class FluxControlCloseTest {
    @Test fun mainThreadClosureRevokesBeforeCleanupAndInterruptsWriterWhileTlsCloseBlocks() {
        val cleanup = FluxControlCleanup(1, 1)
        val authority = AtomicBoolean(true)
        val networkOnMain = AtomicBoolean(false)
        val rawClosed = CountDownLatch(1)
        val tlsEntered = CountDownLatch(1)
        val releaseTls = CountDownLatch(1)
        val revokeCalls = AtomicInteger()
        val controller = FluxControlClose(Closeable {
            if (Looper.myLooper() == Looper.getMainLooper()) networkOnMain.set(true)
            assertFalse(authority.get())
            rawClosed.countDown()
        }, Closeable {
            if (Looper.myLooper() == Looper.getMainLooper()) networkOnMain.set(true)
            assertFalse(authority.get())
            tlsEntered.countDown()
            releaseTls.await()
        }, cleanup)
        val writerHoldsMonitor = CountDownLatch(1)
        val writerExited = CountDownLatch(1)
        val serializer = Any()
        val writer = Thread {
            synchronized(serializer) {
                writerHoldsMonitor.countDown()
                rawClosed.await()
            }
            writerExited.countDown()
        }
        writer.start()
        try {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            assertTrue(writerHoldsMonitor.await(2, TimeUnit.SECONDS))
            assertTrue(controller.close {
                assertTrue(controller.isClosed)
                authority.set(false)
                revokeCalls.incrementAndGet()
            })
            assertTrue(controller.isClosed)
            assertFalse(authority.get())
            assertTrue(tlsEntered.await(2, TimeUnit.SECONDS))
            assertTrue("raw close must interrupt a writer independently of stalled TLS cleanup",
                writerExited.await(2, TimeUnit.SECONDS))
            assertFalse(networkOnMain.get())
            assertTrue(controller.close { revokeCalls.incrementAndGet() })
            assertEquals(1, revokeCalls.get())
        } finally {
            rawClosed.countDown()
            releaseTls.countDown()
            writer.join(2000)
            cleanup.shutdown()
        }
    }

    @Test fun saturatedCleanupHasBoundedAdmissionAndNeverClosesOnCallerThread() {
        val cleanup = FluxControlCleanup(1, 1)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val callerClose = AtomicBoolean(false)
        val completed = CountDownLatch(2)
        val blocked = Closeable { entered.countDown(); release.await() }
        try {
            assertTrue(cleanup.raw(blocked))
            assertTrue(cleanup.secure(blocked))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(cleanup.raw(Closeable { completed.countDown() }))
            assertTrue(cleanup.secure(Closeable { completed.countDown() }))
            assertFalse(cleanup.raw(Closeable { callerClose.set(true) }))
            assertFalse(cleanup.secure(Closeable { callerClose.set(true) }))
            assertFalse(callerClose.get())
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertFalse(callerClose.get())
        } finally { release.countDown(); cleanup.shutdown() }
    }
}
