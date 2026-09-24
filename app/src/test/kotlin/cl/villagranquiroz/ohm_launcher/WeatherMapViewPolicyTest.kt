package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherMapViewPolicyTest {
    @Test
    fun exposesTheOriginalWeatherMapLayers() {
        assertEquals(
            listOf("rain", "clouds", "temperature", "wind", "humidity"),
            WeatherMapViewPolicy.layers.map { it.id },
        )
    }

    @Test
    fun normalizesLayerAndClampsZoomToTheRustRendererRange() {
        assertEquals("rain", WeatherMapViewPolicy.normalizeLayer("unknown"))
        assertEquals(1, WeatherMapViewPolicy.clampZoom(0))
        assertEquals(3, WeatherMapViewPolicy.clampZoom(4))
        assertEquals(2, WeatherMapViewPolicy.clampZoom(2))
    }

    @Test
    fun showsLoadingOnlyWhileRenderingAnUncachedMap() {
        assertTrue(WeatherMapViewPolicy.shouldShowLoading(isLoading = true, cacheHit = false))
        assertFalse(WeatherMapViewPolicy.shouldShowLoading(isLoading = true, cacheHit = true))
        assertFalse(WeatherMapViewPolicy.shouldShowLoading(isLoading = false, cacheHit = false))
    }
}