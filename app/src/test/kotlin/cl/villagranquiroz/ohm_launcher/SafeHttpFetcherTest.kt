package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class SafeHttpFetcherTest {
    @Test
    fun rejectsNonHttpsSpoofedCredentialedAndFragmentUrlsBeforeConnecting() {
        var connections = 0
        val fetcher = SafeHttpFetcher(connectionOpener = {
            connections++
            FakeConnection(it, 200, "ok".toByteArray())
        })

        assertNull(fetcher.fetch("http://raw.githubusercontent.com/a", 10))
        assertNull(fetcher.fetch("https://raw.githubusercontent.com.evil.test/a", 10))
        assertNull(fetcher.fetch("https://user@raw.githubusercontent.com/a", 10))
        assertNull(fetcher.fetch("https://raw.githubusercontent.com:444/a", 10))
        assertNull(fetcher.fetch("https://raw.githubusercontent.com/a#fragment", 10))
        assertEquals(0, connections)
    }

    @Test
    fun boundsResponsesWithAndWithoutContentLength() {
        val responses = ArrayDeque(
            listOf(
                FakeConnection(URL("https://raw.githubusercontent.com/a"), 200, ByteArray(6), declaredLength = 6),
                FakeConnection(URL("https://raw.githubusercontent.com/a"), 200, ByteArray(6), declaredLength = -1),
                FakeConnection(URL("https://raw.githubusercontent.com/a"), 200, "okay".toByteArray()),
            ),
        )
        val fetcher = SafeHttpFetcher(connectionOpener = { responses.removeFirst() })

        assertNull(fetcher.fetch("https://raw.githubusercontent.com/a", 5))
        assertNull(fetcher.fetch("https://raw.githubusercontent.com/a", 5))
        assertArrayEquals("okay".toByteArray(), fetcher.fetch("https://raw.githubusercontent.com/a", 5))
    }

    @Test
    fun validatesRedirectTargetsAndFollowsTrustedRedirects() {
        val opened = mutableListOf<String>()
        val responses = mapOf(
            "https://omarchyplugins.com/start" to FakeConnection(
                URL("https://omarchyplugins.com/start"), 302, ByteArray(0),
                location = "https://raw.githubusercontent.com/acme/registry.json",
            ),
            "https://raw.githubusercontent.com/acme/registry.json" to FakeConnection(
                URL("https://raw.githubusercontent.com/acme/registry.json"), 200, "registry".toByteArray(),
            ),
        )
        val fetcher = SafeHttpFetcher(connectionOpener = { url ->
            opened += url.toString()
            responses.getValue(url.toString())
        })

        assertArrayEquals("registry".toByteArray(), fetcher.fetch("https://omarchyplugins.com/start", 20))
        assertEquals(responses.keys.toList(), opened)

        val unsafeRedirect = SafeHttpFetcher(connectionOpener = {
            FakeConnection(it, 302, ByteArray(0), location = "http://raw.githubusercontent.com/file")
        })
        assertNull(unsafeRedirect.fetch("https://omarchyplugins.com/start", 20))
    }

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val bytes: ByteArray,
        private val declaredLength: Long = bytes.size.toLong(),
        private val location: String? = null,
    ) : HttpURLConnection(url) {
        override fun getResponseCode(): Int = status
        override fun getContentLengthLong(): Long = declaredLength
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getHeaderField(name: String?): String? = if (name.equals("Location", true)) location else null
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
    }
}
