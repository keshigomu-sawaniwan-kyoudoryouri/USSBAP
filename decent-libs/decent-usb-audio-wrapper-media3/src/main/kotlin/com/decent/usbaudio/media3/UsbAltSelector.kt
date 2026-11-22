package com.decent.usbaudio.media3

import com.decent.usbaudio.UsbAudioDeviceInfo
import android.util.Log

/**
 * Selects a UAC2 alternate setting that actually supports the current sample
 * rate. The original sink always used the DAC's highest bit-depth alternate
 * setting, which makes 44.1 kHz play at double speed on DACs whose highest
 * alternate setting only advertises 88.2/96 kHz and above.
 */
internal object UsbAltSelector {
    private const val TAG = "UsbAltSelector"

    data class Alt(
        val alt: Int,
        val bitDepth: Int,
        val sampleRates: List<Int>
    )

    fun select(info: UsbAudioDeviceInfo, sampleRate: Int, desiredBitDepth: Int = 0): Alt? {
        val parsed = parse(info.connection?.rawDescriptors)
        if (parsed.isEmpty()) return null

        val atRate = parsed.filter {
            it.bitDepth in 16..32 && (it.sampleRates.isEmpty() || sampleRate in it.sampleRates)
        }
        // Prefer the source bit depth so a 16-bit FLAC is not forced through a
        // 32-bit slot. This avoids frame-size/padding mistakes that make
        // 16/44.1 run at double speed.
        val exact = atRate
            .sortedWith(
                compareByDescending<Alt> { if (desiredBitDepth > 0 && it.bitDepth == desiredBitDepth) 1 else 0 }
                    .thenByDescending { it.bitDepth }
                    .thenByDescending { it.alt }
            )
        if (exact.isNotEmpty()) {
            Log.i(TAG, "rate=$sampleRate desired=$desiredBitDepth selected alt=${exact.first().alt} ${exact.first().bitDepth}bit")
            return exact.first()
        }

        // Fallback: closest advertised rate with the highest bit depth.
        val nearest = parsed
            .filter { it.bitDepth in 16..32 && it.sampleRates.isNotEmpty() }
            .minByOrNull { alt ->
                alt.sampleRates.minOf { kotlin.math.abs(it - sampleRate) }
            }
        if (nearest != null) {
            Log.w(TAG, "rate=$sampleRate no exact alt; using alt=${nearest.alt} ${nearest.bitDepth}bit rates=${nearest.sampleRates}")
        }
        return nearest
    }

    private fun parse(raw: ByteArray?): List<Alt> {
        if (raw == null || raw.size < 18) return emptyList()
        val result = mutableListOf<Alt>()
        var i = 0
        var currentAlt = -1
        var streaming = false
        while (i + 2 <= raw.size) {
            val len = raw[i].toInt() and 0xff
            if (len < 2 || i + len > raw.size) break
            val type = raw[i + 1].toInt() and 0xff
            when (type) {
                4 -> { // INTERFACE
                    if (len >= 9) {
                        val cls = raw[i + 5].toInt() and 0xff
                        val sub = raw[i + 6].toInt() and 0xff
                        streaming = cls == 1 && sub == 2
                        currentAlt = if (streaming) raw[i + 3].toInt() and 0xff else -1
                    }
                }
                0x24 -> { // CS_INTERFACE
                    if (streaming && len >= 8 && currentAlt >= 0 &&
                        (raw[i + 2].toInt() and 0xff) == 2
                    ) {
                        val formatType = raw[i + 3].toInt() and 0xff
                        if (formatType == 1) {
                            val channels = raw[i + 4].toInt() and 0xff
                            val bits = raw[i + 6].toInt() and 0xff
                            val freqType = raw[i + 7].toInt() and 0xff
                            val rates = mutableListOf<Int>()
                            if (channels in 1..2 && freqType in 1..15) {
                                var p = i + 8
                                repeat(freqType) {
                                    if (p + 3 <= i + len) {
                                        rates.add(
                                            (raw[p].toInt() and 0xff) or
                                                ((raw[p + 1].toInt() and 0xff) shl 8) or
                                                ((raw[p + 2].toInt() and 0xff) shl 16)
                                        )
                                    }
                                    p += 3
                                }
                            }
                            result.add(Alt(currentAlt, bits, rates))
                        }
                    }
                }
            }
            i += len
        }
        return result
    }
}
