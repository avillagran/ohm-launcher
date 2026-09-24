package cl.villagranquiroz.ohm_launcher

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WeatherMapRenderCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cachedFramesAreAvailableUntilTheTenMinuteTtlExpires() {
        var nowMillis = 1_000L
        val cache = WeatherMapRenderCache(
            directory = temporaryFolder.newFolder("cache"),
            clock = { nowMillis },
        )
        val source = temporaryFolder.newFile("rendered.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertTrue(cache.put(cacheKey(), chartJson(source)))

        nowMillis += WeatherMapRenderCache.TTL_MILLIS - 1
        val cached = cache.get(cacheKey())
        assertNotNull(cached)
        val cachedFrame = File(JSONObject(cached!!).getJSONArray("frames").getJSONObject(0).getString("path"))
        assertNotEquals(source.absolutePath, cachedFrame.absolutePath)
        assertTrue(cachedFrame.isFile)
        assertEquals(byteArrayOf(1, 2, 3).toList(), cachedFrame.readBytes().toList())

        nowMillis += 1
        assertNull(cache.get(cacheKey()))
        assertFalse(cachedFrame.exists())
    }

    @Test
    fun refusesToCacheChartsThatExceedTheDiskBudget() {
        val cache = WeatherMapRenderCache(
            directory = temporaryFolder.newFolder("small-cache"),
            maxBytes = 2,
        )
        val source = temporaryFolder.newFile("large.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertFalse(cache.put(cacheKey(), chartJson(source)))
        assertNull(cache.get(cacheKey()))
    }

    private fun chartJson(frame: File) = JSONObject()
        .put("frames", org.json.JSONArray().put(JSONObject().put("path", frame.absolutePath)))
        .put("layer", "rain")
        .toString()

    private fun cacheKey() = "a".repeat(64)
}
