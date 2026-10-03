package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.*
import org.junit.Test

class FluxThemeCatalogCacheTest {
    @Test fun catalogsAreSessionScopedAndStaleDisconnectCannotEraseReplacement() {
        val cache = FluxThemeCatalogCache<Any>()
        val old = Any()
        val replacement = Any()
        val other = Any()
        val first = FluxThemeCatalog("a", emptyList())
        val second = FluxThemeCatalog("b", emptyList())
        cache.put("peer", old, first)
        cache.put("other", other, first)
        assertSame(first, cache.get("peer", old))
        assertNull(cache.get("peer", replacement))
        cache.put("peer", replacement, second)
        cache.remove("peer", old)
        assertSame(second, cache.get("peer", replacement))
        assertNull(cache.get("peer", old))
        assertSame(first, cache.get("other", other))
        cache.remove("peer", replacement)
        assertNull(cache.get("peer", replacement))
        cache.clear()
        assertNull(cache.get("other", other))
    }
}