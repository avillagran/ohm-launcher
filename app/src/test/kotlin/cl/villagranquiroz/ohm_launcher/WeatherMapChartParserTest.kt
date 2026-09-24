package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherMapChartParserTest {

    @Test
    fun parsesFramesLayerAndLicence() {
        val meta = WeatherMapChartParser.parse(
            """{
              "frames": [
                {"path": "/data/app/chart-a-0.png", "valid": "2026-09-24T05:00", "value": 0.0, "hasSignal": false},
                {"path": "/data/app/chart-a-1.png", "valid": "2026-09-24T06:00", "value": 1.5, "hasSignal": true}
              ],
              "layer": "rain", "zoom": 2, "unit": "mm",
              "labels": [{"name":"Santiago","x":0.5,"y":0.5}],
              "scaleKm": 25, "scalePx": 640.0,
              "legend": [{"label":"1.0","color":"#35c7ff"}],
              "stale": false, "model": "ecmwf_ifs025",
              "snapshot": false,
              "licence": "ECMWF IFS · Open-Meteo CC-BY-4.0", "flowMarkers": true
            }""",
        )

        assertNull(meta.error)
        assertEquals(2, meta.frames.size)
        assertEquals("/data/app/chart-a-0.png", meta.frames[0].path)
        assertEquals("2026-09-24T06:00", meta.frames[1].valid)
        assertEquals(1.5, meta.frames[1].value, 0.001)
        assertEquals(true, meta.frames[1].hasSignal)
        assertEquals("rain", meta.layer)
        assertEquals(2, meta.zoom)
        assertEquals("mm", meta.unit)
        assertEquals("Santiago", meta.labels.single().name)
        assertEquals(25, meta.scaleKm)
        assertEquals(640f, meta.scalePx, 0.01f)
        assertEquals("#35c7ff", meta.legend.single().color)
        assertEquals("ecmwf_ifs025", meta.model)
        assertEquals(true, meta.flowMarkers)
        assertEquals("ECMWF IFS · Open-Meteo CC-BY-4.0", meta.licence)
    }

    @Test
    fun surfacesRustErrorField() {
        val meta = WeatherMapChartParser.parse("""{"error": "offline and no cached IFS timeline"}""")

        assertEquals("offline and no cached IFS timeline", meta.error)
        assertEquals(0, meta.frames.size)
    }
}
