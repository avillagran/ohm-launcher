package cl.villagranquiroz.ohm_launcher

import java.net.URLEncoder
import kotlin.math.roundToInt
import org.json.JSONObject

/** A city suggestion returned by the Open-Meteo geocoding API. */
data class WeatherCityCandidate(
    val name: String,
    val country: String,
    val admin1: String,
    val latitude: Double,
    val longitude: Double,
)

/** Pure parsing and policy helpers for the Open-Meteo weather contract. */
object WeatherApi {
    private const val GEOCODING_BASE = "https://geocoding-api.open-meteo.com/v1/search"
    private const val FORECAST_BASE = "https://api.open-meteo.com/v1/forecast"
    private const val CANDIDATE_LIMIT = 8

    fun parseGeocoding(json: String, limit: Int): List<WeatherCityCandidate> = runCatching {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        buildList {
            for (index in 0 until results.length()) {
                if (size >= limit) break
                val entry = results.optJSONObject(index) ?: continue
                val latitude = entry.optDouble("latitude", Double.NaN)
                val longitude = entry.optDouble("longitude", Double.NaN)
                if (latitude.isNaN() || longitude.isNaN()) continue
                add(
                    WeatherCityCandidate(
                        name = entry.optString("name"),
                        country = entry.optString("country"),
                        admin1 = entry.optString("admin1"),
                        latitude = latitude,
                        longitude = longitude,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    /** Returns the current temperature in Celsius together with the WMO weather code. */
    fun parseCurrentWeather(json: String): Pair<Double, Int>? = runCatching {
        val current = JSONObject(json).optJSONObject("current") ?: return null
        if (!current.has("temperature_2m") || !current.has("weather_code")) return null
        val temperature = current.optDouble("temperature_2m", Double.NaN)
        if (temperature.isNaN()) return null
        temperature to current.optInt("weather_code")
    }.getOrNull()

    fun weatherCodeToConditionKey(code: Int): String = when (code) {
        0 -> "clear"
        1, 2 -> "partly_cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "rain"
        71, 73, 75, 77, 85, 86 -> "snow"
        95, 96, 99 -> "storm"
        else -> "unknown"
    }

    /** Rounds for display; [unit] is "C" or "F". */
    fun displayTemperature(temperatureC: Double, unit: String): Int {
        val value = if (unit == "F") temperatureC * 9.0 / 5.0 + 32.0 else temperatureC
        return value.roundToInt()
    }

    fun geocodingUrl(query: String, language: String): String =
        "$GEOCODING_BASE?name=${encode(query)}&count=$CANDIDATE_LIMIT&language=${encode(language)}&format=json"

    fun forecastUrl(latitude: Double, longitude: Double): String =
        "$FORECAST_BASE?latitude=$latitude&longitude=$longitude&current=${encode("temperature_2m,weather_code")}" +
            "&timezone=auto"

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
