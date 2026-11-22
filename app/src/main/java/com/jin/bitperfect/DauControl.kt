package com.jin.bitperfect

import java.util.concurrent.atomic.AtomicBoolean

object DauControl {
    const val MODEL_V1 = 1
    const val MODEL_V2 = 2
    val userEnabled = AtomicBoolean(true)
    val streamingActive = AtomicBoolean(false)
    @Volatile var strength = 0.68f
    @Volatile var autoStrength = true
    @Volatile var sourceProfile = "STREAM"
    @Volatile var analysisFactor = 1f
    @Volatile var modelVersion = MODEL_V2
    @Volatile var backend = "INITIALIZING"
    @Volatile var lastError = ""
    @Volatile var processingMs = 0f
    @Volatile var backendBenchmarkMs = 0f
    @Volatile var backendDiagnostics = ""
    @Volatile var highBandLiftDb = 0f
    @Volatile var generatedCandidateRms = 0f
    @Volatile var generatedOutputRms = 0f
    @Volatile var neuralResidualPeak = 0f
    @Volatile var detectedCutoffHz = 22_000f
    @Volatile var openedGateBins = 0
    @Volatile var inferenceActive = false
    /** Fixed per-hop residual envelope smoothing. */
    @Volatile var residualSmoothing = 0.15f
    /** Fixed pipeline delay in hops. 0 keeps the stable inline processing path. */
    @Volatile var lookaheadHops = 0
    /** Measured output latency from the lookahead queue (ms). */
    @Volatile var lookaheadLatencyMs = 0f
    /** Count of frames where the delayed inference result was not ready. */
    @Volatile var residualMisses = 0L
    /** Count of audio callbacks that underran the lookahead output queue. */
    @Volatile var audioUnderruns = 0L

    val active: Boolean
        get() = userEnabled.get() && streamingActive.get() && !StreamingDspRuntime.state.enabled && lastError.isEmpty()

    val modelLabel: String get() = if (modelVersion == MODEL_V1) "DAU 1.0.1" else "AI DAU · TEMPORAL 1.5M"
    val parameterLabel: String get() = if (modelVersion == MODEL_V1) "NON-AI" else "1.6008M"
    val effectiveStrength: Float get() {
        if (!autoStrength) return strength.coerceIn(0f,1f)
        val profile = when(sourceProfile.lowercase()) { "mp3" -> 1.0f; "aac","m4a" -> .9f; "opus","ogg" -> .78f; "stream" -> .86f; else -> .82f }
        return (strength * profile * analysisFactor.coerceIn(.72f,1.18f)).coerceIn(.12f,1f)
    }
}
