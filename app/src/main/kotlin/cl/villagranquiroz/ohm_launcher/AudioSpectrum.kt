package cl.villagranquiroz.ohm_launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.math.hypot

/** Normalized energy snapshot derived from Android Visualizer FFT bytes. */
data class AudioSpectrum(
    val volume: Float,
    val beat: Boolean,
    val bands: List<Float>,
) {
    companion object {
        val SILENCE = AudioSpectrum(0f, false, List(16) { 0f })
    }
}

object AudioSpectrumAnalyzer {
    fun analyze(fft: ByteArray?): AudioSpectrum {
        if (fft == null || fft.size < 4) return AudioSpectrum.SILENCE
        val output = FloatArray(16)
        val bins = fft.size / 2
        var total = 0f
        var peak = 0f
        for (band in output.indices) {
            val start = 1 + ((bins - 1) * band * band) / (output.size * output.size)
            val end = maxOf(
                start + 1,
                1 + ((bins - 1) * (band + 1) * (band + 1)) / (output.size * output.size),
            )
            var sum = 0f
            var count = 0
            for (bin in start until minOf(end, bins)) {
                val real = fft[bin * 2].toInt().toFloat()
                val imaginary = fft[bin * 2 + 1].toInt().toFloat()
                sum += hypot(real, imaginary) / 181f
                count++
            }
            val energy = if (count == 0) 0f else (sum / count).coerceIn(0f, 1f)
            output[band] = energy
            total += energy
            peak = maxOf(peak, energy)
        }
        return AudioSpectrum(
            volume = (total / output.size * 2.4f).coerceIn(0f, 1f),
            beat = output.take(3).average() > 0.42 && peak > 0.55f,
            bands = output.toList(),
        )
    }
}

/** Owns the global-output Visualizer and emits FFT updates on the main thread. */
class AudioSpectrumController(
    private val context: Context,
    private val onSpectrum: (AudioSpectrum) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var visualizer: Visualizer? = null

    fun start(): Boolean {
        if (visualizer != null) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onSpectrum(AudioSpectrum.SILENCE)
            return false
        }
        return runCatching {
            val instance = Visualizer(0)
            val range = Visualizer.getCaptureSizeRange()
            instance.captureSize = minOf(1024, range[1]).coerceAtLeast(range[0])
            instance.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit

                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        val value = AudioSpectrumAnalyzer.analyze(fft)
                        main.post { onSpectrum(value) }
                    }
                },
                Visualizer.getMaxCaptureRate() / 2,
                false,
                true,
            )
            instance.enabled = true
            visualizer = instance
            true
        }.getOrElse {
            onSpectrum(AudioSpectrum.SILENCE)
            false
        }
    }

    fun stop() {
        val instance = visualizer ?: return
        visualizer = null
        runCatching { instance.enabled = false }
        runCatching { instance.release() }
        onSpectrum(AudioSpectrum.SILENCE)
    }
}
