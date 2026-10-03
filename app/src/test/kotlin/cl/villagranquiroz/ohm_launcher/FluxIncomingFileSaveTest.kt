package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class FluxIncomingFileSaveTest {
    private fun newSave(
        session: Any,
        token: String,
        currentSession: () -> Any?,
        currentToken: () -> String?,
        authorized: () -> Boolean,
        openOutput: () -> OutputStream?,
        timeoutNanos: Long = TimeUnit.MINUTES.toNanos(10),
    ) = FluxIncomingFileSave(session, token, currentSession, currentToken, authorized,
        openOutput, timeoutNanos = timeoutNanos,
        stageOpener = { file, expected ->
            val input = Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
            try {
                check(input.available().toLong() == expected)
                input
            } catch (error: Throwable) {
                input.close()
                throw error
            }
        })

    private fun staged(bytes: ByteArray): File = Files.createTempFile("flux-save-test-", ".part")
        .toFile().apply { writeBytes(bytes) }

    @Test fun api24SymlinkedStagedPayloadNeverOpensDestination() {
        val actual = staged(byteArrayOf(1, 2, 3))
        val link = File(actual.parentFile, "${actual.name}.link")
        Files.createSymbolicLink(link.toPath(), actual.toPath())
        val session = Any()
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        val opens = java.util.concurrent.atomic.AtomicInteger()
        try {
            val save = newSave(session, "offer", { session }, { "offer" }, { true }, {
                opens.incrementAndGet(); ByteArrayOutputStream()
            })
            assertTrue(save.start(link, 3) { result.set(it); done.countDown() })
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertTrue(result.get().isFailure)
            assertEquals(0, opens.get())
            assertFalse(link.exists())
            assertArrayEquals(byteArrayOf(1, 2, 3), actual.readBytes())
        } finally { Files.deleteIfExists(link.toPath()); actual.delete() }
    }

    @Test fun api24StageChangedToSymlinkDuringValidationNeverOpensDestination() {
        val victim = staged(byteArrayOf(8, 9, 10))
        val original = staged(byteArrayOf(1, 2, 3))
        val stage = object : File(original.absolutePath) {
            override fun length(): Long {
                assertTrue(delete())
                Files.createSymbolicLink(toPath(), victim.toPath())
                return victim.length()
            }
        }
        val session = Any()
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        val opens = java.util.concurrent.atomic.AtomicInteger()
        try {
            val save = newSave(session, "offer", { session }, { "offer" }, { true }, {
                opens.incrementAndGet(); ByteArrayOutputStream()
            })
            assertTrue(save.start(stage, 3) { result.set(it); done.countDown() })
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertTrue(result.get().isFailure)
            assertEquals(0, opens.get())
            assertArrayEquals(byteArrayOf(8, 9, 10), victim.readBytes())
        } finally { Files.deleteIfExists(original.toPath()); victim.delete() }
    }

    @Test fun exactLiveTokenCopiesOnlyAnnouncedBytesAndDeletesStage() {
        val session = Any()
        var current: Any? = session
        var token: String? = "offer"
        val output = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        val file = staged(byteArrayOf(1, 2, 3))
        val save = newSave(session, "offer", { current }, { token }, { true },
            { output })
        assertTrue(save.start(file, 3) { result.set(it); done.countDown() })
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(result.get().exceptionOrNull()?.toString(), result.get().isSuccess)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        assertFalse(file.exists())
        current = Any(); token = null
    }

    @Test fun revokedWhileProviderOpenIsBlockedCannotWriteWhenOpenReturns() {
        val session = Any()
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        val file = staged(byteArrayOf(1, 2, 3))
        val save = newSave(session, "offer", { session }, { "offer" }, { true }, {
            opened.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
            output
        })
        try {
            assertTrue(save.start(file, 3) { done.countDown() })
            assertTrue(opened.await(2, TimeUnit.SECONDS))
            val revoker = Thread { save.revoke() }.apply { start() }
            revoker.join(500)
            assertFalse("revocation blocked by provider open", revoker.isAlive)
            release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(0, output.size())
            assertFalse(file.exists())
        } finally { release.countDown(); save.revoke() }
    }

    @Test fun replacedTokenAndWrongStageLengthNeverOpenDestination() {
        val session = Any()
        var token = "replacement"
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val firstDone = CountDownLatch(1)
        val first = staged(byteArrayOf(1, 2, 3))
        val wrongToken = newSave(session, "original", { session }, { token }, { true }, {
            opens.incrementAndGet(); ByteArrayOutputStream()
        })
        assertTrue(wrongToken.start(first, 3) { assertTrue(it.isFailure); firstDone.countDown() })
        assertTrue(firstDone.await(3, TimeUnit.SECONDS))
        assertFalse(first.exists())
        token = "replacement"
        val secondDone = CountDownLatch(1)
        val second = staged(byteArrayOf(1, 2, 3, 4))
        val wrongLength = newSave(session, "replacement", { session }, { token }, { true }, {
            opens.incrementAndGet(); ByteArrayOutputStream()
        })
        assertTrue(wrongLength.start(second, 3) { assertTrue(it.isFailure); secondDone.countDown() })
        assertTrue(secondDone.await(3, TimeUnit.SECONDS))
        assertFalse(second.exists())
        assertEquals(0, opens.get())
    }

    @Test fun blockedProviderWriteDoesNotBlockRevocationOrAnotherSaveDeadline() {
        val session = Any()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstDone = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val secondOpen = CountDownLatch(1)
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val firstResult = AtomicReference<Result<Unit>>()
        val secondResult = AtomicReference<Result<Unit>>()
        val firstFile = staged(ByteArray(128 * 1024))
        val secondFile = staged(byteArrayOf(7))
        val first = newSave(session, "first", { session }, { "first" }, { true }, {
            object : OutputStream() {
                override fun write(b: Int) = Unit
                override fun write(b: ByteArray, off: Int, len: Int) {
                    writes.incrementAndGet()
                    writing.countDown()
                    release.await(3, TimeUnit.SECONDS)
                }
            }
        })
        val second = newSave(session, "second", { session }, { "second" }, { true }, {
            secondOpen.countDown()
            release.await(3, TimeUnit.SECONDS)
            ByteArrayOutputStream()
        }, timeoutNanos = TimeUnit.MILLISECONDS.toNanos(150))
        try {
            assertTrue(first.start(firstFile, firstFile.length()) { firstResult.set(it); firstDone.countDown() })
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            assertTrue(second.start(secondFile, 1) { secondResult.set(it); secondDone.countDown() })
            assertTrue(secondOpen.await(2, TimeUnit.SECONDS))
            val revoker = Thread { first.revoke() }.apply { start() }
            revoker.join(500)
            assertFalse("revocation blocked by provider write", revoker.isAlive)
            Thread.sleep(300)
            release.countDown()
            assertTrue(firstDone.await(3, TimeUnit.SECONDS))
            assertEquals("no second chunk after revocation", 1, writes.get())
            assertTrue(firstResult.get().isFailure)
            assertTrue(secondDone.await(3, TimeUnit.SECONDS))
            assertTrue("blocked provider result must fail after its deadline", secondResult.get().isFailure)
            assertFalse(firstFile.exists())
            assertFalse(secondFile.exists())
        } finally { release.countDown(); first.revoke(); second.revoke() }
    }
}
