package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.*
import org.junit.Test
import java.util.PriorityQueue

class FluxFilePickerRecoveryTest {
    private data class Link(val id: String, val cert: ByteArray, var paired: Boolean = true,
                            var open: Boolean = true, var live: Boolean = true)
    private class Clock : FluxFilePickerRecovery.Scheduler {
        private data class Task(val at: Long, val order: Long, val run: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        private var order = 0L
        private val tasks = PriorityQueue<Task>(compareBy<Task> { it.at }.thenBy { it.order })
        override fun execute(task: () -> Unit) { schedule(0, task) }
        override fun schedule(delayMs: Long, task: () -> Unit): FluxFilePickerRecovery.Cancel {
            val entry = Task(now + delayMs, order++, task)
            tasks.add(entry)
            return FluxFilePickerRecovery.Cancel { entry.cancelled = true }
        }
        fun advance(ms: Long) {
            val until = now + ms
            while (tasks.peek()?.at?.let { it <= until } == true) {
                val task = tasks.remove()
                now = task.at
                if (!task.cancelled) task.run()
            }
            now = until
        }
    }
    private class Fixture {
        val clock = Clock()
        val a = Link("desk", byteArrayOf(1, 2))
        val sessions = mutableListOf(a)
        var allowed = true
        val sent = mutableListOf<Link>()
        val failures = mutableListOf<FluxFilePickerRecovery.Failure>()
        val recovery = FluxFilePickerRecovery(clock, { allowed }, { sessions.toList() },
            { it.id }, { it.cert }, { it.paired }, { it.open }, { it.live })
        fun complete(token: FluxFilePickerRecovery.Token) =
            recovery.complete(token, { sent.add(it) }, { failures.add(it) })
    }

    @Test fun disconnectedOwnerResolvesOnlyLiveSamePeerAndCertificateAfterReconnect() {
        val f = Fixture()
        val cert = f.a.cert
        val token = f.recovery.begin(f.a.id, cert)
        cert[0] = 9 // begin must own a copy
        f.a.open = false
        f.complete(token)
        f.clock.advance(0)
        assertTrue(f.sent.isEmpty())
        f.sessions.add(Link("other", byteArrayOf(1, 2)))
        f.sessions.add(Link("desk", byteArrayOf(9, 2)))
        f.recovery.signal()
        f.clock.advance(0)
        assertTrue(f.sent.isEmpty())
        val b = Link("desk", byteArrayOf(1, 2))
        f.sessions.add(b)
        f.recovery.signal()
        f.clock.advance(0)
        assertEquals(listOf(b), f.sent)
        f.clock.advance(30_000)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun incompleteOrIneligibleSessionsCannotDispatch() {
        val f = Fixture()
        val token = f.recovery.begin("desk", byteArrayOf(1, 2))
        f.recovery.signal()
        f.clock.advance(0)
        assertTrue(f.sent.isEmpty())
        f.a.paired = false
        f.complete(token)
        f.clock.advance(0)
        assertTrue(f.sent.isEmpty())
        f.a.paired = true
        f.a.live = false
        f.recovery.signal()
        f.clock.advance(0)
        assertTrue(f.sent.isEmpty())
        f.a.live = true
        f.recovery.signal()
        f.clock.advance(0)
        assertEquals(listOf(f.a), f.sent)
        assertFalse(f.complete(token))
        f.recovery.signal()
        f.clock.advance(30_001)
        assertEquals(1, f.sent.size)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun forgetInvalidatesEvenAfterRepinningSameCertificate() {
        val f = Fixture()
        f.a.open = false
        val token = f.recovery.begin("desk", byteArrayOf(1, 2))
        f.complete(token)
        f.clock.advance(0)
        f.recovery.revokePeer("desk")
        f.sessions.add(Link("desk", byteArrayOf(1, 2)))
        f.recovery.signal()
        f.clock.advance(30_001)
        assertTrue(f.sent.isEmpty())
        assertEquals(listOf(FluxFilePickerRecovery.Failure.REVOKED), f.failures)
        assertFalse(f.complete(token))
        val next = f.recovery.begin("desk", byteArrayOf(1, 2))
        assertEquals(token.generation + 1, next.generation)
        f.complete(next)
        f.clock.advance(0)
        assertEquals(1, f.sent.size)
    }

    @Test fun timeoutCannotDispatchLateReconnect() {
        val f = Fixture()
        f.a.open = false
        val token = f.recovery.begin("desk", byteArrayOf(1, 2))
        f.complete(token)
        f.clock.advance(29_999)
        assertTrue(f.failures.isEmpty())
        f.clock.advance(1)
        f.a.open = true
        f.recovery.signal()
        f.clock.advance(0)
        assertEquals(listOf(FluxFilePickerRecovery.Failure.TIMED_OUT), f.failures)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun cancellationAndDestroyAreTerminalAndOneShot() {
        val f = Fixture()
        val first = f.recovery.begin("desk", byteArrayOf(1, 2))
        assertThrows(IllegalStateException::class.java) { f.recovery.begin("desk", byteArrayOf(1, 2)) }
        f.recovery.cancel(first)
        f.complete(first)
        assertFalse(f.complete(first))
        f.clock.advance(0)
        assertTrue(f.sent.isEmpty())
        val second = f.recovery.begin("desk", byteArrayOf(1, 2))
        f.a.open = false
        f.complete(second)
        f.clock.advance(0)
        f.recovery.destroy()
        f.a.open = true
        f.recovery.signal()
        f.clock.advance(30_000)
        assertEquals(listOf(FluxFilePickerRecovery.Failure.DESTROYED), f.failures)
        assertTrue(f.sent.isEmpty())
        assertThrows(IllegalStateException::class.java) { f.recovery.begin("desk", byteArrayOf(1, 2)) }
    }

    @Test fun editionChangeRejectsPendingTransfer() {
        val f = Fixture()
        f.a.open = false
        val token = f.recovery.begin("desk", byteArrayOf(1, 2))
        f.complete(token)
        f.clock.advance(0)
        f.allowed = false
        f.a.open = true
        f.recovery.signal()
        f.clock.advance(0)
        assertEquals(listOf(FluxFilePickerRecovery.Failure.WRONG_EDITION), f.failures)
        assertTrue(f.sent.isEmpty())
        assertThrows(IllegalStateException::class.java) { f.recovery.begin("desk", byteArrayOf(1, 2)) }
    }

    @Test fun revocationDuringCandidateLookupPreventsDispatch() {
        val clock = Clock()
        val link = Link("desk", byteArrayOf(4))
        lateinit var recovery: FluxFilePickerRecovery<Link>
        var revokeOnLookup = false
        val sent = mutableListOf<Link>()
        val failure = mutableListOf<FluxFilePickerRecovery.Failure>()
        recovery = FluxFilePickerRecovery(clock, { true }, {
            if (revokeOnLookup) recovery.revokePeer("desk")
            listOf(link)
        }, { it.id }, { it.cert }, { it.paired }, { it.open }, { it.live })
        val token = recovery.begin("desk", byteArrayOf(4))
        revokeOnLookup = true
        recovery.complete(token, { sent.add(it) }, { failure.add(it) })
        clock.advance(0)
        assertTrue(sent.isEmpty())
        assertEquals(listOf(FluxFilePickerRecovery.Failure.REVOKED), failure)
    }

    @Test fun tokenNeverPrintsCertificate() {
        val f = Fixture()
        val token = f.recovery.begin("desk", "CERT_SECRET".toByteArray())
        assertFalse(token.toString().contains("CERT_SECRET"))
        f.recovery.cancel(token)
    }
}
