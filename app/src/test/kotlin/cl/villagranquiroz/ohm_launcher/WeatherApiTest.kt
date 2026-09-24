package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherApiTest {
    @Test
    fun parsesGeocodingCandidates() {
        val json = """
            {"results":[
              {"id":3871336,"name":"Santiago","latitude":-33.45694,"longitude":-70.64827,"country":"Chile","admin1":"Santiago Metropolitan"},
              {"id":3868626,"name":"Valparaíso","latitude":-33.0472,"longitude":-71.6127,"country":"Chile","admin1":"Valparaíso Region"},
              {"id":3128760,"name":"Barcelona","latitude":41.38879,"longitude":2.15899,"country":"Spain","admin1":"Catalonia"}
            ]}
        """.trimIndent()

        val candidates = WeatherApi.parseGeocoding(json, limit = 8)

        assertEquals(3, candidates.size)
        assertEquals("Santiago", candidates[0].name)
        assertEquals("Chile", candidates[0].country)
        assertEquals("Santiago Metropolitan", candidates[0].admin1)
        assertEquals(-33.45694, candidates[0].latitude, 0.0001)
        assertEquals(-70.64827, candidates[0].longitude, 0.0001)
        assertEquals("Valparaíso", candidates[1].name)
        assertEquals("Barcelona", candidates[2].name)
    }

    @Test
    fun geocodingParsingRespectsLimitAndToleratesMissingFields() {
        val json = """
            {"results":[
              {"name":"A","latitude":1.0,"longitude":2.0},
              {"name":"B","latitude":"oops","longitude":2.0},
              {"name":"C","latitude":3.0,"longitude":4.0}
            ]}
        """.trimIndent()

        val candidates = WeatherApi.parseGeocoding(json, limit = 1)

        assertEquals(1, candidates.size)
        assertEquals("A", candidates[0].name)
        assertEquals("", candidates[0].country)
    }

    @Test
    fun geocodingParsingReturnsEmptyForMissingResults() {
        assertTrue(WeatherApi.parseGeocoding("{}", limit = 8).isEmpty())
        assertTrue(WeatherApi.parseGeocoding("not json", limit = 8).isEmpty())
    }

    @Test
    fun parsesCurrentWeatherPayload() {
        val json = """
            {"current":{"time":"2024-07-01T12:00","temperature_2m":18.3,"weather_code":2}}
        """.trimIndent()

        val current = WeatherApi.parseCurrentWeather(json)

        assertEquals(18.3, current!!.first, 0.001)
        assertEquals(2, current.second)
    }

    @Test
    fun parsesTheCityDetailsShownByTheOriginalWeatherPanel() {
        val current = WeatherApi.parseCurrentWeatherDetails(
            """{"current":{"temperature_2m":18.3,"apparent_temperature":16.8,"relative_humidity_2m":71,"wind_speed_10m":12.4,"weather_code":2}}""",
        )!!

        assertEquals(18.3, current.temperatureC, 0.001)
        assertEquals(16.8, current.apparentTemperatureC!!, 0.001)
        assertEquals(71, current.humidityPercent)
        assertEquals(12.4, current.windSpeedKmh!!, 0.001)
    }

    @Test
    fun parsesThreeUpcomingDaysWithSupernotchNerdFontIcons() {
        val current = WeatherApi.parseCurrentWeatherDetails(
            """{"current":{"time":"2026-09-24T12:00","temperature_2m":18.3,"weather_code":2,"is_day":1},"daily":{"time":["2026-09-24","2026-09-25","2026-09-26","2026-09-27"],"temperature_2m_max":[19,20,21,22],"temperature_2m_min":[8,9,10,11],"weather_code":[2,0,61,95]}}""",
        )!!

        assertEquals("", current.icon)
        assertEquals(3, current.forecast.size)
        assertEquals("2026-09-25", current.forecast[0].date)
        assertEquals(20.0, current.forecast[0].maximumTemperatureC, 0.001)
        assertEquals(9.0, current.forecast[0].minimumTemperatureC, 0.001)
        assertEquals("", current.forecast[0].icon)
        assertEquals("", current.forecast[1].icon)
        assertEquals("", current.forecast[2].icon)
    }

    @Test
    fun mapsOpenMeteoConditionsToTheBundledNerdFont() {
        assertEquals("", WeatherApi.weatherCodeToNerdFontIcon(0, isDay = false))
        assertEquals("", WeatherApi.weatherCodeToNerdFontIcon(2, isDay = false))
        assertEquals("", WeatherApi.weatherCodeToNerdFontIcon(3))
        assertEquals("", WeatherApi.weatherCodeToNerdFontIcon(65))
        assertEquals("", WeatherApi.weatherCodeToNerdFontIcon(73))
        assertEquals("", WeatherApi.weatherCodeToNerdFontIcon(99))
    }

    @Test
    fun currentWeatherParsingReturnsNullWhenIncomplete() {
        assertNull(WeatherApi.parseCurrentWeather("{}"))
        assertNull(WeatherApi.parseCurrentWeather("""{"current":{"weather_code":0}}"""))
        assertNull(WeatherApi.parseCurrentWeather("garbage"))
    }

    @Test
    fun bucketsWmoWeatherCodesIntoConditionKeys() {
        assertEquals("clear", WeatherApi.weatherCodeToConditionKey(0))
        assertEquals("partly_cloudy", WeatherApi.weatherCodeToConditionKey(1))
        assertEquals("partly_cloudy", WeatherApi.weatherCodeToConditionKey(2))
        assertEquals("overcast", WeatherApi.weatherCodeToConditionKey(3))
        assertEquals("fog", WeatherApi.weatherCodeToConditionKey(45))
        assertEquals("fog", WeatherApi.weatherCodeToConditionKey(48))
        assertEquals("drizzle", WeatherApi.weatherCodeToConditionKey(51))
        assertEquals("drizzle", WeatherApi.weatherCodeToConditionKey(57))
        assertEquals("rain", WeatherApi.weatherCodeToConditionKey(61))
        assertEquals("rain", WeatherApi.weatherCodeToConditionKey(65))
        assertEquals("rain", WeatherApi.weatherCodeToConditionKey(80))
        assertEquals("rain", WeatherApi.weatherCodeToConditionKey(82))
        assertEquals("snow", WeatherApi.weatherCodeToConditionKey(71))
        assertEquals("snow", WeatherApi.weatherCodeToConditionKey(86))
        assertEquals("storm", WeatherApi.weatherCodeToConditionKey(95))
        assertEquals("storm", WeatherApi.weatherCodeToConditionKey(99))
        assertEquals("unknown", WeatherApi.weatherCodeToConditionKey(4))
        assertEquals("unknown", WeatherApi.weatherCodeToConditionKey(-1))
    }

    @Test
    fun convertsTemperatureBetweenCelsiusAndFahrenheitRounded() {
        assertEquals(20, WeatherApi.displayTemperature(20.0, "C"))
        assertEquals(68, WeatherApi.displayTemperature(20.0, "F"))
        assertEquals(19, WeatherApi.displayTemperature(18.6, "C"))
        assertEquals(14, WeatherApi.displayTemperature(-10.0, "F"))
        assertEquals(0, WeatherApi.displayTemperature(0.0, "C"))
        assertEquals(32, WeatherApi.displayTemperature(0.0, "F"))
    }

    @Test
    fun buildsHttpsGeocodingUrlWithEncodedQuery() {
        val url = WeatherApi.geocodingUrl("São Paulo", language = "es")

        assertTrue(url.startsWith("https://geocoding-api.open-meteo.com/v1/search?"))
        assertTrue(url.contains("name=S%C3%A3o+Paulo"))
        assertTrue(url.contains("count=8"))
        assertTrue(url.contains("language=es"))
        assertTrue(url.contains("format=json"))
    }

    @Test
    fun buildsHttpsForecastUrlForCoordinates() {
        val url = WeatherApi.forecastUrl(latitude = -33.0472, longitude = -71.6127)

        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(url.contains("latitude=-33.0472"))
        assertTrue(url.contains("longitude=-71.6127"))
        assertTrue(url.contains("current=temperature_2m%2Capparent_temperature%2Crelative_humidity_2m%2Cwind_speed_10m%2Cweather_code"))
        assertTrue(url.contains("daily=temperature_2m_max%2Ctemperature_2m_min%2Cweather_code"))
        assertTrue(url.contains("forecast_days=4"))
    }
}
