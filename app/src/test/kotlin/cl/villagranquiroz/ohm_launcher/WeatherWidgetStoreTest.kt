package cl.villagranquiroz.ohm_launcher

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherWidgetStoreTest {
    @Test
    fun returnsDefaultsWhenFileIsMissing() {
        val file = Files.createTempDirectory("ohm-weather").resolve("weather_widget.json").toFile()
        val store = WeatherWidgetStore(file)

        val state = store.load()

        assertEquals("Santiago", state.city)
        assertEquals(-33.4489, state.latitude, 0.0001)
        assertEquals(-70.6693, state.longitude, 0.0001)
        assertEquals("C", state.unit)
        assertEquals(null, state.temperatureC)
        assertEquals(null, state.conditionKey)
        assertEquals(0L, state.updatedMillis)
    }

    @Test
    fun roundTripsStateThroughJsonFile() {
        val file = Files.createTempDirectory("ohm-weather").resolve("weather_widget.json").toFile()
        val store = WeatherWidgetStore(file)
        val state = WeatherWidgetState(
            city = "Valparaíso",
            latitude = -33.0472,
            longitude = -71.6127,
            unit = "F",
            temperatureC = 21.4,
            conditionKey = "clear",
            updatedMillis = 1_720_000_000_000L,
        )

        store.save(state)

        assertEquals(state, store.load())
        assertTrue(file.isFile)
    }

    @Test
    fun fallsBackToDefaultsWhenFileIsCorrupt() {
        val file = Files.createTempDirectory("ohm-weather").resolve("weather_widget.json").toFile()
        file.writeText("{ not valid json !!!")
        val store = WeatherWidgetStore(file)

        val state = store.load()

        assertEquals("Santiago", state.city)
        assertEquals("C", state.unit)
    }

    @Test
    fun normalizesInvalidStoredUnitToCelsius() {
        val file = Files.createTempDirectory("ohm-weather").resolve("weather_widget.json").toFile()
        file.writeText(
            org.json.JSONObject()
                .put("city", "Oslo")
                .put("latitude", 59.9139)
                .put("longitude", 10.7522)
                .put("unit", "kelvin")
                .toString(),
        )
        val store = WeatherWidgetStore(file)

        val state = store.load()

        assertEquals("Oslo", state.city)
        assertEquals("C", state.unit)
    }

    @Test
    fun writesAtomicallyThroughTemporaryFile() {
        val directory = Files.createTempDirectory("ohm-weather").toFile()
        val file = directory.resolve("weather_widget.json")
        val store = WeatherWidgetStore(file)

        store.save(WeatherWidgetState.DEFAULT)

        assertEquals(1, directory.listFiles { f -> f.name == "weather_widget.json" }!!.size)
        assertTrue(directory.listFiles { f -> f.name.endsWith(".tmp") }.isNullOrEmpty())
    }
}
