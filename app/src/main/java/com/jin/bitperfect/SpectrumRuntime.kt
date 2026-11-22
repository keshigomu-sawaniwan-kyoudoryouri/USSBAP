package com.jin.bitperfect

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

data class SpectrumFrame(
    val db: FloatArray,
    val sampleRate: Int,
    val timestampNs: Long
)

/**
 * Lock-light 8192-point analyzer shared by every in-app PCM playback path.
 * PCM delivery never waits for an FFT: if analysis is busy, that visual frame
 * is skipped while audio continues normally.
 */
object SpectrumRuntime {
    const val FFT_SIZE = 8192
    const val BIN_COUNT = FFT_SIZE / 2 + 1

    val latest = AtomicReference<SpectrumFrame?>(null)
    val latestDry = AtomicReference<SpectrumFrame?>(null)
    val latestGenerated = AtomicReference<SpectrumFrame?>(null)
    @Volatile var displayMode=0 // post, compare, generated, high-band
    @Volatile var visualizerActive = false

    private val ring = FloatArray(FFT_SIZE)
    private val input = FloatArray(FFT_SIZE)
    private val dryRing = FloatArray(FFT_SIZE)
    private val dryInput = FloatArray(FFT_SIZE)
    private val generatedRing = FloatArray(FFT_SIZE)
    private val generatedInput = FloatArray(FFT_SIZE)
    private val real = DoubleArray(FFT_SIZE)
    private val imag = DoubleArray(FFT_SIZE)
    private val measuredDb = FloatArray(BIN_COUNT)
    private val prefixDb = DoubleArray(BIN_COUNT + 1)
    private val smooth = FloatArray(BIN_COUNT) { -108f }
    private val drySmooth = FloatArray(BIN_COUNT) { -108f }
    private val generatedSmooth = FloatArray(BIN_COUNT) { -108f }
    private val window = DoubleArray(FFT_SIZE) { i ->
        // Four-term Blackman-Harris: excellent leakage rejection for close tones.
        val phase = 2.0 * PI * i / (FFT_SIZE - 1)
        0.35875 - 0.48829 * cos(phase) + 0.14128 * cos(2 * phase) - 0.01168 * cos(3 * phase)
    }
    private val coherentGain = window.sum() / FFT_SIZE
    private val busy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Spectrum8192FFT").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private var writePosition = 0
    private var sampleCount = 0L
    private var offerCount = 0
    private var pendingRate = 48_000

    @Synchronized
    fun offer(left: FloatArray, right: FloatArray, offset: Int, count: Int, sampleRate: Int) {
        offerProcessed(left, right, left, right, offset, count, sampleRate)
    }

    /**
     * Supplies the exact wet PCM sent to AudioTrack and its aligned dry signal.
     * The generated trace is measured from wet-dry, never inferred from a scalar.
     */
    @Synchronized
    fun offerProcessed(
        wetLeft: FloatArray,
        wetRight: FloatArray,
        dryLeft: FloatArray,
        dryRight: FloatArray,
        offset: Int,
        count: Int,
        sampleRate: Int
    ) {
        if (!visualizerActive) return
        for (i in 0 until count) {
            val wet = (wetLeft[offset + i] + wetRight[offset + i]) * .5f
            val dry = (dryLeft[offset + i] + dryRight[offset + i]) * .5f
            ring[writePosition] = wet
            dryRing[writePosition] = dry
            generatedRing[writePosition] = wet - dry
            writePosition = (writePosition + 1) and (FFT_SIZE - 1)
        }
        sampleCount += count
        pendingRate = sampleRate
        if (sampleCount < FFT_SIZE || ++offerCount % 3 != 0 || !busy.compareAndSet(false, true)) return

        val tail = FFT_SIZE - writePosition
        ring.copyInto(input, 0, writePosition, FFT_SIZE)
        if (writePosition > 0) ring.copyInto(input, tail, 0, writePosition)
        dryRing.copyInto(dryInput, 0, writePosition, FFT_SIZE)
        if (writePosition > 0) dryRing.copyInto(dryInput, tail, 0, writePosition)
        generatedRing.copyInto(generatedInput, 0, writePosition, FFT_SIZE)
        if (writePosition > 0) generatedRing.copyInto(generatedInput, tail, 0, writePosition)
        val rate = pendingRate
        worker.execute {
            try {
                analyzeSignal(input, smooth, latest, rate)
                analyzeSignal(dryInput, drySmooth, latestDry, rate)
                analyzeSignal(generatedInput, generatedSmooth, latestGenerated, rate)
            }
            catch (t: Throwable) { AppLogger.w("SPECTRUM", "FFT analysis failed", t) }
            finally { busy.set(false) }
        }
    }

    private fun analyzeSignal(
        source: FloatArray,
        smoothed: FloatArray,
        destination: AtomicReference<SpectrumFrame?>,
        sampleRate: Int
    ) {
        for (i in 0 until FFT_SIZE) {
            real[i] = source[i] * window[i]
            imag[i] = 0.0
        }
        fft()
        val normalization = 2.0 / (FFT_SIZE * coherentGain)
        for (k in 0 until BIN_COUNT) {
            val magnitude = kotlin.math.hypot(real[k], imag[k]) * normalization
            measuredDb[k] = (20.0 * log10(max(1e-9, magnitude))).toFloat().coerceIn(-108f, 3f)
            prefixDb[k + 1] = prefixDb[k] + measuredDb[k]
        }
        for (k in 0 until BIN_COUNT) {
            val frequency = k * sampleRate.toFloat() / FFT_SIZE
            // About 1/48 octave above 8 kHz.  The analyzer still computes every
            // 5-6 Hz FFT bin, but the plotted line follows the audible spectral
            // envelope instead of drawing individual-bin needles as full-height
            // pixel spikes.
            val radius = max(3, (k * .0144f).toInt())
            val first = max(0, k - radius)
            val last = kotlin.math.min(BIN_COUNT - 1, k + radius)
            val localEnvelope = ((prefixDb[last + 1] - prefixDb[first]) / (last - first + 1)).toFloat()
            val highMixRaw = ((frequency - 8_000f) / 4_000f).coerceIn(0f, 1f)
            val highMix = highMixRaw * highMixRaw * (3f - 2f * highMixRaw) * .88f
            val displayMeasured = measuredDb[k] + (localEnvelope - measuredDb[k]) * highMix
            // Roughly 180-320 ms visual persistence at the current analysis rate.
            // Audio is not delayed; this affects only the displayed trace.
            val coefficient = if (displayMeasured > smoothed[k]) .28f else .08f
            smoothed[k] += (displayMeasured - smoothed[k]) * coefficient
        }
        destination.set(SpectrumFrame(smoothed.copyOf(), sampleRate, System.nanoTime()))
    }

    private fun fft() {
        var j = 0
        for (i in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var length = 2
        while (length <= FFT_SIZE) {
            val angle = -2.0 * PI / length
            val baseReal = cos(angle)
            val baseImag = sin(angle)
            for (start in 0 until FFT_SIZE step length) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until length / 2) {
                    val even = start + k
                    val odd = even + length / 2
                    val vr = real[odd] * wr - imag[odd] * wi
                    val vi = real[odd] * wi + imag[odd] * wr
                    real[odd] = real[even] - vr; imag[odd] = imag[even] - vi
                    real[even] += vr; imag[even] += vi
                    val nextWr = wr * baseReal - wi * baseImag
                    wi = wr * baseImag + wi * baseReal
                    wr = nextWr
                }
            }
            length = length shl 1
        }
    }
}
