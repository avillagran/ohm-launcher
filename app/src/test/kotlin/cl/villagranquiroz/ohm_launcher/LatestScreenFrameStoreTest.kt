package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestScreenFrameStoreTest {
    @Test
    fun publishesLatestFrameAndMonotonicStatus() {
        val store = LatestScreenFrameStore()
        assertNull(store.snapshot())
        assertEquals(0L, store.status().frames)

        store.update(byteArrayOf(1, 2, 3), width = 1220, height = 2712)
        store.update(byteArrayOf(4, 5), width = 1220, height = 2712)

        val frame = store.snapshot()!!
        assertArrayEquals(byteArrayOf(4, 5), frame.jpeg)
        assertEquals(1220, frame.width)
        assertEquals(2712, frame.height)
        assertEquals(2L, store.status().frames)
    }
}
