package com.decent.usbaudio.media3

/**
 * Configuration for [UsbAudioSink].
 *
 * @param bitPerfectEnabled When true, audio is sent directly to the USB DAC
 *        bypassing the Android audio stack entirely.
 * @param forceRouteToSpeaker When true, the delegate AudioTrack is routed to
 *        the built-in speaker to prevent AudioFlinger from opening the USB device.
 */
data class UsbAudioSinkConfig(
    @Volatile var bitPerfectEnabled: Boolean = true,
    val forceRouteToSpeaker: Boolean = true,
    @Volatile var softwareVolumeEnabled: Boolean = false,
    @Volatile var softwareVolume: Float = 0.5f,
    @Volatile var streamStateListener: ((active: Boolean, sampleRate: Int, bitDepth: Int, deviceName: String?) -> Unit)? = null
)
