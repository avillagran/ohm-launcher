package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenFrameGeometryTest {
    @Test
    fun calculatesPaddedBitmapWidthFromPlaneStride() {
        assertEquals(1088, paddedBitmapWidth(1080, 4, 4352))
        assertEquals(1080, paddedBitmapWidth(1080, 4, 4320))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPixelStride() {
        paddedBitmapWidth(1080, 0, 4320)
    }
}
