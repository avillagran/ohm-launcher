package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Host-side state and network orchestration for the bundled weather widget.
 * Network runs on a single background executor; callbacks are posted to the
 * main thread. Every failure keeps the last known good state.
 */
class WeatherWidgetController(
    private val context: Context,
    private val store: WeatherWidgetStore = WeatherWidgetStore(
        File(context.filesDir, "OhmLauncher/weather_widget.json"),
    ),
    private val fetcher: HttpFetcher = SafeHttpFetcher(
        allowedHosts = setOf("geocoding-api.open-meteo.com", "api.open-meteo.com"),
    ),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile
    private var state: WeatherWidgetState = store.load()

    fun currentState(): WeatherWidgetState = state

    /** Values exposed to the QML weather widget as the root-scope `Weather` map. */
    fun bindings(): Map<String, Any?> {
        val snapshot = state
        val temperature = snapshot.temperatureC
        return mapOf(
            "Weather" to mapOf(
                "unit" to snapshot.unit,
                "city" to snapshot.city,
                "tempText" to temperature?.let { "${WeatherApi.displayTemperature(it, snapshot.unit)}°" }.orEmpty(),
                "condition" to context.getString(conditionKeyToStringRes(snapshot.conditionKey)),
                "updated" to updatedText(snapshot.updatedMillis),
            ),
        )
    }

    fun refreshIfStale(onUpdated: () -> Unit = {}) {
        val snapshot = state
        if (WeatherRefreshPolicy.shouldRefresh(snapshot.updatedMillis, snapshot.temperatureC != null, nowMillis())) {
            refresh(onUpdated)
        }
    }

    fun refresh(onUpdated: () -> Unit = {}) {
        executor.execute {
            val snapshot = state
            val payload = runCatching {
                fetcher.fetch(WeatherApi.forecastUrl(snapshot.latitude, snapshot.longitude), MAX_BYTES)
            }.getOrNull()
            val parsed = payload?.let { WeatherApi.parseCurrentWeather(String(it, Charsets.UTF_8)) }
            parsed?.let { (temperatureC, weatherCode) ->
                state = snapshot.copy(
                    temperatureC = temperatureC,
                    conditionKey = WeatherApi.weatherCodeToConditionKey(weatherCode),
                    updatedMillis = nowMillis(),
                )
                runCatching { store.save(state) }
            }
            mainHandler.post { onUpdated() }
        }
    }

    fun searchCities(query: String, onResult: (List<WeatherCityCandidate>) -> Unit) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            mainHandler.post { onResult(emptyList()) }
            return
        }
        executor.execute {
            val payload = runCatching {
                fetcher.fetch(WeatherApi.geocodingUrl(trimmed, Locale.getDefault().language), MAX_BYTES)
            }.getOrNull()
            val results = payload?.let {
                WeatherApi.parseGeocoding(String(it, Charsets.UTF_8), CANDIDATE_LIMIT)
            }.orEmpty()
            mainHandler.post { onResult(results) }
        }
    }

    fun applyCandidate(candidate: WeatherCityCandidate, unit: String, onApplied: () -> Unit = {}) {
        state = state.copy(
            city = candidate.name,
            latitude = candidate.latitude,
            longitude = candidate.longitude,
            unit = normalizeUnit(unit),
            temperatureC = null,
            conditionKey = null,
            updatedMillis = 0L,
        )
        runCatching { store.save(state) }
        refresh(onApplied)
    }

    fun setUnit(unit: String, onApplied: () -> Unit = {}) {
        state = state.copy(unit = normalizeUnit(unit))
        runCatching { store.save(state) }
        mainHandler.post { onApplied() }
    }

    private fun updatedText(updatedMillis: Long): String {
        if (updatedMillis <= 0L) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(updatedMillis))
    }

    private fun normalizeUnit(unit: String): String = if (unit.uppercase() == "F") "F" else "C"

    companion object {
        private const val MAX_BYTES = 64L * 1024L
        private const val CANDIDATE_LIMIT = 8

        fun conditionKeyToStringRes(conditionKey: String?): Int = when (conditionKey) {
            "clear" -> R.string.weather_condition_clear
            "partly_cloudy" -> R.string.weather_condition_partly_cloudy
            "overcast" -> R.string.weather_condition_overcast
            "fog" -> R.string.weather_condition_fog
            "drizzle" -> R.string.weather_condition_drizzle
            "rain" -> R.string.weather_condition_rain
            "snow" -> R.string.weather_condition_snow
            "storm" -> R.string.weather_condition_storm
            else -> R.string.weather_condition_unknown
        }
    }
}
