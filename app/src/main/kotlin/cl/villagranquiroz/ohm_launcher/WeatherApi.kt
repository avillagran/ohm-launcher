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

data class WeatherCurrentConditions(
    val temperatureC: Double,
    val weatherCode: Int,
    val apparentTemperatureC: Double?,
    val humidityPercent: Int?,
    val windSpeedKmh: Double?,
    val icon: String,
    val forecast: List<WeatherForecastDay>,
)

data class WeatherForecastDay(
    val date: String,
    val maximumTemperatureC: Double,
    val minimumTemperatureC: Double,
    val icon: String,
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

    fun parseCurrentWeatherDetails(json: String): WeatherCurrentConditions? = runCatching {
        val report = JSONObject(json)
        val current = report.optJSONObject("current") ?: return null
        if (!current.has("temperature_2m") || !current.has("weather_code")) return null
        val temperature = current.optDouble("temperature_2m", Double.NaN)
        if (!temperature.isFinite()) return null
        val weatherCode = current.optInt("weather_code")
        val isDay = current.optInt("is_day", 1) != 0
        val apparent = current.optDouble("apparent_temperature", Double.NaN)
            .takeIf(Double::isFinite)
        val humidity = current.optDouble("relative_humidity_2m", Double.NaN)
            .takeIf { it.isFinite() && it in 0.0..100.0 }
            ?.toInt()
        val windSpeed = current.optDouble("wind_speed_10m", Double.NaN)
            .takeIf { it.isFinite() && it >= 0.0 }
        WeatherCurrentConditions(
            temperatureC = temperature,
            weatherCode = weatherCode,
            apparentTemperatureC = apparent,
            humidityPercent = humidity,
            windSpeedKmh = windSpeed,
            icon = weatherCodeToNerdFontIcon(weatherCode, isDay),
            forecast = parseForecast(report.optJSONObject("daily"), current.optString("time").take(10)),
        )
    }.getOrNull()

    private fun parseForecast(daily: JSONObject?, today: String): List<WeatherForecastDay> {
        daily ?: return emptyList()
        val dates = daily.optJSONArray("time") ?: return emptyList()
        val highs = daily.optJSONArray("temperature_2m_max") ?: return emptyList()
        val lows = daily.optJSONArray("temperature_2m_min") ?: return emptyList()
        val codes = daily.optJSONArray("weather_code") ?: return emptyList()
        val firstDate = dates.optString(0)
        val currentDate = today.takeIf(String::isNotBlank) ?: firstDate
        return buildList {
            for (index in 0 until minOf(dates.length(), highs.length(), lows.length(), codes.length())) {
                val date = dates.optString(index).take(10)
                if (date.isBlank() || date <= currentDate) continue
                val high = highs.optDouble(index, Double.NaN).takeIf(Double::isFinite) ?: continue
                val low = lows.optDouble(index, Double.NaN).takeIf(Double::isFinite) ?: continue
                val code = codes.optDouble(index, Double.NaN).takeIf(Double::isFinite)?.toInt() ?: continue
                add(WeatherForecastDay(date, high, low, weatherCodeToNerdFontIcon(code)))
                if (size == FORECAST_DAY_COUNT) break
            }
        }
    }

    /** Returns the current temperature in Celsius together with the WMO weather code. */
    fun parseCurrentWeather(json: String): Pair<Double, Int>? =
        parseCurrentWeatherDetails(json)?.let { it.temperatureC to it.weatherCode }

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

    /** Maps Open-Meteo WMO codes to the same Nerd Font glyphs used by Supernotch. */
    fun weatherCodeToNerdFontIcon(code: Int, isDay: Boolean = true): String = when (code) {
        0 -> if (isDay) "" else ""
        1, 2 -> if (isDay) "" else ""
        3 -> ""
        45, 48 -> if (isDay) "\uE313" else "\uE346"
        51, 53, 55, 56, 57, 61 -> if (isDay) "" else ""
        63, 65, 66, 67, 80, 81, 82 -> ""
        71, 73, 75, 77, 85, 86 -> ""
        95, 96, 99 -> ""
        else -> ""
    }

    /** Rounds for display; [unit] is "C" or "F". */
    fun displayTemperature(temperatureC: Double, unit: String): Int {
        val value = if (unit == "F") temperatureC * 9.0 / 5.0 + 32.0 else temperatureC
        return value.roundToInt()
    }

    fun geocodingUrl(query: String, language: String): String =
        "$GEOCODING_BASE?name=${encode(query)}&count=$CANDIDATE_LIMIT&language=${encode(language)}&format=json"

    fun forecastUrl(latitude: Double, longitude: Double): String =
        "$FORECAST_BASE?latitude=$latitude&longitude=$longitude&current=${encode("temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code")}" +
            "&daily=${encode("temperature_2m_max,temperature_2m_min,weather_code")}&forecast_days=${FORECAST_DAY_COUNT + 1}&timezone=auto"

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private const val FORECAST_DAY_COUNT = 3
}
