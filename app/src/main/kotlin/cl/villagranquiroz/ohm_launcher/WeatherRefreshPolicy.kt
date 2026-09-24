package cl.villagranquiroz.ohm_launcher

/** Decides when the weather widget needs a network refresh. */
object WeatherRefreshPolicy {
    const val STALE_MILLIS: Long = 30L * 60L * 1000L

    fun shouldRefresh(updatedMillis: Long, hasTemperature: Boolean, nowMillis: Long): Boolean {
        if (!hasTemperature) return true
        return nowMillis - updatedMillis >= STALE_MILLIS
    }
}
