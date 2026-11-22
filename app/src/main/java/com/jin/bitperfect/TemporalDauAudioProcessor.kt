package com.jin.bitperfect

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

/** Streaming-only Temporal v2 processor. Local/USB bit-perfect playback never enters this path. */
class TemporalDauAudioProcessor(context: Context) : BaseAudioProcessor() {
    private val n = 1024
    private val hop = 512
    private val highStart = 128
    private val highCount = 385
    private val window = FloatArray(n) { sqrt(max(1e-8, .5 - .5 * cos(2.0 * PI * it / (n - 1)))).toFloat() }
    private val history = Array(2) { FloatArray(n) }
    private val ola = Array(2) { FloatArray(n) }
    private val previousHigh = Array(2) { FloatArray(highCount) }
    private val temporal = Array(2) { Array(4) { Array(32) { FloatArray(highCount) } } }
    private val real = Array(2) { DoubleArray(n) }
    private val imag = Array(2) { DoubleArray(n) }
    private val logMag = Array(2) { FloatArray(n / 2 + 1) }
    private val residual = Array(2) { FloatArray(highCount) }
    private val targetResidual = Array(2) { FloatArray(highCount) }
    // Reused two-pass synthesis buffers. Separating estimation from rendering
    // lets us smooth only the generated envelope without blurring real harmonics.
    private val originalHighMagnitude = Array(2) { FloatArray(highCount) }
    private val harmonicHighMagnitude = Array(2) { FloatArray(highCount) }
    private val originalHighPhase = Array(2) { FloatArray(highCount) }
    private val harmonicHighPhase = Array(2) { FloatArray(highCount) }
    private val candidateHighMagnitude = Array(2) { FloatArray(highCount) }
    private val targetHighMagnitude = Array(2) { FloatArray(highCount) }
    private val frequencyEnvelope = Array(2) { FloatArray(highCount) }
    private val projectedGenerated = Array(2) { FloatArray(highCount) }
    private val generatedLogState = Array(2) { FloatArray(highCount) { Float.NaN } }
    private val temporalGenerated = Array(2) { FloatArray(highCount) }
    private val generatedPhase = Array(2) { FloatArray(highCount) { Float.NaN } }
    // A tiny, deterministic off-bin drift prevents the synthesized STFT bins
    // from repeating the same 1024-sample waveform every two hops.  That exact
    // repetition was audible as a fine metallic/sandpaper texture.
    private val generatedPhaseDrift = Array(2) { channel ->
        FloatArray(highCount) { bin ->
            val hash = ((bin + 1L) * 1103515245L + (channel + 7L) * 12345L) and 0xffffL
            ((hash.toFloat() / 65535f) * 2f - 1f) * .095f
        }
    }
    private val previousSourcePhase = Array(2) { FloatArray(highCount) { Float.NaN } }
    private val sourcePhaseReliability = Array(2) { FloatArray(highCount) }
    private val airGeneratedBins = Array(2) { BooleanArray(highCount) }
    private val smoothingReady = BooleanArray(2)
    private val renderedBlock = Array(2) { FloatArray(hop) }
    private val analyzerWetBlock = Array(2) { FloatArray(hop) }
    private val analyzerDryBlock = Array(2) { FloatArray(hop) }
    private val airEnvelopeLog = FloatArray(2) { Float.NaN }
    private val airSlopeLogPerHz = FloatArray(2) { (-2.326 / 8.685889 / 1000.0).toFloat() }
    private val airBandFast = Array(2) { FloatArray(10) { Float.NaN } }
    private val airBandSlow = Array(2) { FloatArray(10) { Float.NaN } }
    private val airCutoffHz = FloatArray(2) { 22_000f }
    private val airAnalysisFrames = IntArray(2)
    private val airCutoffCandidate = FloatArray(2) { 22_000f }
    private val airCutoffVotes = IntArray(2)
    private val airPhase = Array(2) { FloatArray(highCount) { Float.NaN } }
    // One reusable hand-off buffer. The audio thread only writes it when the
    // inference worker is idle, so no multi-megabyte allocation occurs per hop.
    private val modelInput = FloatArray(2 * 4 * 32 * highCount)
    private val pending = java.util.ArrayDeque<Short>()
    // Processing runs on its own thread so the model has real wall-clock time
    // between analysis and the delayed output. Media3 may deliver several hops
    // in one queueInput() call; doing the model lookahead inline there would
    // synthesize the whole burst before the inference worker could run.
    private val inputQueue = java.util.concurrent.ConcurrentLinkedQueue<ShortArray>()
    private val outputQueue = java.util.concurrent.ConcurrentLinkedQueue<ShortArray>()
    @Volatile private var resetRequested = true
    @Volatile private var dspRunning = true
    @Volatile private var inlineMode = true
    @Volatile private var maxCallbackHops = 1
    private var outputChunk: ShortArray? = null
    private var outputChunkPos = 0
    private var wasActive = false
    private var transitionFrames = 0
    private var inferenceFrame = 0
    private var lastModelVersion = DauControl.modelVersion
    private var classicSpectralFactor = 1f
    private val environment = OrtEnvironment.getEnvironment()
    private data class BackendSession(val session: OrtSession, val label: String, val benchmarkMs: Float)
    @Volatile private var sessionV2: BackendSession? = null
    @Volatile private var backendV2 = "INITIALIZING"
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "DauQnnInference").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
    }
    private val dspThread = Thread { dspLoop() }.apply {
        name = "DauDsp"
        isDaemon = true
        priority = Thread.NORM_PRIORITY + 2
        start()
    }
    private val inferenceQueued = AtomicBoolean(false)
    private val publishedResidual = AtomicReference<FloatArray?>(null)
    // Lookahead: analysis runs D hops ahead of synthesis. Each delayed frame
    // keeps its own spectrum + flux + dry block, and the inference result is
    // written into the slot belonging to that frame.
    private val residualSlots = Array(16) { AtomicReference<FloatArray?>(null) }
    private val snapshots = java.util.ArrayDeque<FrameSnapshot>()
    private val fluxFrame = Array(2) { FloatArray(highCount) }
    private var analysisFrameId = 0L
    private var lastLookahead = -1
    private data class FrameSnapshot(
        val frameId: Long,
        val controlStrength: Float,
        val real: Array<DoubleArray>,
        val imag: Array<DoubleArray>,
        val flux: Array<FloatArray>,
        val dry: Array<FloatArray>
    )

    init {
        updateSelectedBackend()
        val appContext = context.applicationContext
        worker.execute { initializeBackend(appContext) }
    }

    private fun initializeBackend(context: Context) {
        // QNN's HTP backend loads the DSP skeleton from ADSP_LIBRARY_PATH.
        try {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            android.system.Os.setenv("ADSP_LIBRARY_PATH", nativeDir, true)
        } catch (_: Throwable) {}
        // On some OEM builds the untrusted_app SELinux domain is denied access
        // to the CDSP RPC device. HTP device creation cannot succeed there, so
        // probe once instead of running a long chain of failing QNN sessions.
        val htpUsable = try {
            android.system.Os.close(
                android.system.Os.open(
                    "/dev/adsprpc-smd",
                    android.system.OsConstants.O_RDWR,
                    0
                )
            )
            true
        } catch (t: Throwable) {
            AppLogger.w("DAU_BACKEND", "HTP unavailable: /dev/adsprpc-smd ${t.message}")
            false
        }
        val fp32 = try { context.assets.open("model/dau_temporal_1p5m_fp32.onnx").use { it.readBytes() } }
            catch (t: Throwable) { failInitialization("FP32 asset: ${t.message}"); return }
        val fp16 = try { context.assets.open("model/dau_temporal_1p5m_fp16.onnx").use { it.readBytes() } }
            catch (t: Throwable) { failInitialization("FP16 asset: ${t.message}"); return }
        val qdq = try { context.assets.open("model/dau_temporal_1p5m_qdq.onnx").use { it.readBytes() } }
            catch (t: Throwable) { failInitialization("QDQ asset: ${t.message}"); return }
        val failures = ArrayList<String>(4)
        if (!htpUsable) {
            failures += "QNN HTP: /dev/adsprpc-smd EACCES (OEM untrusted_app policy)"
        }
        val attempts = buildList {
            if (htpUsable) {
                add(Triple("QNN HTP", qdq, mapOf(
                    "backend_type" to "htp",
                    "soc_model" to "57",
                    "htp_arch" to "75"
                )))
            }
            // GPU runs FP16 internally; using an explicitly FP16 graph avoids
            // the FP32->FP16 conversion and keeps more weights in half precision.
            add(Triple("QNN GPU FP16", fp16, mapOf("backend_type" to "gpu")))
            add(Triple("QNN GPU FP32", fp32, mapOf("backend_type" to "gpu")))
        }
        for ((label, model, providerOptions) in attempts) {
            try {
                val options = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                    addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    addQnn(providerOptions)
                }
                val candidate = environment.createSession(model, options)
                val measured = benchmark(candidate)
                sessionV2 = BackendSession(candidate, label, measured)
                backendV2 = label
                DauControl.backendBenchmarkMs = measured
                DauControl.backendDiagnostics = failures.joinToString(" | ")
                DauControl.lastError = ""
                updateSelectedBackend()
                AppLogger.i("DAU_BACKEND", "selected=$label benchmark=${"%.3f".format(measured)}ms fallbacks=${failures.joinToString()}")
                return
            } catch (t: Throwable) {
                val reason = t.message?.lineSequence()?.firstOrNull()?.take(180) ?: t.javaClass.simpleName
                failures += "$label: $reason"
                AppLogger.w("DAU_BACKEND", "$label unavailable", t)
            }
        }
        try {
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
                setInterOpNumThreads(1)
            }
            val candidate = environment.createSession(fp32, options)
            val measured = benchmark(candidate)
            sessionV2 = BackendSession(candidate, "ONNX CPU", measured)
            backendV2 = "ONNX CPU"
            DauControl.backendBenchmarkMs = measured
            DauControl.backendDiagnostics = failures.joinToString(" | ")
            DauControl.lastError = ""
            updateSelectedBackend()
            AppLogger.i("DAU_BACKEND", "selected=ONNX CPU benchmark=${"%.3f".format(measured)}ms fallbacks=${failures.joinToString()}")
        } catch (t: Throwable) {
            failures += "CPU: ${t.message ?: t.javaClass.simpleName}"
            failInitialization(failures.joinToString(" | "))
        }
    }

    private fun benchmark(session: OrtSession): Float {
        val zeros = FloatArray(modelInput.size)
        fun runOnce() {
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(zeros), longArrayOf(2, 4, 32, highCount.toLong())).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).close()
            }
        }
        repeat(3) { runOnce() }
        val started = System.nanoTime()
        repeat(8) { runOnce() }
        return (System.nanoTime() - started) / 8_000_000f
    }

    private fun failInitialization(reason: String) {
        backendV2 = "UNAVAILABLE"
        DauControl.backendDiagnostics = reason
        DauControl.lastError = reason
        updateSelectedBackend()
        AppLogger.e("DAU_BACKEND", reason)
    }

    private fun updateSelectedBackend() {
        if (DauControl.modelVersion == DauControl.MODEL_V1) {
            DauControl.backend = "DAU 1.0.1 / SPECTRAL+HARMONIC+CLARITY"
            DauControl.lastError = ""
        } else {
            DauControl.backend = when {
                sessionV2 != null -> "TEMPORAL 1.5M / $backendV2"
                backendV2 == "INITIALIZING" -> "TEMPORAL 1.5M / INITIALIZING"
                else -> "SAFE BYPASS"
            }
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.channelCount != 2 || inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        resetRequested = true
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        inputBuffer.order(ByteOrder.nativeOrder())
        val byteCount = inputBuffer.remaining()
        if (byteCount <= 0) return
        val shortCount = byteCount / 2
        val callbackHops = (shortCount + hop * 2 - 1) / (hop * 2)
        if (callbackHops > maxCallbackHops) maxCallbackHops = callbackHops
        if (shortCount <= 0) {
            val out = replaceOutputBuffer(byteCount)
            while (out.hasRemaining()) out.put(0)
            out.flip()
            return
        }
        val input = ShortArray(shortCount)
        inputBuffer.asShortBuffer().get(input)
        inputBuffer.position(inputBuffer.limit())
        val processingPath = DauControl.active && DauControl.effectiveStrength > 0f &&
            (DauControl.modelVersion == DauControl.MODEL_V1 ||
                backendV2 == "INITIALIZING" || sessionV2 != null) &&
            inputAudioFormat.sampleRate in 44100..48000
        if (!processingPath) {
            resetRequested = true
            wasActive = false
            inputQueue.clear()
            outputQueue.clear()
            outputChunk = null
            val output = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())
            output.asShortBuffer().put(input)
            output.position(byteCount).flip()
            return
        }
        // Stable path: process on the audio thread. No output queue can
        // underrun, so the "play/stop" stutter cannot occur.
        inlineMode = true
        if (!wasActive) resetState()
        wasActive = true
        inputQueue.clear()
        outputQueue.clear()
        outputChunk = null
        outputChunkPos = 0
        for (s in input) pending.add(s)
        val output = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())
        var written = 0
        while (pending.size >= hop * 2) {
            val out = ShortArray(hop * 2)
            processOneHop(out)
            output.asShortBuffer().put(out)
            output.position(output.position() + out.size * 2)
            written += out.size
        }
        while (written < shortCount) {
            output.putShort(0)
            written++
        }
        output.flip()
        return
    }

    override fun onFlush() {
        outputChunk = null
        outputChunkPos = 0
        outputQueue.clear()
        resetRequested = true
        super.onFlush()
    }

    override fun onReset() {
        outputChunk = null
        outputChunkPos = 0
        outputQueue.clear()
        resetRequested = true
        super.onReset()
    }

    private fun dspLoop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        } catch (_: Throwable) {}
        while (dspRunning) {
            try {
                if (inlineMode) {
                    Thread.sleep(5)
                    continue
                }
                if (resetRequested) {
                    resetState()
                    inputQueue.clear()
                    outputQueue.clear()
                    resetRequested = false
                }
                if (inputAudioFormat.sampleRate !in 44100..48000) {
                    Thread.sleep(2)
                    continue
                }
                val lookahead = if (DauControl.active && DauControl.modelVersion == DauControl.MODEL_V2)
                    DauControl.lookaheadHops.coerceIn(0, 12) else 0
                if (outputQueue.size >= lookahead + maxCallbackHops + 2) {
                    Thread.sleep(1)
                    continue
                }
                val chunk = inputQueue.poll()
                if (chunk == null) {
                    Thread.sleep(1)
                    continue
                }
                for (s in chunk) pending.add(s)
                if (pending.size < hop * 2) continue
                val out = ShortArray(hop * 2)
                processOneHop(out)
                outputQueue.add(out)
            } catch (t: Throwable) {
                AppLogger.w("DAU_DSP", "processing failed", t)
                Thread.sleep(2)
            }
        }
    }

    private fun processOneHop(out: ShortArray) {
        for (ch in 0..1) history[ch].copyInto(history[ch], 0, hop, n)
        for (i in 0 until hop) for (ch in 0..1) history[ch][hop + i] = pending.removeFirst() / 32768f
        val wetEnabled = DauControl.active && DauControl.effectiveStrength > 0f
        val lookahead = if (wetEnabled && DauControl.modelVersion == DauControl.MODEL_V2)
            DauControl.lookaheadHops.coerceIn(0, 12) else 0
        if (lookahead != lastLookahead) {
            snapshots.clear()
            for (slot in residualSlots) slot.set(null)
            analysisFrameId = 0
            lastLookahead = lookahead
            DauControl.residualMisses = 0
            DauControl.lookaheadLatencyMs = 0f
        }
        var idx = 0
        if (lookahead > 0) {
            snapshots.addLast(analyzeFrame())
            val snap = if (snapshots.size > lookahead) snapshots.removeFirst() else null
            if (snap == null) {
                for (i in 0 until hop) for (ch in 0..1) {
                    analyzerDryBlock[ch][i] = 0f
                    analyzerWetBlock[ch][i] = 0f
                    out[idx++] = 0
                }
                DauControl.lookaheadLatencyMs = lookahead * (hop * 1000f / inputAudioFormat.sampleRate)
            } else {
                val block = synthesizeFrame(snap)
                for (i in 0 until hop) for (ch in 0..1) {
                    val dry = snap.dry[ch][i]
                    val fade = if (transitionFrames > 0) (4 - transitionFrames) / 4f else 1f
                    val sample = dry + (block[ch][i] - dry) * fade
                    analyzerDryBlock[ch][i] = dry
                    analyzerWetBlock[ch][i] = sample
                    out[idx++] = (sample.coerceIn(-.96f, .96f) * 32767f).roundToInt().toShort()
                }
                DauControl.lookaheadLatencyMs = (lookahead + 1) * (hop * 1000f / inputAudioFormat.sampleRate)
            }
        } else {
            val block = processFrame()
            for (i in 0 until hop) for (ch in 0..1) {
                val dry = if (wetEnabled) history[ch][i] else history[ch][hop + i]
                val fade = if (transitionFrames > 0) (4 - transitionFrames) / 4f else 1f
                val sample = dry + (block[ch][i] - dry) * fade
                analyzerDryBlock[ch][i] = dry
                analyzerWetBlock[ch][i] = sample
                out[idx++] = (sample.coerceIn(-.96f, .96f) * 32767f).roundToInt().toShort()
            }
            DauControl.lookaheadLatencyMs = 0f
        }
        SpectrumRuntime.offerProcessed(
            analyzerWetBlock[0], analyzerWetBlock[1],
            analyzerDryBlock[0], analyzerDryBlock[1],
            0, hop, inputAudioFormat.sampleRate
        )
        if (transitionFrames > 0) transitionFrames--
    }

    private fun processFrame(): Array<FloatArray> {
        val started = System.nanoTime()
        if (lastModelVersion != DauControl.modelVersion) {
            for (ch in 0..1) { residual[ch].fill(0f); targetResidual[ch].fill(0f) }
            inferenceFrame = 0
            lastModelVersion = DauControl.modelVersion
            updateSelectedBackend()
        }
        val controlStrength = if (DauControl.active) DauControl.effectiveStrength else 0f
        if (controlStrength == 0f) {
            for (ch in 0..1) history[ch].copyInto(renderedBlock[ch], 0, hop, n)
            DauControl.processingMs = 0f
            DauControl.highBandLiftDb = 0f
            DauControl.inferenceActive = false
            return renderedBlock
        }
        var highInputEnergy = 0.0
        var highOutputEnergy = 0.0
        var neuralResidualPeak = 0f
        var generatedCandidateEnergy = 0.0
        var generatedOutputEnergy = 0.0
        var openedGateBins = 0
        for (ch in 0..1) {
            for (i in 0 until n) real[ch][i] = (history[ch][i] * window[i]).toDouble()
            java.util.Arrays.fill(imag[ch], 0.0)
            fft(real[ch], imag[ch], false)
            for (k in 0..n/2) logMag[ch][k] = ln(1f + hypot(real[ch][k], imag[ch][k]).toFloat() * 20f)
            for (f in 0..3) for (t in 0 until 31) temporal[ch][f][t] = temporal[ch][f][t + 1]
            for (j in 0 until highCount) {
                val k = highStart + j
                val high = logMag[ch][k]
                val harmonic = logMag[ch][k / 2]
                val flux = max(0f, high - previousHigh[ch][j])
                val slope = high - if (j == 0) high else logMag[ch][k - 1]
                temporal[ch][0][31][j] = high / 5f
                temporal[ch][1][31][j] = harmonic / 5f
                temporal[ch][2][31][j] = flux / 2f
                temporal[ch][3][31][j] = slope.coerceIn(-2f, 2f) / 2f
                previousHigh[ch][j] = high
            }
        }
        if (DauControl.modelVersion == DauControl.MODEL_V1) return processClassicDau(controlStrength.pow(2), started)
        publishedResidual.getAndSet(null)?.let { latest ->
            for (ch in 0..1) for (j in 0 until highCount) targetResidual[ch][j] = latest[ch * highCount + j]
        }
        val frame = inferenceFrame++
        if (frame % 2 == 0) scheduleInference(frame.toLong())
        if (sessionV2 == null) {
            for (ch in 0..1) history[ch].copyInto(renderedBlock[ch], 0, hop, n)
            DauControl.inferenceActive = false
            return renderedBlock
        }
        return renderNeuralFrame(controlStrength, Array(2) { temporal[it][2][31] })
    }

    private fun analyzeFrame(): FrameSnapshot {
        if (lastModelVersion != DauControl.modelVersion) {
            for (ch in 0..1) { residual[ch].fill(0f); targetResidual[ch].fill(0f) }
            inferenceFrame = 0
            lastModelVersion = DauControl.modelVersion
            updateSelectedBackend()
        }
        val controlStrength = DauControl.effectiveStrength
        for (ch in 0..1) {
            for (i in 0 until n) real[ch][i] = (history[ch][i] * window[i]).toDouble()
            java.util.Arrays.fill(imag[ch], 0.0)
            fft(real[ch], imag[ch], false)
            for (k in 0..n / 2) logMag[ch][k] = ln(1f + hypot(real[ch][k], imag[ch][k]).toFloat() * 20f)
            for (f in 0..3) for (t in 0 until 31) temporal[ch][f][t] = temporal[ch][f][t + 1]
            for (j in 0 until highCount) {
                val k = highStart + j
                val high = logMag[ch][k]
                val harmonic = logMag[ch][k / 2]
                val flux = max(0f, high - previousHigh[ch][j])
                val slope = high - if (j == 0) high else logMag[ch][k - 1]
                temporal[ch][0][31][j] = high / 5f
                temporal[ch][1][31][j] = harmonic / 5f
                temporal[ch][2][31][j] = flux / 2f
                temporal[ch][3][31][j] = slope.coerceIn(-2f, 2f) / 2f
                previousHigh[ch][j] = high
            }
        }
        val frameId = analysisFrameId++
        if (frameId % 2 == 0L) scheduleInference(frameId)
        return FrameSnapshot(
            frameId = frameId,
            controlStrength = controlStrength,
            real = Array(2) { real[it].copyOf() },
            imag = Array(2) { imag[it].copyOf() },
            flux = Array(2) { temporal[it][2][31].copyOf() },
            dry = Array(2) { ch -> FloatArray(hop) { history[ch][it] } }
        )
    }

    private fun synthesizeFrame(snapshot: FrameSnapshot): Array<FloatArray> {
        for (ch in 0..1) {
            snapshot.real[ch].copyInto(real[ch])
            snapshot.imag[ch].copyInto(imag[ch])
        }
        val slot = residualSlots[(snapshot.frameId % residualSlots.size).toInt()]
        val latest = slot.getAndSet(null)
        if (latest != null) {
            for (ch in 0..1) for (j in 0 until highCount) {
                targetResidual[ch][j] = latest[ch * highCount + j]
            }
        } else if (snapshot.frameId % 2 == 0L) {
            DauControl.residualMisses++
        }
        if (sessionV2 == null) {
            for (ch in 0..1) snapshot.dry[ch].copyInto(renderedBlock[ch])
            DauControl.inferenceActive = false
            return renderedBlock
        }
        return renderNeuralFrame(snapshot.controlStrength, snapshot.flux)
    }

    private fun renderNeuralFrame(controlStrength: Float, flux: Array<FloatArray>): Array<FloatArray> {
        var highInputEnergy = 0.0
        var highOutputEnergy = 0.0
        var neuralResidualPeak = 0f
        var generatedCandidateEnergy = 0.0
        var generatedOutputEnergy = 0.0
        var openedGateBins = 0
        // Compress the old useful 15-25% region across the lower half of the
        // control while retaining enough headroom for a clearly audible 100%.
        // The old curve grew almost eightfold from 20 to 100; this one grows
        // only about threefold, with the envelope/phase guards handling peaks.
        // Restore the original full-scale neural reconstruction. Stability is
        // handled by the envelope and phase stages below, never by turning the
        // model down globally.
        // The student was trained in normalized STFT magnitude while this
        // real-time FFT is intentionally unnormalized. A 0.20 multiplier left
        // its reconstructed bins roughly 30 dB below the intended time-domain
        // level (visible only at the numerical noise floor). 6.0 restores that
        // calibration. Add a shallow high-end-only trim so 100% has 1.7 dB more
        // presence, while the useful low/mid range and every spectral/phase
        // safety stage remain effectively unchanged.
        // Multitrack calibration (13 titles / 10 artists, two disjoint
        // passages each) showed that the previous top-end trim was slightly
        // under-filled outside the ESCAPADE passage. Keep the lower range
        // unchanged and add only 0.4 dB at the very top of the control.
        val highStrengthTrim = 1f + .28f * controlStrength.pow(4f)
        val strength = 6.0f * controlStrength.pow(1.25f) * highStrengthTrim
        // A fixed per-hop interpolator hides asynchronous QNN completion jitter.
        // At 48 kHz this has an approximately 80 ms settling time and never
        // changes cadence when the accelerator returns a result late.
        for (ch in 0..1) for (j in 0 until highCount) {
            residual[ch][j] += (targetResidual[ch][j] - residual[ch][j]) * DauControl.residualSmoothing.coerceIn(.02f, .5f)
            neuralResidualPeak = max(neuralResidualPeak, residual[ch][j])
        }
        for (ch in 0..1) {
            // Locate the actual codec cliff before allowing neural energy.  The
            // Student has a positive softplus floor, so applying it below an
            // intact 15-16 kHz edge creates the broad artificial shelf visible
            // in the analyzer even when the prediction is otherwise stable.
            updateAirCutoff(ch)
            val cutoff = airCutoffHz[ch]
            val lowerEnvelope = bandMeanLogMagnitude(ch, cutoff - 3_000f, cutoff - 1_500f)
            val edgeEnvelope = bandMeanLogMagnitude(ch, cutoff - 1_500f, cutoff - 250f)
            val measuredSlope = ((edgeEnvelope - lowerEnvelope) / 1_375f)
                .coerceIn(-.00052f, -.00020f)
            airEnvelopeLog[ch] = if (airEnvelopeLog[ch].isFinite()) {
                airEnvelopeLog[ch] * .88f + edgeEnvelope * .12f
            } else edgeEnvelope
            airSlopeLogPerHz[ch] = airSlopeLogPerHz[ch] * .90f + measuredSlope * .10f
            // Pass one: build the neural candidate.  The former hand-authored
            // air continuation could become dominant at 100% and create a broad
            // synthetic hiss floor.  The trained residual is now the sole source
            // of new spectral energy; 100% is calibrated to 20% of the learned
            // full repair because the training target was normalized by five.
            for (j in 0 until highCount) {
                val k = highStart + j
                val old = hypot(real[ch][k], imag[ch][k]).toFloat()
                val base = ln(1f + old * 20f)
                val estimate = (exp((base + residual[ch][j]).coerceIn(0f, 7f)) - 1f) / 20f
                val hk = max(1, k / 2)
                val harmonicMag = hypot(real[ch][hk], imag[ch][hk]).toFloat()
                val confidence = (j / 220f).coerceIn(0f, 1f)
                // Preserve the established voicing below 18 kHz, then relax the
                // reconstruction ceiling gradually toward the learned air band.
                // At 20 kHz+ this still caps output well below the FLAC reference,
                // preventing codec noise from becoming a false ultrasonic shelf.
                val frequencyHz = k * inputAudioFormat.sampleRate.toFloat() / n
                val airBand = ((frequencyHz - 18000f) / 2000f).coerceIn(0f, 1f)
                val ceilingBase = .012f + (.032f - .012f) * airBand
                val ceilingSlope = .055f + (.145f - .055f) * airBand
                // Strength is applied once below to the reconstructed delta.
                // Applying it here as well reduced a nominal 100% path to only
                // a few percent of the trained residual.
                val ceiling = old + harmonicMag * (ceilingBase + ceilingSlope) * confidence
                val target = max(old, min(estimate, ceiling))
                val missingRaw = if (cutoff < 21_500f) {
                    ((frequencyHz - (cutoff - 650f)) / 1_650f).coerceIn(0f, 1f)
                } else 0f
                val cutoffGate = missingRaw * missingRaw * (3f - 2f * missingRaw)
                // A codec cliff can move or evade the coarse 1 kHz detector.
                // Open genuinely unsupported bins directly by comparing them
                // with their lower harmonic. This remains closed on intact HF
                // content and avoids the former all-or-nothing detector failure.
                val supportRatio = old / max(1e-8f, harmonicMag * .10f)
                val unsupportedRaw = ((.90f - supportRatio) / .75f).coerceIn(0f, 1f)
                val unsupported = unsupportedRaw * unsupportedRaw * (3f - 2f * unsupportedRaw)
                val highPermissionRaw = ((frequencyHz - 15_000f) / 2_500f).coerceIn(0f, 1f)
                val highPermission = highPermissionRaw * highPermissionRaw * (3f - 2f * highPermissionRaw)
                // Codec cutoffs are not identical: AAC often leaves a sloped
                // edge while Opus can end abruptly. A detector-only gate could
                // therefore stay almost shut on otherwise valid material.
                // This small neural-only floor opens gradually above 15 kHz;
                // it cannot create a fixed noise shelf because the learned
                // residual, natural envelope and phase guard still follow it.
                val codecInvariantFloor =
                    .12f * highPermission * confidence * controlStrength.pow(2f)
                val missingGate = max(max(cutoffGate, unsupported * highPermission), codecInvariantFloor)
                var generated = (target - old) * strength * confidence * missingGate
                if (missingGate > .05f) openedGateBins++
                // This is a ceiling, not a synthesized target.  It follows the
                // last intact source band and can only reduce neural output.
                // Consequently quiet passages cannot acquire a fixed HF floor.
                if (generated > 0f && cutoff < 21_500f && airEnvelopeLog[ch].isFinite()) {
                    val continuationScale = 1.18f + .10f * controlStrength.pow(3f)
                    val continuation = continuationScale * exp(
                        airEnvelopeLog[ch] + airSlopeLogPerHz[ch] *
                            (frequencyHz - (cutoff - 875f))
                    )
                    val naturalMaximum = max(0f, continuation - old)
                    generated = min(generated, naturalMaximum)
                }
                generatedCandidateEnergy += generated * generated
                var magnitude = old + generated
                originalHighMagnitude[ch][j] = old
                harmonicHighMagnitude[ch][j] = harmonicMag
                originalHighPhase[ch][j] = atan2(imag[ch][k], real[ch][k]).toFloat()
                harmonicHighPhase[ch][j] = atan2(imag[ch][hk], real[ch][hk]).toFloat()
                candidateHighMagnitude[ch][j] = magnitude
                targetHighMagnitude[ch][j] = target
                airGeneratedBins[ch][j] = false
            }

            // Pass two: obtain a local envelope of only the generated component.
            // A triangular window of roughly 0.4-0.8 kHz removes isolated needles
            // and deep single-bin holes, but the original spectrum is untouched.
            for (j in 0 until highCount) {
                val frequencyHz = (highStart + j) * inputAudioFormat.sampleRate.toFloat() / n
                val radius = when {
                    frequencyHz < 12_000f -> 8
                    frequencyHz < 17_000f -> 11
                    else -> 14
                }
                var weightedLog = 0f
                var weightTotal = 0f
                val first = max(0, j - radius)
                val last = min(highCount - 1, j + radius)
                for (neighbor in first..last) {
                    val weight = (radius + 1 - abs(neighbor - j)).toFloat()
                    val generated = max(0f, candidateHighMagnitude[ch][neighbor] - originalHighMagnitude[ch][neighbor])
                    weightedLog += ln(1f + generated * 20f) * weight
                    weightTotal += weight
                }
                frequencyEnvelope[ch][j] = (exp(weightedLog / max(1f, weightTotal)) - 1f) / 20f
            }

            // Pass 2b: project the fully missing band onto a slowly moving,
            // continuously descending log envelope. Neural FFT bins used to
            // rise and fall independently, producing large dB variance and the
            // audible "grit" reported at high strengths. The source spectrum
            // remains untouched; only the positive AI residual is projected.
            for (j in 0 until highCount) {
                val frequencyHz = (highStart + j) * inputAudioFormat.sampleRate.toFloat() / n
                val fullyGenerated = ((frequencyHz - (cutoff + 250f)) / 900f)
                    .coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                val targetLog = ln(max(1e-10f, frequencyEnvelope[ch][j]))
                var state = generatedLogState[ch][j]
                if (!state.isFinite()) state = targetLog
                else {
                    // Use bounded log-amplitude slew, not proportional motion
                    // toward the -200 dB numerical floor. With proportional
                    // interpolation one empty inference frame could remove
                    // several dB per 10.7 ms hop, which caused the observed
                    // mostly-flat band with only occasional generated spikes.
                    // Fully missing audio now releases at roughly 8 dB/s while
                    // still admitting genuine attacks at a controlled rate.
                    val maxRiseLog = .22f + (.10f - .22f) * fullyGenerated
                    val maxFallLog = .060f + (.010f - .060f) * fullyGenerated
                    val delta = targetLog - state
                    state += delta.coerceIn(-maxFallLog, maxRiseLog)
                }
                generatedLogState[ch][j] = state
                projectedGenerated[ch][j] = exp(state)
            }

            // From the codec cliff upward, cap the envelope against a natural
            // descending reference anchored at the edge. Do not chain each bin
            // to the previous raw minimum: a single spectral notch would then
            // drag the entire remaining band to the noise floor.
            val binHz = inputAudioFormat.sampleRate.toFloat() / n
            val firstDescending = (((cutoff + 650f) * n / inputAudioFormat.sampleRate).roundToInt() - highStart)
                .coerceIn(0, highCount - 1)
            // Use a robust edge neighborhood, never one FFT bin. A legitimate
            // notch exactly at the boundary must not collapse the ceiling for
            // every higher bin to zero.
            var descendingAnchor = 1e-10f
            for (anchorBin in max(0, firstDescending - 12)..min(highCount - 1, firstDescending + 6)) {
                descendingAnchor = max(descendingAnchor, projectedGenerated[ch][anchorBin])
            }
            val descendingAnchorLog = ln(descendingAnchor)
            val descendingSlope = airSlopeLogPerHz[ch].coerceIn(-.00052f, -.00028f)
            for (j in firstDescending + 1 until highCount) {
                val rawLog = ln(max(1e-10f, projectedGenerated[ch][j]))
                val descendingCeiling = descendingAnchorLog +
                    descendingSlope * binHz * (j - firstDescending)
                val projectedLog = min(rawLog, descendingCeiling)
                projectedGenerated[ch][j] = exp(projectedLog)
            }

            // Pass three: blend toward that envelope only during steady content.
            // Positive spectral flux opens the path immediately for cymbal and
            // pick attacks. A causal state then suppresses frame-to-frame chatter.
            for (j in 0 until highCount) {
                val k = highStart + j
                val old = originalHighMagnitude[ch][j]
                val harmonicMag = harmonicHighMagnitude[ch][j]
                val frequencyHz = k * inputAudioFormat.sampleRate.toFloat() / n
                val confidence = (j / 220f).coerceIn(0f, 1f)
                val rawFlux = flux[ch][j] * 2f
                val attack = (rawFlux / .12f).coerceIn(0f, 1f)
                val generated = max(0f, candidateHighMagnitude[ch][j] - old)
                // Generated sibilance can form a very narrow ridge even when its
                // total HF energy is modest.  Apply a soft local crest limit only
                // to the synthesized component in the ear-sensitive 7-16.5 kHz
                // region.  Real source transients remain completely untouched.
                val presenceRise = ((frequencyHz - 7_000f) / 2_500f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                val presenceFall = ((16_500f - frequencyHz) / 3_500f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                val presenceWeight = presenceRise * presenceFall
                val crestAllowance = frequencyEnvelope[ch][j] * (1.68f - .18f * attack) + old * .10f
                val crestKnee = max(crestAllowance * .38f, 1e-8f)
                val crestLimited = if (generated > crestAllowance) {
                    crestAllowance + crestKnee * kotlin.math.tanh((generated - crestAllowance) / crestKnee)
                } else generated
                val deRidgedGenerated = generated + (crestLimited - generated) * presenceWeight
                val fullyGenerated = ((frequencyHz - (cutoff + 250f)) / 900f)
                    .coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                val envelopeMix = if (frequencyHz >= 8_000f) {
                    (.82f * (1f - .42f * attack) + .16f * fullyGenerated).coerceAtMost(.985f)
                } else 0f
                val shapedGenerated = deRidgedGenerated +
                    (projectedGenerated[ch][j] - deRidgedGenerated) * envelopeMix
                if (!smoothingReady[ch]) temporalGenerated[ch][j] = shapedGenerated
                else {
                    // Flux may open the transition band, but it cannot punch
                    // through the stabilized fully generated envelope.
                    val follow = .16f + .30f * attack * (1f - fullyGenerated)
                    temporalGenerated[ch][j] += (shapedGenerated - temporalGenerated[ch][j]) * follow
                }
                var guardedTemporal = max(0f, temporalGenerated[ch][j])
                // Fast, shallow de-essing for AI-only transients.  The maximum
                // attenuation is about 1.3 dB and scales with both spectral flux
                // and generated/source dominance, preserving air and cymbal tails.
                val generatedDominance = guardedTemporal / max(1e-8f, old + guardedTemporal)
                val transientDeEss = .14f * presenceWeight * attack * generatedDominance
                guardedTemporal *= 1f - transientDeEss
                // Generated energy must converge toward Nyquist instead of
                // forming a flat ultrasonic shelf.  Apply -6.0 dB/kHz only to
                // the AI residual above 20 kHz; source information is preserved.
                // A 26-passage lossless/AAC regression found -6 dB/kHz to be
                // unnecessarily dark for several non-ESCAPADE masters. -5.2
                // retains a decisive Nyquist convergence without muting the
                // fully generated band.
                if (frequencyHz > 20_000f) {
                    val tailDb = -5.2f * (frequencyHz - 20_000f) / 1_000f
                    guardedTemporal *= 10f.pow(tailDb / 20f)
                }
                var magnitude = old + guardedTemporal

                // A lightweight inference guard for the generated band.  It is a
                // soft knee rather than a hard clamp: ordinary harmonics pass
                // unchanged, while only an implausibly large neural residual is
                // compressed progressively.  The allowance follows both the
                // source bin and its lower harmonic so quiet material does not
                // acquire a fixed ultrasonic shelf.
                if (frequencyHz >= 16_000f && magnitude > old) {
                    val guardedGenerated = magnitude - old
                    val allowance = (
                        old * .68f + harmonicMag * (.060f + .480f * sqrt(controlStrength)) * confidence
                    ).coerceAtLeast(1e-7f)
                    if (guardedGenerated > allowance) {
                        val knee = (allowance * .45f).coerceAtLeast(1e-7f)
                        magnitude = old + allowance + knee * kotlin.math.tanh((guardedGenerated - allowance) / knee)
                    }
                }
                var separatedGenerated = max(0f, magnitude - old)

                // Do not amplify AAC/MP3 residue using its unstable phase.  The
                // original complex bin is kept bit-for-bit within FFT precision;
                // AI energy is a separate oscillator whose phase advances every
                // hop and is gently locked to the lower harmonic.  This removes
                // frame-boundary phase jumps without turning noise into a tone.
                val sourcePhase = originalHighPhase[ch][j]
                val expectedAdvance = (2.0 * PI * k * hop / n).toFloat()
                val previousPhase = previousSourcePhase[ch][j]
                val phaseError = if (previousPhase.isFinite()) {
                    wrapPhase(sourcePhase - previousPhase - expectedAdvance)
                } else PI.toFloat()
                val measuredReliability = exp(-phaseError * phaseError / (2f * .72f * .72f))
                sourcePhaseReliability[ch][j] = if (previousPhase.isFinite()) {
                    sourcePhaseReliability[ch][j] * .82f + measuredReliability * .18f
                } else 0f
                previousSourcePhase[ch][j] = sourcePhase

                val donorPhase = wrapPhase(harmonicHighPhase[ch][j] * 2f)
                var synthesisPhase = generatedPhase[ch][j]
                synthesisPhase = if (synthesisPhase.isFinite()) {
                    val advanced = wrapPhase(synthesisPhase + expectedAdvance + generatedPhaseDrift[ch][j])
                    val reliableSource = sourcePhaseReliability[ch][j] > .72f && old > separatedGenerated
                    // In unreliable/noisy bins free-run at the exact bin phase.
                    // Chasing a noisy source or donor phase recreates the very
                    // frame-to-frame metallic modulation this stage removes.
                    val desired = if (reliableSource) sourcePhase else donorPhase
                    val correction = wrapPhase(desired - advanced).coerceIn(-.42f, .42f)
                    val lock = if (reliableSource) .22f + .22f * attack else .015f * attack
                    wrapPhase(advanced + correction * lock)
                } else donorPhase
                generatedPhase[ch][j] = synthesisPhase

                // Unreliable codec phase is precisely where metallic musical
                // noise is most likely.  Keep restoration, but reduce only the
                // synthesized part by at most 42%; real source energy is untouched.
                val reliability = sourcePhaseReliability[ch][j]
                val phaseRiskRaw = ((1f - reliability) * generatedDominance - .12f) / .48f
                val phaseRisk = phaseRiskRaw.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                val phaseSafety = .58f + .42f * sqrt(reliability)
                separatedGenerated *= 1f + (phaseSafety - 1f) * phaseRisk
                generatedOutputEnergy += separatedGenerated * separatedGenerated
                // Preserve exact source phase in ordinary, well-supported bins.
                // Crossfade toward the independent oscillator only where AI is
                // dominant and the codec phase detector reports low confidence.
                val outputGeneratedPhase = wrapPhase(
                    sourcePhase + wrapPhase(synthesisPhase - sourcePhase) * phaseRisk
                )
                val sourceReal = old * cos(sourcePhase)
                val sourceImag = old * sin(sourcePhase)
                val outputReal = sourceReal + separatedGenerated * cos(outputGeneratedPhase)
                val outputImag = sourceImag + separatedGenerated * sin(outputGeneratedPhase)
                highInputEnergy += old * old
                highOutputEnergy += outputReal * outputReal + outputImag * outputImag
                real[ch][k] = outputReal.toDouble()
                imag[ch][k] = if (k == n / 2) 0.0 else outputImag.toDouble()
                if (k in 1 until n / 2) { real[ch][n-k] = real[ch][k]; imag[ch][n-k] = -imag[ch][k] }
            }
            smoothingReady[ch] = true
            fft(real[ch], imag[ch], true)
            for (i in 0 until n) ola[ch][i] += real[ch][i].toFloat() * window[i]
        }
        for (ch in 0..1) ola[ch].copyInto(renderedBlock[ch], 0, 0, hop)
        for (ch in 0..1) { ola[ch].copyInto(ola[ch], 0, hop, n); java.util.Arrays.fill(ola[ch], hop, n, 0f) }
        val lift = (10.0 * log10((highOutputEnergy + 1e-12) / (highInputEnergy + 1e-12))).toFloat().coerceIn(0f, 24f)
        DauControl.highBandLiftDb = DauControl.highBandLiftDb * .85f + lift * .15f
        DauControl.generatedCandidateRms = sqrt(generatedCandidateEnergy / (2.0 * highCount)).toFloat()
        DauControl.generatedOutputRms = sqrt(generatedOutputEnergy / (2.0 * highCount)).toFloat()
        DauControl.neuralResidualPeak = neuralResidualPeak
        DauControl.detectedCutoffHz = (airCutoffHz[0] + airCutoffHz[1]) * .5f
        DauControl.openedGateBins = openedGateBins
        if (DauControl.autoStrength) { val target=(1.08f-lift/48f).coerceIn(.76f,1.12f);DauControl.analysisFactor=DauControl.analysisFactor*.94f+target*.06f }
        DauControl.inferenceActive = true
        return renderedBlock
    }

    private fun scheduleInference(frameId: Long) {
        val backend = sessionV2 ?: return
        if (!inferenceQueued.compareAndSet(false, true)) return
        var cursor = 0
        for (ch in 0..1) for (f in 0..3) for (t in 0 until 32) for (j in 0 until highCount) {
            modelInput[cursor++] = temporal[ch][f][t][j]
        }
        worker.execute {
            val started = System.nanoTime()
            try {
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(modelInput), longArrayOf(2, 4, 32, highCount.toLong())).use { tensor ->
                    backend.session.run(mapOf(backend.session.inputNames.first() to tensor)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val value = result[0].value as Array<Array<Array<FloatArray>>>
                        val ready = FloatArray(2 * highCount)
                        for (ch in 0..1) for (j in 0 until highCount) ready[ch * highCount + j] = value[ch][0][31][j] * 5f
                        publishedResidual.set(ready)
                        residualSlots[(frameId % residualSlots.size).toInt()].set(ready)
                    }
                }
                val elapsed = (System.nanoTime() - started) / 1_000_000f
                DauControl.processingMs = if (DauControl.processingMs == 0f) elapsed else DauControl.processingMs * .85f + elapsed * .15f
                DauControl.inferenceActive = true
            } catch (t: Throwable) {
                // Runtime accelerator resets are audible only if processing blocks.
                // Keep dry audio flowing and expose the failure for diagnostics.
                DauControl.backendDiagnostics = "${backend.label} runtime: ${t.message ?: t.javaClass.simpleName}"
                DauControl.inferenceActive = false
                AppLogger.w("DAU_INFERENCE", DauControl.backendDiagnostics, t)
            } finally {
                inferenceQueued.set(false)
            }
        }
    }

    /** SIGNAL//LAB DAU 1.0.1: adaptive five-band spectral restoration, no neural model. */
    private fun processClassicDau(strength: Float, started: Long): Array<FloatArray> {
        val rate = inputAudioFormat.sampleRate.toDouble()
        var body = 0.0; var air = 0.0; var bodyBins = 0; var airBins = 0
        for (ch in 0..1) for (k in 1..n/2) {
            val hz = k * rate / n
            val power = real[ch][k] * real[ch][k] + imag[ch][k] * imag[ch][k]
            if (hz in 500.0..5999.99) { body += power; bodyBins++ }
            else if (hz in 8000.0..19000.0) { air += power; airBins++ }
        }
        if (bodyBins > 0 && airBins > 0 && body / bodyBins >= 2.0) {
            val ratio = (air / airBins) / (body / bodyBins + 1e-9)
            val target = (1.24 - .24 * log10(1 + ratio * 24)).coerceIn(.68, 1.38).toFloat()
            classicSpectralFactor = classicSpectralFactor * .88f + target * .12f
        }
        val centers = doubleArrayOf(60.0, 250.0, 1000.0, 4000.0, 12000.0)
        val bandDb = doubleArrayOf(-.3, -.1, .28, 1.75 * classicSpectralFactor, 3.65 * classicSpectralFactor)
        var inputHigh = 0.0; var outputHigh = 0.0
        val levelDb = .45 + 1.2 * strength
        for (ch in 0..1) {
            for (k in 1..n/2) {
                val hz = k * rate / n
                var gainDb = levelDb
                for (i in centers.indices) {
                    val oct = ln(hz / centers[i]) / ln(2.0)
                    val width = if (i >= 3) .62 else .9
                    gainDb += bandDb[i] * strength * exp(-(oct * oct) / (2 * width * width))
                }
                if (hz >= 8000) {
                    val j = (k - highStart).coerceIn(0, highCount - 1)
                    gainDb += min(.45 * strength, temporal[ch][2][31][j] * .35)
                }
                val scale = 10.0.pow(gainDb / 20.0)
                val old = hypot(real[ch][k], imag[ch][k])
                if (hz >= 8000) inputHigh += old * old
                real[ch][k] *= scale; imag[ch][k] *= scale
                if (hz >= 8000) outputHigh += old * old * scale * scale
                if (k < n/2) { real[ch][n-k] = real[ch][k]; imag[ch][n-k] = -imag[ch][k] }
            }
            fft(real[ch], imag[ch], true)
            for (i in 0 until n) ola[ch][i] += real[ch][i].toFloat() * window[i]
        }
        for (ch in 0..1) ola[ch].copyInto(renderedBlock[ch], 0, 0, hop)
        for (ch in 0..1) { ola[ch].copyInto(ola[ch], 0, hop, n); java.util.Arrays.fill(ola[ch], hop, n, 0f) }
        val elapsed = (System.nanoTime() - started) / 1_000_000f
        DauControl.processingMs = DauControl.processingMs * .9f + elapsed * .1f
        val lift = (10 * log10((outputHigh + 1e-12) / (inputHigh + 1e-12))).toFloat().coerceIn(0f, 24f)
        DauControl.highBandLiftDb = DauControl.highBandLiftDb * .85f + lift * .15f
        DauControl.inferenceActive = true
        return renderedBlock
    }

    private fun resetState() {
        pending.clear()
        snapshots.clear()
        for (slot in residualSlots) slot.set(null)
        analysisFrameId = 0
        lastLookahead = -1
        DauControl.residualMisses = 0
        DauControl.lookaheadLatencyMs = 0f
        inferenceFrame = 0
        classicSpectralFactor = 1f
        transitionFrames = 4
        for (ch in 0..1) { history[ch].fill(0f); ola[ch].fill(0f); previousHigh[ch].fill(0f); residual[ch].fill(0f); targetResidual[ch].fill(0f); temporalGenerated[ch].fill(0f); frequencyEnvelope[ch].fill(0f); projectedGenerated[ch].fill(0f); generatedLogState[ch].fill(Float.NaN); generatedPhase[ch].fill(Float.NaN); previousSourcePhase[ch].fill(Float.NaN); sourcePhaseReliability[ch].fill(0f); smoothingReady[ch] = false; airEnvelopeLog[ch] = Float.NaN; airSlopeLogPerHz[ch] = (-2.326 / 8.685889 / 1000.0).toFloat(); airBandFast[ch].fill(Float.NaN); airBandSlow[ch].fill(Float.NaN); airCutoffHz[ch] = 22_000f; airAnalysisFrames[ch] = 0; airCutoffCandidate[ch] = 22_000f; airCutoffVotes[ch] = 0; airPhase[ch].fill(Float.NaN); for (f in 0..3) for (t in 0 until 32) temporal[ch][f][t].fill(0f) }
    }

    private fun wrapPhase(value: Float): Float = atan2(sin(value), cos(value))

    private fun updateAirCutoff(channel: Int) {
        for (band in 0 until 10) {
            val measured = bandMeanLogMagnitude(channel, 12_000f + band * 1_000f, 13_000f + band * 1_000f)
            airBandFast[channel][band] = if (airBandFast[channel][band].isFinite()) airBandFast[channel][band] * .52f + measured * .48f else measured
            airBandSlow[channel][band] = if (airBandSlow[channel][band].isFinite()) airBandSlow[channel][band] * .90f + measured * .10f else measured
        }
        airAnalysisFrames[channel]++
        // One decision every 512 samples: 10.7 ms at 48 kHz (11.6 ms at 44.1).
        // Two matching decisions are sufficient, reducing the old 0.25 s wait to
        // roughly 21-23 ms while dual-time-constant envelopes reject single hits.
        if (airAnalysisFrames[channel] < 2) return
        val fast = detectAirCutoff(airBandFast[channel], airSlopeLogPerHz[channel])
        val slow = detectAirCutoff(airBandSlow[channel], airSlopeLogPerHz[channel])
        val detected = if (airAnalysisFrames[channel] < 8 || abs(fast - slow) <= 1_000f) fast else min(fast, slow)
        if (abs(detected - airCutoffCandidate[channel]) <= 750f) airCutoffVotes[channel]++
        else { airCutoffCandidate[channel] = detected; airCutoffVotes[channel] = 1 }
        if (airCutoffVotes[channel] < 2) return
        val alpha = if (detected < airCutoffHz[channel]) .65f else .06f
        airCutoffHz[channel] += (detected - airCutoffHz[channel]) * alpha
    }

    private fun detectAirCutoff(bands: FloatArray, slope: Float): Float {
        // Do not classify silence or an isolated low-level frame as a codec cliff.
        if (!bands[0].isFinite() || max(bands[0], bands[1]) < -13f) return 22_000f
        var detected = 22_000f
        for (index in 2 until 9) {
            val expected = (bands[index - 2] + bands[index - 1]) * .5f + slope * 1_500f
            val expectedNext = expected + slope * 1_000f
            if (bands[index] < expected - 1.0f && bands[index + 1] < expectedNext - 1.0f) {
                detected = 11_500f + index * 1_000f
                break
            }
        }
        return detected
    }

    private fun bandMeanLogMagnitude(channel: Int, fromHz: Float, toHz: Float): Float {
        val rate = inputAudioFormat.sampleRate.toFloat()
        val first = ceil(fromHz * n / rate).toInt().coerceIn(1, n / 2)
        val last = floor(toHz * n / rate).toInt().coerceIn(first, n / 2)
        var sumSquares = 0.0
        for (k in first..last) {
            val magnitude = hypot(real[channel][k], imag[channel][k])
            sumSquares += magnitude * magnitude
        }
        // RMS follows the visible spectral envelope. A geometric mean treated
        // normal narrow-bin notches as the envelope and placed the continuation
        // 10-15 dB too low, recreating a cliff at the codec boundary.
        return ln(max(sqrt(sumSquares / (last - first + 1)), 1e-9)).toFloat()
    }

    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        var j = 0
        for (i in 1 until n) { var bit = n shr 1; while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit; if (i < j) { val tr=re[i];re[i]=re[j];re[j]=tr;val ti=im[i];im[i]=im[j];im[j]=ti } }
        var len = 2
        while (len <= n) {
            val angle = 2 * PI / len * if (inverse) 1 else -1
            val wr0=cos(angle); val wi0=sin(angle)
            for (i in 0 until n step len) { var wr=1.0;var wi=0.0;for(k in 0 until len/2){val u=i+k;val v=u+len/2;val vr=re[v]*wr-im[v]*wi;val vi=re[v]*wi+im[v]*wr;re[v]=re[u]-vr;im[v]=im[u]-vi;re[u]+=vr;im[u]+=vi;val nw=wr*wr0-wi*wi0;wi=wr*wi0+wi*wr0;wr=nw} }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}
