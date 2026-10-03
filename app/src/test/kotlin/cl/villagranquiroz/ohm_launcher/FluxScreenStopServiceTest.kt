package cl.villagranquiroz.ohm_launcher

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class FluxScreenStopServiceTest {
    @After fun releaseCapture() { FluxScreenStops.currentId()?.let(FluxScreenStops::stop) }

    @Test fun notificationStopsExactOwnerOnMainWithoutOpeningActivityAndOnlyOnce() {
        val service = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        var closed = 0
        val id = checkNotNull(FluxScreenStops.register(Any()) {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            closed++
        })
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(start(service, id), 0, 1))
        val notification = shadowOf(service).lastForegroundNotification
        assertEquals(1, notification.actions.size)
        assertEquals(service.getString(R.string.flux_screen_stop), notification.actions.single().title)
        val pending = shadowOf(notification.actions.single().actionIntent)
        assertTrue(pending.isService)
        assertTrue(pending.isImmutable)
        val stop = pending.savedIntent
        assertEquals(ComponentName(service, ScreenCaptureService::class.java), stop.component)
        assertFalse(service.packageManager.getServiceInfo(checkNotNull(stop.component), 0).exported)
        service.onStartCommand(stop, 0, 2)
        service.onStartCommand(stop, 0, 3)
        assertEquals(1, closed)
        assertNull(FluxScreenStops.currentId())
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun oldPendingIntentAndDelayedCleanupCannotStopNewCapture() {
        val service = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        val oldOwner = Any()
        val oldId = checkNotNull(FluxScreenStops.register(oldOwner) {})
        service.onStartCommand(start(service, oldId), 0, 1)
        val oldPending = shadowOf(service).lastForegroundNotification.actions.single().actionIntent
        assertTrue(FluxScreenStops.unregister(oldOwner, oldId))
        var replacementClosed = false
        val newId = checkNotNull(FluxScreenStops.register(Any()) { replacementClosed = true })
        service.onStartCommand(start(service, newId), 0, 2)
        val newPending = shadowOf(service).lastForegroundNotification.actions.single().actionIntent
        assertNotEquals(oldPending, newPending)
        assertEquals(oldId, shadowOf(oldPending).savedIntent.getLongExtra(ScreenCaptureService.EXTRA_FLUX_STOP_ID, -1L))
        service.onStartCommand(shadowOf(oldPending).savedIntent, 0, 3)
        service.onStartCommand(ScreenCaptureService.fluxStopIntent(service, oldId, ended = true), 0, 4)
        service.onStartCommand(Intent(service, ScreenCaptureService::class.java).putExtra("stop", true), 0, 5)
        assertTrue(FluxScreenStops.isCurrent(newId))
        assertFalse(replacementClosed)
        assertFalse(shadowOf(service).isForegroundStopped)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun canceledCaptureCannotStartForegroundOrReleaseItsReadyCallback() {
        val service = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        val owner = Any()
        val id = checkNotNull(FluxScreenStops.register(owner) {})
        var began = false
        val ready = ScreenCaptureForegroundRequests.createRequest { began = true }
        assertTrue(FluxScreenStops.unregister(owner, id))
        service.onStartCommand(start(service, id)
            .putExtra(ScreenCaptureForegroundRequests.EXTRA_REQUEST_ID, ready.id), 0, 1)
        assertFalse(began)
        assertFalse(ScreenCaptureForegroundRequests.signal(ready.id))
        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun delayedFluxCleanupPreservesOmarchyForegroundService() {
        val service = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        val owner = Any()
        val id = checkNotNull(FluxScreenStops.register(owner) {})
        service.onStartCommand(start(service, id), 0, 1)
        assertTrue(FluxScreenStops.unregister(owner, id))
        assertEquals(Service.START_STICKY,
            service.onStartCommand(Intent(service, ScreenCaptureService::class.java), 0, 2))
        service.onStartCommand(ScreenCaptureService.fluxStopIntent(service, id, ended = true), 0, 3)
        assertFalse(shadowOf(service).isForegroundStopped)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun backgroundStopWithdrawsCaptureImmediatelyAndClosesOnMain() {
        var closed = false
        val id = checkNotNull(FluxScreenStops.register(Any()) {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            closed = true
        })
        val worker = Thread { assertTrue(FluxScreenStops.stop(id)) }
        worker.start()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertFalse(FluxScreenStops.isCurrent(id))
        assertFalse(closed)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(closed)
    }

    @Test fun nullRestartDoesNotCreateUnconsentedForegroundService() {
        val service = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun destroyedServiceClosesOnlyItsOwnCapture() {
        val oldService = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        val oldOwner = Any()
        val oldId = checkNotNull(FluxScreenStops.register(oldOwner) {})
        oldService.onStartCommand(start(oldService, oldId), 0, 1)
        assertTrue(FluxScreenStops.unregister(oldOwner, oldId))
        val newService = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        var replacementClosed = 0
        val newId = checkNotNull(FluxScreenStops.register(Any()) { replacementClosed++ })
        newService.onStartCommand(start(newService, newId), 0, 2)
        oldService.onDestroy()
        assertEquals(0, replacementClosed)
        assertTrue(FluxScreenStops.isCurrent(newId))
        newService.onDestroy()
        assertEquals(1, replacementClosed)
        assertNull(FluxScreenStops.currentId())
    }

    private fun start(service: ScreenCaptureService, id: Long) =
        Intent(service, ScreenCaptureService::class.java)
            .putExtra(ScreenCaptureService.EXTRA_TARGET, "flux")
            .putExtra(ScreenCaptureService.EXTRA_FLUX_STOP_ID, id)
}
