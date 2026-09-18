package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCaptureForegroundGateTest {
    @Test
    fun completedOldStartCannotReleaseTheNextForegroundStart() {
        val gate = ScreenCaptureForegroundGate()
        var firstReady = false
        var secondReady = false
        val first = gate.createRequest { firstReady = true }

        assertTrue(gate.signal(first.id))
        assertTrue(firstReady)

        val second = gate.createRequest { secondReady = true }
        assertFalse(gate.signal(first.id))
        assertFalse(secondReady)

        assertTrue(gate.signal(second.id))
        assertTrue(secondReady)
    }
}