package com.decent.usbaudio

/** UAC1/UAC2 Format Type I values for one AudioStreaming alternate setting. */
data class UacAltFormat(
    val altSetting: Int,
    val bitDepth: Int,
    val subframeSize: Int,
    val sampleRates: List<Int> = emptyList(),
    val interfaceId: Int = -1
) {
    /** Width of each sample on the USB endpoint (not necessarily its valid bits). */
    val transportBitDepth: Int get() = subframeSize * 8
}

/** Parses class-specific AS_FORMAT_TYPE descriptors without Android dependencies. */
object UacFormatDescriptorParser {
    fun parse(raw: ByteArray?): List<UacAltFormat> {
        if (raw == null) return emptyList()
        val result = mutableListOf<UacAltFormat>()
        var offset = 0
        var currentAlt = -1
        var currentInterfaceId = -1
        var currentInterfaceProtocol = 0
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
                        currentInterfaceId = if (inAudioStreaming) {
                            raw[offset + 2].toInt() and 0xff
                        } else {
                            -1
                        }
                        currentAlt = if (inAudioStreaming) {
                            raw[offset + 3].toInt() and 0xff
                        } else {
                            -1
                        }
                        currentInterfaceProtocol = if (inAudioStreaming) raw[offset + 7].toInt() and 0xff else 0
                    }
                }
                0x24 -> { // CS_INTERFACE
                    if (inAudioStreaming && currentAlt > 0 &&
                        length >= (if (currentInterfaceProtocol == 0) 8 else 6) &&
                        (raw[offset + 2].toInt() and 0xff) == 0x02 && // AS_FORMAT_TYPE
                        (raw[offset + 3].toInt() and 0xff) == 0x01    // FORMAT_TYPE_I
                    ) {
                        // UAC1 inserts channel count at byte 4 and appends
                        // discrete/continuous rates. UAC2 uses the compact
                        // subslot/bit-resolution fields from byte 4.
                        val uac1 = currentInterfaceProtocol == 0
                        val subframeSize = raw[offset + if (uac1) 5 else 4].toInt() and 0xff
                        val bitDepth = raw[offset + if (uac1) 6 else 5].toInt() and 0xff
                        val sampleRates = if (uac1) {
                            parseUac1SampleRates(raw, offset, length)
                        } else {
                            emptyList()
                        }
                        if (subframeSize in 1..4 && bitDepth in 1..subframeSize * 8) {
                            result.add(UacAltFormat(currentAlt, bitDepth, subframeSize, sampleRates, currentInterfaceId))
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
