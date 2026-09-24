package cl.villagranquiroz.ohm_launcher

data class WeatherMapLayer(val id: String, val labelRes: Int)

object WeatherMapViewPolicy {
    const val DEFAULT_LAYER = "rain"
    const val DEFAULT_ZOOM = 1
    const val MIN_ZOOM = 1
    const val MAX_ZOOM = 3

    val layers = listOf(
        WeatherMapLayer("rain", R.string.weather_map_layer_rain),
        WeatherMapLayer("clouds", R.string.weather_map_layer_clouds),
        WeatherMapLayer("temperature", R.string.weather_map_layer_temperature),
        WeatherMapLayer("wind", R.string.weather_map_layer_wind),
        WeatherMapLayer("humidity", R.string.weather_map_layer_humidity),
    )

    fun normalizeLayer(layer: String): String =
        layer.takeIf { candidate -> layers.any { it.id == candidate } } ?: DEFAULT_LAYER

    fun clampZoom(zoom: Int): Int = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    fun shouldShowLoading(isLoading: Boolean, cacheHit: Boolean): Boolean = isLoading && !cacheHit
}