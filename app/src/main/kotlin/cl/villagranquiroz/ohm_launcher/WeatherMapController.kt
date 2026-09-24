package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Renders the supernotch weather map through the very same Rust
 * `weather_map::render_chart` code used by the omarchy-supernotch Linux
 * CLI, compiled as a cdylib and loaded via JNI. Rendering happens
 * off the main thread; one Open-Meteo request per fresh timeline.
 */
class WeatherMapController(private val context: Context) {

    data class Frame(
        val bitmap: Bitmap,
        val valid: String,
        val value: Double,
        val hasSignal: Boolean,
    )

    data class Chart(
        val frames: List<Frame>,
        val layer: String,
        val zoom: Int,
        val unit: String,
        val stale: Boolean,
        val snapshot: Boolean,
        val model: String,
        val labels: List<WeatherMapChartParser.Label>,
        val scaleKm: Int,
        val scalePx: Float,
        val legend: List<WeatherMapChartParser.LegendEntry>,
        val flowMarkers: Boolean,
        val licence: String,
    )

    sealed interface Result {
        data class Success(val chart: Chart) : Result
        data class Failure(val message: String) : Result
        data object Unsupported : Result
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val renderCache = WeatherMapRenderCache(File(context.filesDir, MAP_CACHE_DIR))

    data class ViewSettings(val layer: String, val zoom: Int)

    fun viewSettings(): ViewSettings {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return ViewSettings(
            layer = WeatherMapViewPolicy.normalizeLayer(preferences.getString(KEY_LAYER, null).orEmpty()),
            zoom = WeatherMapViewPolicy.clampZoom(preferences.getInt(KEY_ZOOM, WeatherMapViewPolicy.DEFAULT_ZOOM)),
        )
    }

    fun renderMap(
        latitude: Double,
        longitude: Double,
        layer: String = WeatherMapViewPolicy.DEFAULT_LAYER,
        zoom: Int = WeatherMapViewPolicy.DEFAULT_ZOOM,
        onCacheMiss: () -> Unit = {},
        callback: (Result) -> Unit,
    ) {
        if (!isSupported) {
            callback(Result.Unsupported)
            return
        }
        val selectedLayer = WeatherMapViewPolicy.normalizeLayer(layer)
        val selectedZoom = WeatherMapViewPolicy.clampZoom(zoom)
        val cacheKey = renderCacheKey(latitude, longitude, selectedLayer, selectedZoom)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAYER, selectedLayer)
            .putInt(KEY_ZOOM, selectedZoom)
            .apply()
        val stateDir = File(context.filesDir, SUPERNOTCH_STATE_DIR).apply { mkdirs() }
        executor.execute {
            val result = runCatching {
                // Rust emits temporary UUID frames; cached copies live elsewhere.
                stateDir.listFiles { file -> file.name.startsWith("chart-") && file.name.endsWith(".png") }
                    ?.forEach(File::delete)
                val cachedChart = renderCache.get(cacheKey)
                val json = cachedChart ?: run {
                    (context as? android.app.Activity)?.runOnUiThread(onCacheMiss) ?: onCacheMiss()
                    nativeChart(
                        latitude.toString(),
                        longitude.toString(),
                        selectedLayer,
                        selectedZoom.toString(),
                        stateDir.absolutePath,
                    ).let { rendered ->
                        if (renderCache.put(cacheKey, rendered)) {
                            renderCache.get(cacheKey) ?: rendered
                        } else {
                            rendered
                        }
                    }
                }
                toResult(WeatherMapChartParser.parse(json))
            }.getOrElse { error -> Result.Failure(error.message ?: "render error") }
            (context as? android.app.Activity)?.runOnUiThread { callback(result) }
                ?: callback(result)
        }
    }

    private fun toResult(meta: WeatherMapChartParser.Meta): Result {
        if (meta.error != null) return Result.Failure(meta.error)
        val frames = meta.frames.mapNotNull { frame ->
            BitmapFactory.decodeFile(frame.path)?.let {
                Frame(bitmap = it, valid = frame.valid, value = frame.value, hasSignal = frame.hasSignal)
            }
        }
        if (frames.isEmpty()) return Result.Failure("no frames")
        return Result.Success(
            Chart(
                frames = frames,
                layer = meta.layer,
                zoom = meta.zoom,
                unit = meta.unit,
                stale = meta.stale,
                snapshot = meta.snapshot,
                model = meta.model,
                labels = meta.labels,
                scaleKm = meta.scaleKm,
                scalePx = meta.scalePx,
                legend = meta.legend,
                flowMarkers = meta.flowMarkers,
                licence = meta.licence,
            ),
        )
    }

    private external fun nativeChart(
        lat: String,
        lon: String,
        layer: String,
        zoom: String,
        stateDir: String,
    ): String

    private fun renderCacheKey(latitude: Double, longitude: Double, layer: String, zoom: Int): String {
        val input = "${latitude.toBits()}:$longitude:${layer}:$zoom"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    companion object {
        private const val SUPERNOTCH_STATE_DIR = "supernotch"
        private const val MAP_CACHE_DIR = "weather-map-render-cache"
        private const val PREFERENCES = "supernotch_weather_map"
        private const val KEY_LAYER = "layer"
        private const val KEY_ZOOM = "zoom"

        val isSupported: Boolean = try {
            System.loadLibrary("supernotchweather")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
}

/** Pure-JSON view of the Rust chart payload; bitmap decoding stays in Android. */
object WeatherMapChartParser {

    data class Frame(
        val path: String,
        val valid: String,
        val value: Double,
        val hasSignal: Boolean,
    )
    data class Label(val name: String, val x: Float, val y: Float)
    data class LegendEntry(val label: String, val color: String)

    data class Meta(
        val frames: List<Frame>,
        val layer: String,
        val zoom: Int,
        val unit: String,
        val stale: Boolean,
        val snapshot: Boolean,
        val model: String,
        val labels: List<Label>,
        val scaleKm: Int,
        val scalePx: Float,
        val legend: List<LegendEntry>,
        val flowMarkers: Boolean,
        val licence: String,
        val error: String? = null,
    )

    fun parse(json: String): Meta {
        val doc = JSONObject(json)
        val error = doc.optString("error").takeIf(String::isNotBlank)
        val frames = if (error != null) {
            emptyList()
        } else {
            val framesJson = doc.getJSONArray("frames")
            buildList {
                for (index in 0 until framesJson.length()) {
                    val item = framesJson.getJSONObject(index)
                    add(
                        Frame(
                            path = item.getString("path"),
                            valid = item.optString("valid"),
                            value = item.optDouble("value", Double.NaN),
                            hasSignal = item.optBoolean("hasSignal", false),
                        ),
                    )
                }
            }
        }
        val labelsJson = doc.optJSONArray("labels")
        val labels = buildList {
            if (labelsJson != null) for (index in 0 until labelsJson.length()) {
                val item = labelsJson.optJSONObject(index) ?: continue
                val x = item.optDouble("x", Double.NaN)
                val y = item.optDouble("y", Double.NaN)
                if (x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0) {
                    add(Label(item.optString("name"), x.toFloat(), y.toFloat()))
                }
            }
        }
        val legendJson = doc.optJSONArray("legend")
        val legend = buildList {
            if (legendJson != null) for (index in 0 until legendJson.length()) {
                val item = legendJson.optJSONObject(index) ?: continue
                val color = item.optString("color")
                if (color.matches(Regex("#[0-9a-fA-F]{6}"))) {
                    add(LegendEntry(item.optString("label"), color))
                }
            }
        }
        return Meta(
            frames = frames,
            layer = doc.optString("layer", "rain"),
            zoom = doc.optInt("zoom", WeatherMapViewPolicy.DEFAULT_ZOOM),
            unit = doc.optString("unit"),
            stale = doc.optBoolean("stale", false),
            snapshot = doc.optBoolean("snapshot", false),
            model = doc.optString("model"),
            labels = labels,
            scaleKm = doc.optInt("scaleKm", 0),
            scalePx = doc.optDouble("scalePx", 0.0).toFloat(),
            legend = legend,
            flowMarkers = doc.optBoolean("flowMarkers", false),
            licence = doc.optString("licence"),
            error = error,
        )
    }
}
