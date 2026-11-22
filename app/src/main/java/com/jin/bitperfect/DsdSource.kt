package com.jin.bitperfect

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

enum class DsdContainer {
    DSF,
    DFF
}

/**
 * Header information for a DSF / DSDIFF file. The payload is deliberately not
 * decoded to PCM: DoP and native DSD both need the original 1-bit stream.
 */
data class DsdInfo(
    val container: DsdContainer,
    val sampleRate: Int,
    val channels: Int,
    val dataOffset: Long,
    val dataLength: Long,
    val blockSize: Int,
    val lsbFirst: Boolean,
    val compressed: Boolean,
    val declaredSamplesPerChannel: Long
) {
    val bytesPerChannel: Long
        get() = if (channels <= 0) 0L else dataLength / channels

    val durationMs: Long
        get() {
            val bits = when {
                declaredSamplesPerChannel > 0L -> declaredSamplesPerChannel
                else -> bytesPerChannel * 8L
            }
            return if (sampleRate > 0) bits * 1000L / sampleRate else 0L
        }

    val dopSampleRate: Int get() = sampleRate / 16
    val nativeSampleRate: Int get() = sampleRate / 32

    val dsdLabel: String get() = dsdRateLabel(sampleRate)

    companion object {
        fun dsdRateLabel(rate: Int): String = when (rate) {
            2_822_400 -> "DSD64"
            5_644_800 -> "DSD128"
            11_289_600 -> "DSD256"
            22_579_200 -> "DSD512"
            45_158_400 -> "DSD1024"
            3_072_000 -> "DSD64/48k"
            6_144_000 -> "DSD128/48k"
            12_288_000 -> "DSD256/48k"
            24_576_000 -> "DSD512/48k"
            49_152_000 -> "DSD1024/48k"
            else -> if (rate > 0) "DSD${"%.3f".format(rate / 44_100.0 / 64.0)}" else "DSD"
        }

        fun isDsdExtension(ext: String): Boolean =
            ext.equals("dsf", true) || ext.equals("dff", true) || ext.equals("dsdiff", true)
    }
}

object DsdParser {
    private const val TAG = "DSD"

    fun parse(file: File): DsdInfo? = try {
        FileInputStream(file).use { parse(it.channel, file.length()) }
    } catch (t: Throwable) {
        AppLogger.w(TAG, "Cannot parse ${file.name}", t)
        null
    }

    /**
     * Parses only the small header area. The caller owns [channel].
     */
    fun parse(channel: FileChannel, fileSize: Long): DsdInfo? {
        if (fileSize < 12L) return null
        val head = read(channel, 0L, 12) ?: return null
        return when {
            head[0] == 'D'.code.toByte() && head[1] == 'S'.code.toByte() &&
                head[2] == 'D'.code.toByte() && head[3] == ' '.code.toByte() -> parseDsf(channel, fileSize)
            head[0] == 'F'.code.toByte() && head[1] == 'R'.code.toByte() &&
                head[2] == 'M'.code.toByte() && head[3] == '8'.code.toByte() -> parseDff(channel, fileSize)
            else -> null
        }
    }

    private fun parseDsf(channel: FileChannel, fileSize: Long): DsdInfo? {
        val dsdHeader = read(channel, 0L, 28) ?: return null
        if (dsdHeader[0] != 'D'.code.toByte() || dsdHeader[1] != 'S'.code.toByte() ||
            dsdHeader[2] != 'D'.code.toByte() || dsdHeader[3] != ' '.code.toByte()
        ) return null

        var pos = 28L
        var sampleRate = 0
        var channels = 0
        var blockSize = 0
        var declaredSamples = 0L
        var dataOffset = -1L
        var dataLength = 0L
        var guard = 0
        while (pos + 12L <= fileSize && guard++ < 256) {
            val header = read(channel, pos, 12) ?: break
            val id = String(header, 0, 4, Charsets.ISO_8859_1)
            val chunkSize = leLong(header, 4)
            if (chunkSize < 12L || pos + chunkSize > fileSize) break
            when (id) {
                "fmt " -> {
                    val fmt = read(channel, pos + 12L, 40) ?: break
                    // version, format id, channel type, channel number,
                    // sample rate, bits/sample, sample count, block size,
                    // reserved
                    channels = leInt(fmt, 12).coerceIn(1, 8)
                    sampleRate = leInt(fmt, 16)
                    declaredSamples = leLong(fmt, 24)
                    blockSize = leInt(fmt, 32).coerceAtLeast(1)
                }
                "data" -> {
                    dataOffset = pos + 12L
                    dataLength = chunkSize - 12L
                }
            }
            pos += chunkSize
        }

        if (sampleRate <= 0 || channels <= 0 || dataOffset < 0L || dataLength <= 0L) return null
        return DsdInfo(
            container = DsdContainer.DSF,
            sampleRate = sampleRate,
            channels = channels,
            dataOffset = dataOffset,
            dataLength = dataLength,
            blockSize = if (blockSize > 0) blockSize else 4096,
            lsbFirst = true,
            compressed = false,
            declaredSamplesPerChannel = declaredSamples
        )
    }

    private fun parseDff(channel: FileChannel, fileSize: Long): DsdInfo? {
        val frm = read(channel, 0L, 16) ?: return null
        val form = String(frm, 12, 4, Charsets.ISO_8859_1)
        if (form != "DSD ") return null

        var pos = 16L
        var sampleRate = 0
        var channels = 0
        var compressed = false
        var dataOffset = -1L
        var dataLength = 0L
        var guard = 0
        while (pos + 12L <= fileSize && guard++ < 256) {
            val header = read(channel, pos, 12) ?: break
            val id = String(header, 0, 4, Charsets.ISO_8859_1)
            // DSDIFF ckSize is the payload length, excluding this 12-byte
            // header. Chunks are padded to an even physical boundary.
            val payloadSize = beLong(header, 4)
            val chunkEnd = pos + 12L + payloadSize
            if (payloadSize < 0L || chunkEnd > fileSize) break
            when (id) {
                "PROP" -> {
                    val propEnd = chunkEnd
                    var sub = pos + 12L
                    // PROP starts with a four-byte form type ("SND ").
                    if (sub + 4L <= propEnd) sub += 4L
                    var subGuard = 0
                    while (sub + 12L <= propEnd && subGuard++ < 128) {
                        val sh = read(channel, sub, 12) ?: break
                        val sid = String(sh, 0, 4, Charsets.ISO_8859_1)
                        val subPayloadSize = beLong(sh, 4)
                        val subEnd = sub + 12L + subPayloadSize
                        if (subPayloadSize < 0L || subEnd > propEnd) break
                        when (sid) {
                            "FS  " -> {
                                val b = read(channel, sub + 12L, 4)
                                if (b != null) sampleRate = beInt(b, 0)
                            }
                            "CHNL" -> {
                                val b = read(channel, sub + 12L, 2)
                                if (b != null) channels = beShort(b, 0).coerceIn(1, 8)
                            }
                            "CMPR" -> {
                                val b = read(channel, sub + 12L, 4)
                                if (b != null) {
                                    val compression = String(b, 0, 4, Charsets.ISO_8859_1)
                                    compressed = compression == "DST "
                                }
                            }
                        }
                        sub = subEnd + (subPayloadSize and 1L)
                    }
                }
                "DSD " -> {
                    dataOffset = pos + 12L
                    dataLength = payloadSize
                }
                "DST " -> {
                    compressed = true
                    dataOffset = pos + 12L
                    dataLength = payloadSize
                }
            }
            pos = chunkEnd + (payloadSize and 1L)
        }

        if (sampleRate <= 0 || channels <= 0 || dataOffset < 0L || dataLength <= 0L) return null
        return DsdInfo(
            container = DsdContainer.DFF,
            sampleRate = sampleRate,
            channels = channels,
            dataOffset = dataOffset,
            dataLength = dataLength,
            blockSize = 0,
            lsbFirst = false,
            compressed = compressed,
            declaredSamplesPerChannel = 0L
        )
    }

    private fun read(channel: FileChannel, position: Long, length: Int): ByteArray? {
        if (length <= 0) return ByteArray(0)
        val buffer = ByteBuffer.allocate(length)
        var pos = position
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, pos)
            if (n <= 0) return null
            pos += n
        }
        return buffer.array()
    }

    private fun leInt(b: ByteArray, o: Int): Int = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun leLong(b: ByteArray, o: Int): Long = ByteBuffer.wrap(b, o, 8).order(ByteOrder.LITTLE_ENDIAN).long
    private fun beInt(b: ByteArray, o: Int): Int = ByteBuffer.wrap(b, o, 4).order(ByteOrder.BIG_ENDIAN).int
    private fun beLong(b: ByteArray, o: Int): Long = ByteBuffer.wrap(b, o, 8).order(ByteOrder.BIG_ENDIAN).long
    private fun beShort(b: ByteArray, o: Int): Int = ByteBuffer.wrap(b, o, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
}

/**
 * Reads the 1-bit DSD payload and normalises it to MSB-first bytes. DoP and
 * native U32 both need the oldest DSD bit in the most significant position.
 */
class DsdStreamReader(
    private val channel: FileChannel,
    private val info: DsdInfo,
    startByte: Long = 0L
) {
    private val channelCount = info.channels.coerceIn(1, 2)
    private val totalBytesPerChannel = run {
        val raw = info.bytesPerChannel
        if (info.container == DsdContainer.DSF && info.declaredSamplesPerChannel > 0L) {
            minOf(raw, info.declaredSamplesPerChannel / 8L)
        } else {
            raw
        }
    }
    private var blockIndex = 0L
    private var blockOffset = 0
    private var dsfLoadedBlock = -1L
    private val dsfBlocks = Array(channelCount) { ByteArray(info.blockSize.coerceAtLeast(1)) }
    private val dsfLengths = IntArray(channelCount)

    init {
        seek(startByte)
    }

    fun seek(byteOffset: Long) {
        val start = byteOffset.coerceIn(0L, totalBytesPerChannel)
        if (info.container == DsdContainer.DSF) {
            val block = info.blockSize.coerceAtLeast(1)
            blockIndex = start / block
            blockOffset = (start % block).toInt()
            dsfLoadedBlock = -1L
        } else {
            blockIndex = start
            blockOffset = 0
        }
    }

    /**
     * Returns one array per channel, each containing [frames] * [bytesPerFrame]
     * bytes. Returns null at end of stream.
     */
    fun read(frames: Int, bytesPerFrame: Int): Array<ByteArray>? {
        if (frames <= 0 || bytesPerFrame <= 0) return null
        val need = frames * bytesPerFrame
        val out = Array(channelCount) { ByteArray(need) }
        val ok = if (info.container == DsdContainer.DSF) {
            readDsf(out, need)
        } else {
            readDff(out, frames, bytesPerFrame)
        }
        if (!ok) return null
        if (info.lsbFirst) {
            for (c in 0 until channelCount) reverseBits(out[c])
        }
        return out
    }

    private fun readDsf(out: Array<ByteArray>, need: Int): Boolean {
        var produced = 0
        val block = info.blockSize.coerceAtLeast(1)
        while (produced < need) {
            if (blockIndex * block >= totalBytesPerChannel) return produced > 0
            if (blockOffset >= block) {
                blockIndex++
                blockOffset = 0
                continue
            }
            if (dsfLoadedBlock != blockIndex) {
                if (!loadDsfBlock(blockIndex)) return produced > 0
            }
            val available = minOf(block - blockOffset, need - produced)
            for (c in 0 until channelCount) {
                val len = minOf(available, dsfLengths[c] - blockOffset).coerceAtLeast(0)
                if (len > 0) {
                    System.arraycopy(dsfBlocks[c], blockOffset, out[c], produced, len)
                }
            }
            produced += available
            blockOffset += available
            if (blockOffset >= block) {
                blockIndex++
                blockOffset = 0
                dsfLoadedBlock = -1L
            }
        }
        return true
    }

    private fun loadDsfBlock(index: Long): Boolean {
        val block = info.blockSize.coerceAtLeast(1)
        for (c in 0 until channelCount) {
            val position = info.dataOffset + index * block * info.channels + c.toLong() * block
            val remaining = (totalBytesPerChannel - index * block).coerceAtLeast(0L)
            val length = minOf(block.toLong(), remaining).toInt()
            dsfLengths[c] = length
            if (length <= 0) {
                dsfBlocks[c].fill(0)
                continue
            }
            val buffer = ByteBuffer.wrap(dsfBlocks[c], 0, length)
            var pos = position
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, pos)
                if (n <= 0) {
                    dsfLengths[c] = buffer.position()
                    break
                }
                pos += n
            }
            if (buffer.position() < length) {
                java.util.Arrays.fill(dsfBlocks[c], buffer.position(), length, 0)
                dsfLengths[c] = length
            }
        }
        dsfLoadedBlock = index
        return dsfLengths.any { it > 0 }
    }

    private fun readDff(out: Array<ByteArray>, frames: Int, bytesPerFrame: Int): Boolean {
        val start = blockIndex
        if (start >= totalBytesPerChannel) return false
        val availableBytes = minOf((totalBytesPerChannel - start), (frames * bytesPerFrame).toLong()).toInt()
        val framesToRead = availableBytes / bytesPerFrame
        if (framesToRead <= 0) return false
        val interleaved = ByteArray(framesToRead * channelCount * bytesPerFrame)
        val buffer = ByteBuffer.wrap(interleaved)
        var pos = info.dataOffset + start * info.channels
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, pos)
            if (n <= 0) return false
            pos += n
        }
        var src = 0
        for (i in 0 until framesToRead) {
            for (j in 0 until bytesPerFrame) {
                for (c in 0 until channelCount) {
                    out[c][i * bytesPerFrame + j] = interleaved[src++]
                }
            }
        }
        blockIndex += framesToRead.toLong() * bytesPerFrame
        return true
    }

    private fun reverseBits(bytes: ByteArray) {
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            var r = 0
            var x = v
            for (bit in 0 until 8) {
                r = (r shl 1) or (x and 1)
                x = x ushr 1
            }
            bytes[i] = r.toByte()
        }
    }
}
