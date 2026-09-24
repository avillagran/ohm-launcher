package cl.villagranquiroz.ohm_launcher

/** Decides when the weather widget needs a network refresh. */
object WeatherRefreshPolicy {
    const val STALE_MILLIS: Long = 10L * 60L * 1000L
    const val RETRY_DELAY_MILLIS: Long = 60L * 1000L

    fun shouldRefresh(
        updatedMillis: Long,
        hasTemperature: Boolean,
        nowMillis: Long,
        hasForecast: Boolean = true,
        retryAfterMillis: Long = 0L,
    ): Boolean {
        if (nowMillis < retryAfterMillis) return false
        if (!hasTemperature || !hasForecast) return true
        return nowMillis - updatedMillis >= STALE_MILLIS
    }
}
