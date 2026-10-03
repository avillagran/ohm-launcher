package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.content.res.AssetFileDescriptor
import android.content.pm.ProviderInfo
import android.provider.OpenableColumns
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
class FluxRevocationTest {
    @Test fun replacedAndDisconnectedOutboundPairDialogsAreDismissedOnlyForTheirSession() {
        val dismissed = mutableListOf<String>()
        val dialogs = FluxPairDialogRegistry<Any, String> { dismissed.add(it) }
        val old = Any()
        val replacement = Any()
        dialogs.replace(old, "old-compare")
        dialogs.replace(old, "old-verify")
        dialogs.replace(replacement, "replacement")
        dialogs.dismiss(old)
        dialogs.dismiss(old)
        assertEquals(listOf("old-compare", "old-verify"), dismissed)
        dialogs.dismiss(replacement)
        assertEquals(listOf("old-compare", "old-verify", "replacement"), dismissed)
    }

    @Test fun remoteUnpairCannotEraseReplacementPinOrRacePinWrite() {
        val peers = ConcurrentHashMap<String, Any>()
        val old = Any()
        val replacement = Any()
        peers["peer"] = old
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val removed = CountDownLatch(1)
        var pin = "old"
        val writer = Thread {
            FluxSessionAuthorization.withMapped(peers, "peer", old) {
                entered.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                pin = "new"
            }
        }
        writer.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val unpair = Thread {
            FluxSessionAuthorization.revokeIfMapped(peers, "peer", old) { pin = "" }
            removed.countDown()
        }
        unpair.start()
        assertFalse(removed.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        writer.join(2000)
        unpair.join(2000)
        assertFalse(writer.isAlive)
        assertFalse(unpair.isAlive)
        assertEquals("", pin)
        synchronized(peers) { peers["peer"] = replacement; pin = "replacement" }
        assertFalse(FluxSessionAuthorization.revokeIfMapped(peers, "peer", old) { pin = "" })
        assertEquals("replacement", pin)
        assertSame(replacement, peers["peer"])
    }

    @Test fun pendingPairReplyDisconnectClosesBeforeDelayedConfirmation() {
        val live = ConcurrentHashMap<String, Any>()
        val pending = ConcurrentHashMap<String, Any>()
        val session = Any()
        pending["peer"] = session
        val input = java.io.PipedInputStream()
        val output = java.io.PipedOutputStream(input)
        val closed = CountDownLatch(1)
        val liveness = FluxPairLiveness()
        liveness.watch({ FluxWire.readLine(input); null }, {}, {
            synchronized(live) { pending.remove("peer", session) }
            closed.countDown()
        })
        output.close() // EOF after a successful pair reply, before UI confirmation.
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertFalse(liveness.isOpen)
        assertThrows(IllegalStateException::class.java) {
            FluxSessionAuthorization.withPending(live, pending, "peer", session) {
                check(liveness.isOpen)
            }
        }
    }
    @Test fun delayedPairConfirmationCannotPinClosedOrReplacedSession() {
        val old = Any()
        val replacement = Any()
        var live: Any? = old
        var pins = 0
        val confirm = {
            FluxSessionAuthorization.withCurrent(old, { live }) { pins++ }
        }
        live = replacement
        assertThrows(IllegalStateException::class.java) { confirm() }
        live = null
        assertThrows(IllegalStateException::class.java) { confirm() }
        assertEquals(0, pins)
        live = old
        confirm()
        assertEquals(1, pins)
    }

    @Test fun replacementBetweenMapCheckAndPinCannotAuthorizeStaleSession() {
        val peers = java.util.concurrent.ConcurrentHashMap<String, Any>()
        val old = Any()
        val replacement = Any()
        peers["peer"] = old
        val checked = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var pins = 0
        val worker = Thread {
            try {
                assertTrue(peers["peer"] === old)
                checked.countDown()
                proceed.await()
                FluxSessionAuthorization.withMapped(peers, "peer", old) { pins++ }
            } catch (e: Throwable) { failure.set(e) }
        }
        worker.start()
        assertTrue(checked.await(2, TimeUnit.SECONDS))
        peers["peer"] = replacement
        proceed.countDown()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertTrue(failure.get() is IllegalStateException)
        assertEquals(0, pins)
    }

    @Test fun mapTransitionCannotReplaceDuringPinCriticalSection() {
        val peers = java.util.concurrent.ConcurrentHashMap<String, Any>()
        val old = Any()
        val replacement = Any()
        peers["peer"] = old
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val worker = Thread {
            FluxSessionAuthorization.withMapped(peers, "peer", old) {
                entered.countDown()
                assertTrue(proceed.await(2, TimeUnit.SECONDS))
                assertTrue(peers["peer"] === old)
            }
        }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val replaced = CountDownLatch(1)
        val swap = Thread { synchronized(peers) { peers["peer"] = replacement }; replaced.countDown() }
        swap.start()
        assertFalse(replaced.await(100, TimeUnit.MILLISECONDS))
        proceed.countDown()
        worker.join(2000)
        swap.join(2000)
        assertTrue(replaced.count == 0L)
        assertTrue(peers["peer"] === replacement)
    }

    @Test fun outboundPendingSessionCannotPinAfterInboundReplacement() {
        val live = java.util.concurrent.ConcurrentHashMap<String, Any>()
        val pending = java.util.concurrent.ConcurrentHashMap<String, Any>()
        val outgoing = Any()
        val incoming = Any()
        pending["peer"] = outgoing
        val checked = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var pins = 0
        val worker = Thread {
            try {
                assertTrue(pending["peer"] === outgoing)
                checked.countDown()
                proceed.await()
                FluxSessionAuthorization.withPending(live, pending, "peer", outgoing) { pins++ }
            } catch (e: Throwable) { failure.set(e) }
        }
        worker.start()
        assertTrue(checked.await(2, TimeUnit.SECONDS))
        synchronized(live) { pending.remove("peer"); live["peer"] = incoming }
        proceed.countDown()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertTrue(failure.get() is IllegalStateException)
        assertEquals(0, pins)
    }

    @Test fun forgetDuringBlockedSafQueryOrOpenNeverAnnouncesFile() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val provider = BlockingDocumentProvider()
        provider.attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply { authority = "flux-test-document" })
        ShadowContentResolver.registerProviderInternal("flux-test-document", provider)
        val uri = Uri.parse("content://flux-test-document/file")
        val cert = FluxIdentity.createMaterial("d".repeat(32)).certificate
        for (stage in listOf("query", "open")) {
            provider.blockAt = stage
            provider.entered = CountDownLatch(1)
            provider.release = CountDownLatch(1)
            val peers = ConcurrentHashMap<String, Any>()
            val session = Any()
            peers["desktop"] = session
            var trusted = true
            val gate = FluxTransferGate { trusted && peers["desktop"] === session }
            val announcements = mutableListOf<String>()
            val failure = AtomicReference<Throwable?>()
            val worker = Thread {
                try {
                    FluxFileTransfer.send(resolver, uri, null, null, gate, cert,
                        { error("TLS must not start after Forget") }) { announcements.add(it) }
                } catch (error: Throwable) { failure.set(error) }
            }
            worker.start()
            try {
                val reached = provider.entered.await(2, TimeUnit.SECONDS)
                assertTrue("SAF $stage was not entered: ${failure.get()}", reached)
                FluxSessionAuthorization.removeMapped(peers, "desktop", session) {
                    gate.revokeAsync()
                    trusted = false
                }
            } finally { provider.release.countDown() }
            worker.join(2000)
            assertFalse("SAF $stage did not finish", worker.isAlive)
            assertNotNull(failure.get())
            assertTrue("Late announce after $stage", announcements.isEmpty())
            assertNull(peers["desktop"])
            assertFalse(trusted)
        }
    }

    private class BlockingDocumentProvider : ContentProvider() {
        @Volatile var blockAt = ""
        @Volatile var entered = CountDownLatch(1)
        @Volatile var release = CountDownLatch(1)
        private fun pause(stage: String) {
            if (blockAt == stage) { entered.countDown(); release.await() }
        }
        override fun onCreate() = true
        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            pause("query")
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any>("file.txt", 1L))
            }
        }
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
            pause("open")
            val pipe = ParcelFileDescriptor.createPipe()
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(1) }
            return AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
    }

    @Test fun revokeClosesBlockedListenerAndRejectsLateRegistration() {
        val gate = FluxTransferGate { true }
        val listener = ServerSocket(0)
        gate.track(listener)
        val entered = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            entered.countDown()
            try { listener.accept() } catch (e: Exception) { failure.set(e) }
        }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        gate.revoke()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertTrue(listener.isClosed)
        assertNotNull(failure.get())
        val late = ServerSocket(0)
        assertThrows(IllegalStateException::class.java) { gate.track(late) }
        assertTrue(late.isClosed)
    }

    @Test fun revokeWhileSourceReadBlocksPreventsAnyPayloadWrite() {
        val gate = FluxTransferGate { true }
        val entered = CountDownLatch(1)
        val input = object : InputStream() {
            private val release = CountDownLatch(1)
            @Volatile var closed = false
            override fun read(): Int { entered.countDown(); release.await(); return 42 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                entered.countDown(); release.await(); b[off] = 42; return 1
            }
            override fun close() { closed = true; release.countDown() }
        }
        gate.track(input)
        val output = ByteArrayOutputStream()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try { FluxFileTransfer.copyExact(input, output, 1, gate::checkActive) }
            catch (e: Exception) { failure.set(e) }
        }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        gate.revoke()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertTrue(input.closed)
        assertTrue(output.toByteArray().isEmpty())
        assertTrue(failure.get() is IllegalStateException)
    }

    @Test fun replacementRevokesSourceEvenWhenPinRemainsValid() {
        val old = Any()
        var live: Any = old
        val gate = FluxTransferGate { live === old }
        val resource = Closeable { }
        gate.track(resource)
        live = Any()
        assertThrows(IllegalStateException::class.java) { gate.checkActive() }
        gate.revoke()
        assertThrows(IllegalStateException::class.java) { gate.checkActive() }
    }

    @Test fun revokeInterruptsBlockedPayloadWrite() {
        val gate = FluxTransferGate { true }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = object : OutputStream() {
            @Volatile var closed = false
            override fun write(b: Int) { entered.countDown(); release.await(); check(!closed) }
            override fun write(b: ByteArray, off: Int, len: Int) { write(b[off].toInt()) }
            override fun close() { closed = true; release.countDown() }
        }
        gate.track(output)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try { FluxFileTransfer.copyExact(ByteArrayInputStream(byteArrayOf(1)), output, 1, gate::checkActive) }
            catch (e: Exception) { failure.set(e) }
        }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        gate.revoke()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertTrue(output.closed)
        assertNotNull(failure.get())
    }
}
