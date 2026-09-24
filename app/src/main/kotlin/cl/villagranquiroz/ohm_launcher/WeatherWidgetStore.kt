package cl.villagranquiroz.ohm_launcher

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Persisted state of the bundled weather widget. */
data class WeatherWidgetState(
    val city: String,
    val latitude: Double,
    val longitude: Double,
    val unit: String,
    val temperatureC: Double?,
    val conditionKey: String?,
    val updatedMillis: Long,
    val apparentTemperatureC: Double? = null,
    val humidityPercent: Int? = null,
    val windSpeedKmh: Double? = null,
    val weatherIcon: String? = null,
    val forecast: List<WeatherForecastDay> = emptyList(),
) {
    companion object {
        val DEFAULT = WeatherWidgetState(
            city = "Santiago",
            latitude = -33.4489,
            longitude = -70.6693,
            unit = "C",
            temperatureC = null,
            conditionKey = null,
            updatedMillis = 0L,
        )
    }
}

/** JSON codec for [WeatherWidgetState]; tolerates missing and corrupt fields. */
object WeatherWidgetStateCodec {
    fun toJson(state: WeatherWidgetState): JSONObject = JSONObject()
        .put("city", state.city)
        .put("latitude", state.latitude)
        .put("longitude", state.longitude)
        .put("unit", state.unit)
        .put("temperatureC", state.temperatureC)
        .put("conditionKey", state.conditionKey)
        .put("updatedMillis", state.updatedMillis)
        .put("apparentTemperatureC", state.apparentTemperatureC)
        .put("humidityPercent", state.humidityPercent)
        .put("windSpeedKmh", state.windSpeedKmh)
        .put("weatherIcon", state.weatherIcon)
        .put("forecast", JSONArray().apply {
            state.forecast.forEach { day ->
                put(
                    JSONObject()
                        .put("date", day.date)
                        .put("maximumTemperatureC", day.maximumTemperatureC)
                        .put("minimumTemperatureC", day.minimumTemperatureC)
                        .put("icon", day.icon),
                )
            }
        })

    fun fromJson(json: JSONObject): WeatherWidgetState {
        val unit = json.optString("unit", "C").uppercase().let { if (it == "F") "F" else "C" }
        val latitude = json.optDouble("latitude", Double.NaN)
        val longitude = json.optDouble("longitude", Double.NaN)
        return WeatherWidgetState(
            city = json.optString("city", WeatherWidgetState.DEFAULT.city),
            latitude = if (latitude.isNaN()) WeatherWidgetState.DEFAULT.latitude else latitude,
            longitude = if (longitude.isNaN()) WeatherWidgetState.DEFAULT.longitude else longitude,
            unit = unit,
            temperatureC = if (json.has("temperatureC")) json.optDouble("temperatureC") else null,
            conditionKey = json.optString("conditionKey").takeIf(String::isNotBlank),
            updatedMillis = json.optLong("updatedMillis", 0L),
            apparentTemperatureC = json.optDouble("apparentTemperatureC", Double.NaN)
                .takeIf(Double::isFinite),
            humidityPercent = json.optInt("humidityPercent", -1).takeIf { it in 0..100 },
            windSpeedKmh = json.optDouble("windSpeedKmh", Double.NaN)
                .takeIf { it.isFinite() && it >= 0.0 },
            weatherIcon = json.optString("weatherIcon").takeIf(String::isNotBlank),
            forecast = parseForecast(json.optJSONArray("forecast")),
        )
    }

    private fun parseForecast(json: JSONArray?): List<WeatherForecastDay> = buildList {
        if (json == null) return@buildList
        for (index in 0 until json.length()) {
            val day = json.optJSONObject(index) ?: continue
            val date = day.optString("date").takeIf(String::isNotBlank) ?: continue
            val maximum = day.optDouble("maximumTemperatureC", Double.NaN).takeIf(Double::isFinite) ?: continue
            val minimum = day.optDouble("minimumTemperatureC", Double.NaN).takeIf(Double::isFinite) ?: continue
            val icon = day.optString("icon").takeIf(String::isNotBlank) ?: continue
            add(WeatherForecastDay(date, maximum, minimum, icon))
            if (size == MAX_FORECAST_DAYS) break
        }
    }

    private const val MAX_FORECAST_DAYS = 3
}

/** Persists the weather widget configuration with atomic tmp+rename writes. */
class WeatherWidgetStore(private val file: File) {
    @Synchronized
    fun load(): WeatherWidgetState = runCatching {
        if (!file.isFile) return WeatherWidgetState.DEFAULT
        WeatherWidgetStateCodec.fromJson(JSONObject(file.readText()))
    }.getOrDefault(WeatherWidgetState.DEFAULT)

    @Synchronized
    fun save(state: WeatherWidgetState) {
        val parent = requireNotNull(file.parentFile)
        parent.mkdirs()
        val temporary = parent.resolve("${file.name}.tmp")
        temporary.writeText(WeatherWidgetStateCodec.toJson(state).toString(2))
        check(temporary.renameTo(file) || temporary.copyTo(file, overwrite = true).let { temporary.delete() })
    }
}
