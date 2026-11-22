package com.jin.dacprobe

/**
 * Verbatim copy of the UAC1/UAC2 Format Type I parser used by the main app
 * (decent-libs/decent-usb-audio-driver .../UacFormatDescriptorParser.kt).
 * Kept dependency-free so this probe stays minimal.
 */
data class UacAltFormat(
    val altSetting: Int,
    val bitDepth: Int,
    val subframeSize: Int,
    val sampleRates: List<Int> = emptyList()
) {
    val transportBitDepth: Int get() = subframeSize * 8
}

object UacFormatDescriptorParser {
    fun parse(raw: ByteArray?): List<UacAltFormat> {
        if (raw == null) return emptyList()
        val result = mutableListOf<UacAltFormat>()
        var offset = 0
        var currentAlt = -1
        var inAudioStreaming = false

        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) break
            when (raw[offset + 1].toInt() and 0xff) {
                0x04 -> { // INTERFACE
                    if (length >= 9) {
                        val interfaceClass = raw[offset + 5].toInt() and 0xff
                        val interfaceSubclass = raw[offset + 6].toInt() and 0xff
                        inAudioStreaming = interfaceClass == 1 && interfaceSubclass == 2
                        currentAlt = if (inAudioStreaming) {
                            raw[offset + 3].toInt() and 0xff
                        } else {
                            -1
                        }
                    }
                }
                0x24 -> { // CS_INTERFACE
                    if (inAudioStreaming && currentAlt > 0 && length >= 6 &&
                        (raw[offset + 2].toInt() and 0xff) == 0x02 && // AS_FORMAT_TYPE
                        (raw[offset + 3].toInt() and 0xff) == 0x01    // FORMAT_TYPE_I
                    ) {
                        val uac1 = length >= 8
                        val subframeSize = raw[offset + if (uac1) 5 else 4].toInt() and 0xff
                        val bitDepth = raw[offset + if (uac1) 6 else 5].toInt() and 0xff
                        val sampleRates = if (uac1) {
                            parseUac1SampleRates(raw, offset, length)
                        } else {
                            emptyList()
                        }
                        if (subframeSize in 1..4 && bitDepth in 1..subframeSize * 8) {
                            result.add(UacAltFormat(currentAlt, bitDepth, subframeSize, sampleRates))
                        }
                    }
                }
            }
            offset += length
        }
        return result
    }

    private fun parseUac1SampleRates(raw: ByteArray, offset: Int, length: Int): List<Int> {
        val frequencyType = raw[offset + 7].toInt() and 0xff
        if (frequencyType !in 1..15) return emptyList()
        val rates = mutableListOf<Int>()
        var position = offset + 8
        repeat(frequencyType) {
            if (position + 3 <= offset + length) {
                rates.add(
                    (raw[position].toInt() and 0xff) or
                        ((raw[position + 1].toInt() and 0xff) shl 8) or
                        ((raw[position + 2].toInt() and 0xff) shl 16)
                )
            }
            position += 3
        }
        return rates
    }
}

/** Minimal copy of the CLOCK_SOURCE entity-ID scan (main app UsbAudioDevice). */
object UacClockSourceParser {
    fun parse(raw: ByteArray?): Int {
        if (raw == null) return -1
        var i = 0
        var inAudioControl = false
        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break
            val bDescriptorType = raw[i + 1].toInt() and 0xFF
            if (bDescriptorType == 0x04 && bLength >= 9) {
                val cls = raw[i + 5].toInt() and 0xFF
                val sub = raw[i + 6].toInt() and 0xFF
                inAudioControl = (cls == 1 && sub == 1)
            }
            if (inAudioControl && bDescriptorType == 0x24 && bLength >= 3) {
                if ((raw[i + 2].toInt() and 0xFF) == 0x0A && bLength >= 5) {
                    return raw[i + 3].toInt() and 0xFF
                }
            }
            i += bLength
        }
        return -1
    }
}

/** Replicates the main app's alt-selection semantics for diagnosis. */
object AltSelector {
    fun best(formats: List<UacAltFormat>): UacAltFormat =
        formats.maxWithOrNull(
            compareBy<UacAltFormat> { it.transportBitDepth }.thenBy { it.bitDepth }
        ) ?: UacAltFormat(1, 16, 2)

    fun findForBitDepth(formats: List<UacAltFormat>, target: Int): UacAltFormat {
        formats.filter { it.bitDepth == target }
            .minByOrNull { it.transportBitDepth }
            ?.let { return it }
        formats.filter { it.bitDepth > target && it.transportBitDepth >= target }
            .minWithOrNull(compareBy<UacAltFormat> { it.bitDepth }.thenBy { it.transportBitDepth })
            ?.let { return it }
        return best(formats)
    }
}
