package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmarchyWordmarkTest {
    @Test
    fun sizeAndResolutionControlIndependentCellDensity() {
        val small = OmarchyWordmark.render(canvasColumns = 65, canvasRows = 90, textSize = 1)
        val large = OmarchyWordmark.render(canvasColumns = 85, canvasRows = 110, textSize = 7)

        assertEquals(29, small.lineSequence().first().length)
        assertEquals(85, large.lineSequence().first().length)
        assertTrue(large.lines().size > small.lines().size)
        assertTrue(large.all { it == ' ' || it == '█' || it == '\n' })
    }
}
