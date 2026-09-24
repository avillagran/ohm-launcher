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
    fun refreshesWhenDataIsOlderThanThirtyMinutes() {
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
    fun keepsFreshDataWithoutRefreshing() {
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
