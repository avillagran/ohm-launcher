package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FluxThemePickerClosureTest {
    @Test fun alreadyPendingCloseAfterClosesOriginalSessionOnceEvenIfCancelAlsoArrives() {
        val closes = AtomicInteger()
        val original = Any()
        val closure = FluxThemePickerClosure(original, true) { owner ->
            assertSame(original, owner)
            closes.incrementAndGet()
        }
        closure.finish() // Already-pending rejection.
        closure.finish() // Dialog's late cancel/dismiss event.
        assertEquals(1, closes.get())
    }

    @Test fun finalAuthorizationFailureClosesOnlyCapturedSessionOnceAcrossLateWorkerFailure() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val original = Any()
        val replacement = Any()
        val closed = mutableListOf<Any>()
        val closure = FluxThemePickerClosure(original, true) { owner ->
            synchronized(closed) { closed.add(owner) }
            closes.incrementAndGet()
        }
        val worker = Thread { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); closure.finish() }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // Authorization changed while the worker was waiting: reject on the UI thread.
            closure.finish()
            release.countDown()
            worker.join(5000)
            assertEquals(1, closes.get())
            assertEquals(listOf(original), synchronized(closed) { closed.toList() })
            assertNotSame(replacement, closed.single())
        } finally { release.countDown(); worker.join(5000) }
    }
}
