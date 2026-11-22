package com.decent.usbaudio

import org.junit.Assert.assertEquals
import org.junit.Test

class UacFormatDescriptorParserTest {
    @Test
    fun readsValidBitsAndTransportSlotWidthFromTheCorrectFields() {
        val raw = streamingInterface(alt = 1) + formatTypeI(channels = 2, subframe = 2, bits = 16) +
            streamingInterface(alt = 2) + formatTypeI(channels = 2, subframe = 4, bits = 24) +
            streamingInterface(alt = 3) + formatTypeI(channels = 2, subframe = 4, bits = 32)

        val formats = UacFormatDescriptorParser.parse(raw)

        assertEquals(3, formats.size)
        assertEquals(UacAltFormat(1, 16, 2, emptyList(), 1), formats[0])
        assertEquals(16, formats[0].transportBitDepth)
        assertEquals(UacAltFormat(2, 24, 4, emptyList(), 1), formats[1])
        assertEquals(32, formats[1].transportBitDepth)
        assertEquals(UacAltFormat(3, 32, 4, emptyList(), 1), formats[2])
    }

    @Test
    fun readsSixByteUac2FormatDescriptors() {
        val raw = streamingInterface(alt = 1, protocol = 0x20) +
            byteArrayOf(6, 0x24, 2, 1, 3, 24) +
            streamingInterface(alt = 2, protocol = 0x20) +
            byteArrayOf(6, 0x24, 2, 1, 4, 32)

        val formats = UacFormatDescriptorParser.parse(raw)

        assertEquals(listOf(UacAltFormat(1, 24, 3, emptyList(), 1), UacAltFormat(2, 32, 4, emptyList(), 1)), formats)
    }

    @Test
    fun parsesUac1RatesAndDoesNotMisclassifyUac2ByDescriptorLength() {
        val uac1 = streamingInterface(alt = 1) + byteArrayOf(
            11, 0x24, 2, 1, 2, 3, 24, 1,
            0x44, 0xAC.toByte(), 0
        )
        val uac2 = streamingInterface(alt = 2, protocol = 0x20) + byteArrayOf(8, 0x24, 2, 1, 2, 16, 0, 0)
        val uac2Fixed = uac2.copyOfRange(0, 9) + byteArrayOf(8, 0x24, 2, 1, 2, 16, 0, 0)

        val formats = UacFormatDescriptorParser.parse(uac1 + uac2Fixed)

        assertEquals(listOf(44_100), formats[0].sampleRates)
        assertEquals(UacAltFormat(2, 16, 2, emptyList(), 1), formats[1])
    }

    @Test
    fun ignoresNonStreamingAndMalformedDescriptors() {
        val controlInterface = byteArrayOf(9, 4, 0, 0, 0, 1, 1, 1, 0)
        val raw = controlInterface + formatTypeI(channels = 2, subframe = 4, bits = 32) +
            streamingInterface(alt = 1) + byteArrayOf(3, 0x24, 2)

        assertEquals(emptyList<UacAltFormat>(), UacFormatDescriptorParser.parse(raw))
        assertEquals(emptyList<UacAltFormat>(), UacFormatDescriptorParser.parse(null))
    }

    private fun streamingInterface(alt: Int, protocol: Int = 0): ByteArray = byteArrayOf(
        9, 4, 1, alt.toByte(), 2, 1, 2, protocol.toByte(), 0
    )

    private fun formatTypeI(channels: Int, subframe: Int, bits: Int): ByteArray = byteArrayOf(
        11, 0x24, 2, 1, channels.toByte(), subframe.toByte(), bits.toByte(), 0,
        0x44, 0xAC.toByte(), 0
    )
}
