package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSpectrumAnalyzerTest {
    @Test
    fun silenceProducesZeroEnergy() {
        val value = AudioSpectrumAnalyzer.analyze(ByteArray(64))

        assertEquals(16, value.bands.size)
        assertEquals(0f, value.volume, 0.0001f)
        assertFalse(value.beat)
    }

    @Test
    fun fftEnergyProducesBoundedBandsVolumeAndBeat() {
        val fft = ByteArray(1024)
        for (index in 2 until fft.size step 2) {
            fft[index] = 127
            fft[index + 1] = 127
        }

        val value = AudioSpectrumAnalyzer.analyze(fft)

        assertTrue(value.bands.all { it in 0f..1f })
        assertTrue(value.volume in 0.9f..1f)
        assertTrue(value.beat)
    }
}
