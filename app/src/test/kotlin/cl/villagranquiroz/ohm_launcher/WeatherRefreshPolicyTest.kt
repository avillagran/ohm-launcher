package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherRefreshPolicyTest {
    private val now = 1_720_000_000_000L

    @Test
    fun refreshesWhenTemperatureIsMissing() {
        assertEquals(true, WeatherRefreshPolicy.shouldRefresh(updatedMillis = now, hasTemperature = false, nowMillis = now))
        assertEquals(true, WeatherRefreshPolicy.shouldRefresh(updatedMillis = 0, hasTemperature = false, nowMillis = now))
    }

    @Test
    fun refreshesFreshCurrentDataWhenForecastIsMissing() {
        assertEquals(
            true,
            WeatherRefreshPolicy.shouldRefresh(
                updatedMillis = now,
                hasTemperature = true,
                nowMillis = now,
                hasForecast = false,
            ),
        )
    }

    @Test
    fun suppressesRepeatedRetriesDuringTheRetryWindow() {
        assertEquals(
            false,
            WeatherRefreshPolicy.shouldRefresh(
                updatedMillis = 0,
                hasTemperature = false,
                nowMillis = now,
                retryAfterMillis = now + 1,
            ),
        )
    }

    @Test
    fun refreshesWhenDataIsTenMinutesOld() {
        assertEquals(10L * 60L * 1000L, WeatherRefreshPolicy.STALE_MILLIS)
        assertEquals(
            true,
            WeatherRefreshPolicy.shouldRefresh(
                updatedMillis = now - WeatherRefreshPolicy.STALE_MILLIS,
                hasTemperature = true,
                nowMillis = now,
            ),
        )
        assertEquals(
            true,
            WeatherRefreshPolicy.shouldRefresh(updatedMillis = 0, hasTemperature = true, nowMillis = now),
        )
    }

    @Test
    fun keepsDataFreshUntilTenMinutes() {
        assertEquals(
            false,
            WeatherRefreshPolicy.shouldRefresh(
                updatedMillis = now - WeatherRefreshPolicy.STALE_MILLIS + 1,
                hasTemperature = true,
                nowMillis = now,
            ),
        )
    }
}
