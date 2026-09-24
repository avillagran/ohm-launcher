package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ClockMapParserTest {

    @Test
    fun parsesTimezoneSearchSuggestions() {
        assertEquals(
            listOf("America/New_York", "America/Los_Angeles"),
            ClockMapParser.parseZones("""["America/New_York","America/Los_Angeles"]"""),
        )
    }

    @Test
    fun keepsOnlyClocksWithFiniteMapCoordinates() {
        val clocks = ClockMapParser.parse(
            """[
              {"name":"Santiago","tz":"America/Santiago","time":"10:30","date":"2026-09-24","local":true,"primary":true,"latitude":-33.45,"longitude":-70.67},
              {"name":"Unknown","tz":"Etc/Unknown","time":"13:30","date":"2026-09-24","local":false,"primary":false,"latitude":null,"longitude":null}
            ]""",
        )

        assertEquals(1, clocks.size)
        assertEquals("America/Santiago", clocks.single().timeZone)
        assertEquals("Santiago", clocks.single().name)
        assertEquals("10:30", clocks.single().time)
        assertEquals(-33.45, clocks.single().latitude, 0.001)
        assertEquals(-70.67, clocks.single().longitude, 0.001)
        assertEquals(true, clocks.single().primary)
    }

    @Test
    fun projectsTheEquatorAndPrimeMeridianToTheMapCenter() {
        val point = ClockMapProjection.project(
            longitude = 0.0,
            latitude = 0.0,
            width = 1200f,
            height = 570f,
        )

        assertEquals(600f, point.x, 0.01f)
        assertEquals(285f, point.y, 0.01f)
    }

    @Test
    fun exposesRustErrorPayloadsAsUsefulMessages() {
        try {
            ClockMapParser.parse("""{"error":"invalid timezone database"}""")
            fail("Expected the Rust error payload to be rejected")
        } catch (error: IllegalStateException) {
            assertEquals("invalid timezone database", error.message)
        }
    }
}
