package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherConditionResourcesTest {
    @Test
    fun mapsEveryConditionKeyToAStringResource() {
        assertEquals(R.string.weather_condition_clear, WeatherWidgetController.conditionKeyToStringRes("clear"))
        assertEquals(R.string.weather_condition_partly_cloudy, WeatherWidgetController.conditionKeyToStringRes("partly_cloudy"))
        assertEquals(R.string.weather_condition_overcast, WeatherWidgetController.conditionKeyToStringRes("overcast"))
        assertEquals(R.string.weather_condition_fog, WeatherWidgetController.conditionKeyToStringRes("fog"))
        assertEquals(R.string.weather_condition_drizzle, WeatherWidgetController.conditionKeyToStringRes("drizzle"))
        assertEquals(R.string.weather_condition_rain, WeatherWidgetController.conditionKeyToStringRes("rain"))
        assertEquals(R.string.weather_condition_snow, WeatherWidgetController.conditionKeyToStringRes("snow"))
        assertEquals(R.string.weather_condition_storm, WeatherWidgetController.conditionKeyToStringRes("storm"))
        assertEquals(R.string.weather_condition_unknown, WeatherWidgetController.conditionKeyToStringRes("nonsense"))
        assertEquals(R.string.weather_condition_unknown, WeatherWidgetController.conditionKeyToStringRes(null))
    }
}
