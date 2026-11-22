package com.jin.bitperfect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DsdSourceTest {
    @Test
    fun parsesDsfHeaderAndReadsBlocks() {
        val file = File.createTempFile("ussbap-dsd-", ".dsf")
        try {
            val left = byteArrayOf(0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80.toByte())
            val right = byteArrayOf(0x11, 0x22, 0x44, 0x88.toByte(), 0x00, 0x00, 0x00, 0x00)
            // DSF blocks are per-channel: L0, R0, L1, R1.
            val data = left.copyOfRange(0, 4) + right.copyOfRange(0, 4) +
                left.copyOfRange(4, 8) + right.copyOfRange(4, 8)
            val fmt = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(1)          // version
                putInt(0)          // format id
                putInt(2)          // channel type
                putInt(2)          // channels
                putInt(2_822_400)  // DSD sample rate
                putInt(1)          // bits per sample
                putLong(64)        // samples per channel
                putInt(4)          // block size per channel
                putInt(0)          // reserved
            }.array()
            val fmtChunk = "fmt ".toByteArray() + leLong(12L + fmt.size) + fmt
            val dataChunk = "data".toByteArray() + leLong(12L + data.size) + data
            val total = 28L + fmtChunk.size + dataChunk.size
            val dsdChunk = "DSD ".toByteArray() + leLong(28L) + leLong(total) + leLong(0L)
            file.writeBytes(dsdChunk + fmtChunk + dataChunk)

            FileInputStream(file).use { input ->
                val info = DsdParser.parse(input.channel, file.length())
                assertNotNull(info)
                info!!
                assertEquals(DsdContainer.DSF, info.container)
                assertEquals(2_822_400, info.sampleRate)
                assertEquals(2, info.channels)
                assertEquals(4, info.blockSize)
                assertEquals(1000L * 64 / 2_822_400, info.durationMs)
                assertEquals(16L, info.dataLength)

                val reader = DsdStreamReader(input.channel, info, 0)
                val chunk = reader.read(2, 2)
                assertNotNull(chunk)
                assertArrayEquals(byteArrayOf(0x80.toByte(), 0x40, 0x20, 0x10), chunk!![0])
                assertArrayEquals(byteArrayOf(0x88.toByte(), 0x44, 0x22, 0x11), chunk[1])
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun parsesDffAndDeinterleavesBytes() {
        val file = File.createTempFile("ussbap-dsd-", ".dff")
        try {
            val data = byteArrayOf(0x11, 0x22, 0x33, 0x44)
            val fs = dffChunk("FS  ", ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2_822_400).array())
            val chnl = dffChunk("CHNL", ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN).apply {
                putShort(2)
                put("L   ".toByteArray())
                put("R   ".toByteArray())
            }.array())
            val cmpr = dffChunk("CMPR", "DSD ".toByteArray() + byteArrayOf(3) + "DSD".toByteArray())
            val unknownOdd = dffChunk("ODD ", byteArrayOf(0x55))
            val prop = dffChunk("PROP", "SND ".toByteArray() + unknownOdd + fs + chnl + cmpr)
            val dsd = dffChunk("DSD ", data)
            file.writeBytes(dffChunk("FRM8", "DSD ".toByteArray() + prop + dsd))

            FileInputStream(file).use { input ->
                val info = DsdParser.parse(input.channel, file.length())
                assertNotNull(info)
                info!!
                assertEquals(DsdContainer.DFF, info.container)
                assertEquals(2_822_400, info.sampleRate)
                assertEquals(2, info.channels)
                assertEquals(false, info.lsbFirst)
                val reader = DsdStreamReader(input.channel, info, 0)
                val chunk = reader.read(1, 2)
                assertNotNull(chunk)
                assertArrayEquals(byteArrayOf(0x11, 0x33), chunk!![0])
                assertArrayEquals(byteArrayOf(0x22, 0x44), chunk[1])
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun packsDopAndNativeInTheExpectedByteOrder() {
        val left = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val right = byteArrayOf(0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte())
        val dop = ByteArray(12)
        assertEquals(12, DsdPacker.packDop(left, right, 2, dop))
        assertArrayEquals(
            byteArrayOf(
                0x34, 0x12, 0x05,
                0xBC.toByte(), 0x9A.toByte(), 0x05,
                0x78, 0x56, 0xFA.toByte(),
                0xF0.toByte(), 0xDE.toByte(), 0xFA.toByte()
            ),
            dop
        )
        val native = ByteArray(8)
        assertEquals(8, DsdPacker.packNative(left, right, 1, native))
        assertArrayEquals(
            byteArrayOf(
                0x12, 0x34, 0x56, 0x78,
                0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte()
            ),
            native
        )
    }

    private fun leLong(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun dffChunk(id: String, payload: ByteArray): ByteArray =
        id.toByteArray() +
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(payload.size.toLong()).array() +
            payload + if (payload.size % 2 == 0) ByteArray(0) else byteArrayOf(0)
}
