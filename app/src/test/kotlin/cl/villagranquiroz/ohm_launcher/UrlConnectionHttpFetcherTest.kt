package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlConnectionHttpFetcherTest {
    @Test
    fun allowsOnlyHttpsMarketplaceAndGithubHosts() {
        assertTrue(UrlConnectionHttpFetcher.isAllowed("https://omarchyplugins.com/registry.json"))
        assertTrue(UrlConnectionHttpFetcher.isAllowed("https://raw.githubusercontent.com/o/r/main/x.qml"))
        assertTrue(UrlConnectionHttpFetcher.isAllowed("https://api.github.com/repos/o/r/contents"))
        assertFalse(UrlConnectionHttpFetcher.isAllowed("http://omarchyplugins.com/registry.json"))
        assertFalse(UrlConnectionHttpFetcher.isAllowed("https://omarchyplugins.com.evil.test/x"))
    }

    @Test
    fun rejectsInvalidLimitsWithoutOpeningNetwork() {
        assertNull(UrlConnectionHttpFetcher().fetch("https://omarchyplugins.com/registry.json", 0))
    }
}
