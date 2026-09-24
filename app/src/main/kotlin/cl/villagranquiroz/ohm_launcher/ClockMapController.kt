package cl.villagranquiroz.ohm_launcher

import android.app.Activity
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.time.ZoneId

/** Reads world-clock data from the same Supernotch Rust clock backend. */
class ClockMapController(private val context: Context) {

    sealed interface Result {
        data class Success(val clocks: List<ClockMapParser.Clock>) : Result
        data class ZoneOptions(val zones: List<String>) : Result
        data class Failure(val message: String) : Result
        data object Unsupported : Result
    }

    private val executor = Executors.newSingleThreadExecutor()

    fun refresh(callback: (Result) -> Unit) = execute("list", "", callback)

    fun searchZones(query: String, callback: (Result) -> Unit) {
        if (query.isBlank()) {
            callback(Result.ZoneOptions(emptyList()))
            return
        }
        execute("search", query, callback)
    }

    fun addZone(timeZone: String, callback: (Result) -> Unit) = execute("add", timeZone, callback)

    fun selectZone(timeZone: String, callback: (Result) -> Unit) = execute("select", timeZone, callback)

    fun removeZone(timeZone: String, callback: (Result) -> Unit) = execute("remove", timeZone, callback)

    private fun execute(action: String, argument: String, callback: (Result) -> Unit) {
        if (!isSupported) {
            callback(Result.Unsupported)
            return
        }
        val stateDir = File(context.filesDir, STATE_DIRECTORY).apply { mkdirs() }
        val localZone = ZoneId.systemDefault().id
        executor.execute {
            val result = runCatching {
                val payload = nativeClockCommand(stateDir.absolutePath, localZone, action, argument)
                if (action == "search") {
                    Result.ZoneOptions(ClockMapParser.parseZones(payload))
                } else {
                    Result.Success(ClockMapParser.parse(payload))
                }
            }.fold(
                onSuccess = { it },
                onFailure = { Result.Failure(it.message ?: "clock map error") },
            )
            (context as? Activity)?.runOnUiThread { callback(result) } ?: callback(result)
        }
    }

    private external fun nativeWorldClocks(stateDir: String, localZone: String): String
    private external fun nativeClockCommand(
        stateDir: String,
        localZone: String,
        action: String,
        argument: String,
    ): String

    companion object {
        private const val STATE_DIRECTORY = "supernotch-clock"

        val isSupported: Boolean = try {
            System.loadLibrary("supernotchweather")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
}

/** Parsed world clocks emitted by Supernotch's Rust `worldclock-list` command. */
object ClockMapParser {
    data class Clock(
        val name: String,
        val timeZone: String,
        val time: String,
        val date: String,
        val local: Boolean,
        val primary: Boolean,
        val latitude: Double,
        val longitude: Double,
    )

    fun parseZones(payload: String): List<String> {
        val normalizedPayload = payload.trimStart()
        if (normalizedPayload.startsWith("{")) {
            val message = JSONObject(normalizedPayload).optString("error")
                .takeIf(String::isNotBlank) ?: "invalid timezone search response"
            throw IllegalStateException(message)
        }
        val zones = JSONArray(normalizedPayload)
        return buildList {
            for (index in 0 until zones.length()) {
                val zone = zones.optString(index).takeIf(String::isNotBlank) ?: continue
                if (zone !in this) add(zone)
            }
        }
    }

    fun parse(payload: String): List<Clock> {
        val normalizedPayload = payload.trimStart()
        if (normalizedPayload.startsWith("{")) {
            val message = JSONObject(normalizedPayload).optString("error")
                .takeIf(String::isNotBlank) ?: "invalid clock map response"
            throw IllegalStateException(message)
        }
        val clocks = JSONArray(normalizedPayload)
        return buildList {
            for (index in 0 until clocks.length()) {
                val item = clocks.optJSONObject(index) ?: continue
                val latitude = item.optDouble("latitude", Double.NaN)
                val longitude = item.optDouble("longitude", Double.NaN)
                if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
                    !longitude.isFinite() || longitude !in -180.0..180.0
                ) continue
                val timeZone = item.optString("tz").takeIf(String::isNotBlank) ?: continue
                add(
                    Clock(
                        name = item.optString("name", timeZone.substringAfterLast('/')),
                        timeZone = timeZone,
                        time = item.optString("time"),
                        date = item.optString("date"),
                        local = item.optBoolean("local"),
                        primary = item.optBoolean("primary"),
                        latitude = latitude,
                        longitude = longitude,
                    ),
                )
            }
        }
    }
}

/** The Equal Earth projection used by the original Supernotch clock panel. */
object ClockMapProjection {
    data class Point(val x: Float, val y: Float)

    fun project(longitude: Double, latitude: Double, width: Float, height: Float): Point {
        val phi = Math.toRadians(latitude)
        val theta = kotlin.math.asin(kotlin.math.sqrt(3.0) / 2.0 * kotlin.math.sin(phi))
        val theta2 = theta * theta
        val denominator = 3.0 * (
            1.340264 - 0.243318 * theta2 + 0.006251 * theta2 * theta2 * theta2 +
                0.034164 * theta2 * theta2 * theta2 * theta2
            )
        val x = 2.0 * kotlin.math.sqrt(3.0) * Math.toRadians(longitude) * kotlin.math.cos(theta) / denominator
        val y = 1.340264 * theta - 0.081106 * theta * theta2 +
            0.000893 * theta * theta2 * theta2 * theta2 +
            0.003796 * theta * theta2 * theta2 * theta2 * theta2
        return Point(
            x = width * ((x + 2.72) / 5.44).toFloat(),
            y = height * ((1.39 - y) / 2.78).toFloat(),
        )
    }
}
