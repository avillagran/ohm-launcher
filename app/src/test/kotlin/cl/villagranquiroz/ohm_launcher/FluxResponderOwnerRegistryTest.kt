package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FluxResponderOwnerRegistryTest {
    private class Owner(var lease: FluxResponderLease? = null, var granted: Int = 0, var revoked: Int = 0)
    private fun claim(registry: FluxResponderOwnerRegistry, owner: Owner, home: Boolean) {
        registry.request(owner, home, { owner.lease = it; owner.granted++ }, { owner.revoked++ })
    }

    @Test fun homeTakesFirstLauncherListenerAndStandardTasksCannotStealIt() {
        val registry = FluxResponderOwnerRegistry { false }
        val launcher = Owner()
        val home = Owner()
        val secondary = Owner()
        claim(registry, launcher, false)
        val old = checkNotNull(launcher.lease)
        claim(registry, home, true)
        assertFalse(old.active)
        assertEquals(1, launcher.revoked)
        assertTrue(registry.isCurrent(home, checkNotNull(home.lease)))
        claim(registry, secondary, false)
        claim(registry, launcher, false)
        claim(registry, home, false) // A later launcher/deep-link intent cannot undo HOME status.
        assertEquals(1, home.granted)
        assertEquals(0, secondary.granted)
        assertTrue(registry.isCurrent(home, checkNotNull(home.lease)))
    }

    @Test fun pendingScreenConsentAndCaptureKeepOwnerUntilEndThenPromoteHome() {
        var pinned = false
        val registry = FluxResponderOwnerRegistry { pinned }
        val original = Owner()
        val home = Owner()
        val selectorActivity = Owner()
        claim(registry, original, false)
        pinned = true
        claim(registry, home, true)
        claim(registry, selectorActivity, false)
        assertTrue(registry.isCurrent(original, checkNotNull(original.lease)))
        assertEquals(0, home.granted)
        pinned = false
        registry.reconsider()
        assertTrue(registry.isCurrent(home, checkNotNull(home.lease)))
        assertEquals(1, original.revoked)
        assertEquals(0, selectorActivity.granted)
    }

    @Test fun destroyedDeferredHomeCannotReceiveListenerWhenCaptureEnds() {
        var pinned = false
        val registry = FluxResponderOwnerRegistry { pinned }
        val home = Owner()
        val deferred = Owner()
        claim(registry, home, true)
        pinned = true
        claim(registry, deferred, true)
        registry.release(deferred)
        pinned = false
        registry.reconsider()
        assertEquals(0, deferred.granted)
        assertEquals(0, home.revoked)
        assertTrue(registry.isCurrent(home, checkNotNull(home.lease)))
    }

    @Test fun destroyingListenerOwnerWhileOtherCaptureIsPinnedWaitsForCaptureEnd() {
        var pinned = false
        val registry = FluxResponderOwnerRegistry { pinned }
        val owner = Owner()
        val replacement = Owner()
        claim(registry, owner, true)
        pinned = true
        claim(registry, replacement, true)
        registry.release(owner)
        assertFalse(checkNotNull(owner.lease).active)
        assertEquals(0, replacement.granted)
        pinned = false
        registry.reconsider()
        assertTrue(registry.isCurrent(replacement, checkNotNull(replacement.lease)))
    }

    @Test fun delayedFactoryCannotAttachOrAdmitSessionsAfterOwnershipReplacement() {
        val registry = FluxResponderOwnerRegistry { false }
        val original = Owner()
        val home = Owner()
        claim(registry, original, false)
        val delayed = checkNotNull(original.lease)
        claim(registry, home, true)
        var closed = 0
        val constructed = Closeable { closed++ }
        if (!delayed.attach(constructed)) constructed.close()
        var trustWrites = 0
        if (registry.isCurrent(original, delayed)) trustWrites++
        assertEquals(1, closed)
        assertEquals(0, trustWrites)
        assertFalse(delayed.active)
    }

    @Test fun attachedResponderClosesBeforeReplacementStartsAndOutsideRegistryLock() {
        val registry = FluxResponderOwnerRegistry { false }
        val original = Owner()
        val home = Owner()
        val unrelated = Owner()
        claim(registry, original, false)
        val old = checkNotNull(original.lease)
        val closed = CountDownLatch(1)
        val unlocked = AtomicBoolean()
        assertTrue(old.attach(Closeable {
            assertFalse(old.active)
            val callback = Thread { claim(registry, unrelated, false); closed.countDown() }
            callback.start()
            unlocked.set(closed.await(2, TimeUnit.SECONDS))
            callback.join(2000)
        }))
        registry.request(home, true, { home.lease = it; assertEquals(0L, closed.count) }, { })
        assertTrue("closing the old listener must not hold the ownership monitor", unlocked.get())
        assertTrue(registry.isCurrent(home, checkNotNull(home.lease)))
    }

    @Test fun oldLeaseReleaseAndFailureCannotRetireReplacementOwner() {
        val registry = FluxResponderOwnerRegistry { false }
        val original = Owner()
        val home = Owner()
        claim(registry, original, false)
        val old = checkNotNull(original.lease)
        claim(registry, home, true)
        registry.failed(original, old)
        registry.release(original)
        assertEquals(0, home.revoked)
        assertTrue(registry.isCurrent(home, checkNotNull(home.lease)))
    }

    @Test fun failedStartupRetriesOnlyOnNewLifecycleRequestWithNewLease() {
        val registry = FluxResponderOwnerRegistry { false }
        val home = Owner()
        claim(registry, home, true)
        val first = checkNotNull(home.lease)
        registry.failed(home, first)
        assertFalse(first.active)
        repeat(5) { registry.reconsider() }
        assertEquals(1, home.granted)
        claim(registry, home, true)
        assertNotSame(first, home.lease)
        assertEquals(2, home.granted)
        assertTrue(registry.isCurrent(home, checkNotNull(home.lease)))
    }

    @Test fun failedListenerCannotInterruptPinnedCapture() {
        var pinned = false
        val registry = FluxResponderOwnerRegistry { pinned }
        val owner = Owner()
        val home = Owner()
        claim(registry, owner, false)
        pinned = true
        registry.failed(owner, checkNotNull(owner.lease))
        claim(registry, home, true)
        assertTrue(checkNotNull(owner.lease).active)
        pinned = false
        registry.reconsider()
        assertTrue(registry.isCurrent(home, checkNotNull(home.lease)))
    }

    @Test fun throwingRevocationAndCloseStillGrantReplacement() {
        val registry = FluxResponderOwnerRegistry { false }
        val original = Owner()
        val home = Owner()
        registry.request(original, false, { original.lease = it }, { error("UI already destroyed") })
        assertTrue(checkNotNull(original.lease).attach(Closeable { error("close failed") }))
        claim(registry, home, true)
        assertTrue(registry.isCurrent(home, checkNotNull(home.lease)))
    }

    @Test fun throwingStarterDoesNotLeaveCurrentLeaseWithoutListener() {
        val registry = FluxResponderOwnerRegistry { false }
        val original = Owner()
        val failed = Owner()
        claim(registry, original, false)
        registry.request(failed, true, { failed.lease = it; error("executor rejected") }, {})
        assertFalse(checkNotNull(failed.lease).active)
        assertEquals(2, original.granted)
        assertTrue(registry.isCurrent(original, checkNotNull(original.lease)))
    }

    @Test fun reentrantHomeRequestSkipsGrantForAlreadySupersededLease() {
        val registry = FluxResponderOwnerRegistry { false }
        val original = Owner()
        val intermediate = Owner()
        val finalHome = Owner()
        registry.request(original, false, { original.lease = it }, { claim(registry, finalHome, true) })
        claim(registry, intermediate, true)
        assertEquals(0, intermediate.granted)
        assertTrue(registry.isCurrent(finalHome, checkNotNull(finalHome.lease)))
    }

    @Test fun pendingOutboundHandshakePreventsStartingCaptureAndCapturePreventsNewDial() {
        var pinned = false
        val registry = FluxResponderOwnerRegistry { pinned }
        val ticket = checkNotNull(registry.beginOutbound())
        assertFalse(registry.canStartCapture())
        registry.finishOutbound(ticket)
        assertTrue(registry.canStartCapture())
        pinned = true
        assertNull(registry.beginOutbound())
        assertFalse(registry.canStartCapture())
        pinned = false
        registry.finishOutbound(ticket) // Delayed completion is idempotent.
        assertTrue(registry.canStartCapture())
    }
}
