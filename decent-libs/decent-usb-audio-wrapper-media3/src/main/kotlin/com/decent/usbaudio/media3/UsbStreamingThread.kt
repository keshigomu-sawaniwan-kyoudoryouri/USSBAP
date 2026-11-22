package com.decent.usbaudio.media3

import android.util.Log
import com.decent.usbaudio.UsbAudioStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Dedicated thread for USB audio streaming, decoupled from ExoPlayer's
 * render thread.
 *
 * Supports two buffer types:
 * - [FloatBuffer]: float PCM from FFmpeg (MP3, AAC, FLAC via float path)
 * - [RawBuffer]: raw integer PCM from libFLAC (zero float, true bit-perfect)
 *
 * @param usbStream The native USB audio stream to write to.
 *                  Must only be accessed from the USB thread.
 */
class UsbStreamingThread(private val usbStream: UsbAudioStream) {

    companion object {
        private const val TAG = "UsbStreamingThread"
        private const val QUEUE_CAPACITY = 128
        private const val POLL_TIMEOUT_MS = 100L
    }

    /** Sealed class for type-safe audio buffer queueing. */
    private sealed class AudioBuffer {
        class FloatBuffer(val data: FloatArray) : AudioBuffer()
        class RawBuffer(val data: ByteArray, val encoding: Int) : AudioBuffer()
    }

    private val audioQueue = ArrayBlockingQueue<AudioBuffer>(QUEUE_CAPACITY)

    @Volatile
    private var running = false
    @Volatile
    private var paused = false
    @Volatile
    var volume: Float = 1f
    private var thread: Thread? = null
    private var dropCount = 0

    fun start() {
        running = true
        thread = Thread({
            Log.i(TAG, "USB streaming thread started")
            while (running) {
                if (paused) {
                    Thread.sleep(50)
                    continue
                }
                val qBefore = audioQueue.size
                when (val buf = audioQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    is AudioBuffer.FloatBuffer -> {
                        applyFloatVolume(buf.data)
                        usbStream.write(buf.data)
                        if (qBefore <= 1) Log.w(TAG, "Queue nearly empty: $qBefore before write")
                    }
                    is AudioBuffer.RawBuffer -> {
                        applyRawVolume(buf.data, buf.encoding)
                        usbStream.writeRaw(buf.data, buf.encoding)
                        if (qBefore <= 1) Log.w(TAG, "Queue nearly empty: $qBefore before writeRaw")
                    }
                    null -> Log.w(TAG, "Queue EMPTY — poll timeout")
                }
            }
            Log.i(TAG, "USB streaming thread exited")
        }, "UsbStreamingThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun applyFloatVolume(samples: FloatArray) {
        val gain = volume.coerceIn(0f, 1f)
        if (gain == 1f) return
        for (i in samples.indices) samples[i] *= gain
    }

    private fun applyRawVolume(bytes: ByteArray, encoding: Int) {
        val gain = volume.coerceIn(0f, 1f)
        if (gain == 1f) return
        val sampleBytes = when (encoding) {
            2 -> 2
            0x15 -> 3
            0x16 -> 4
            else -> return
        }
        var offset = 0
        while (offset + sampleBytes <= bytes.size) {
            var value = 0L
            for (i in 0 until sampleBytes) value = value or ((bytes[offset + i].toLong() and 0xff) shl (8 * i))
            val bits = sampleBytes * 8
            if (value and (1L shl (bits - 1)) != 0L) value -= 1L shl bits
            val scaled = (value.toDouble() * gain).toLong()
            val min = -(1L shl (bits - 1))
            val max = (1L shl (bits - 1)) - 1
            val output = scaled.coerceIn(min, max)
            for (i in 0 until sampleBytes) bytes[offset + i] = (output shr (8 * i)).toByte()
            offset += sampleBytes
        }
    }

    /** Enqueue float PCM (FFmpeg path). Non-blocking, drop-oldest on full. */
    fun enqueue(floatBuf: FloatArray) {
        val buf = AudioBuffer.FloatBuffer(floatBuf)
        if (!audioQueue.offer(buf)) {
            audioQueue.poll()
            audioQueue.offer(buf)
            dropCount++
            if (dropCount <= 3 || dropCount % 100 == 0) {
                Log.w(TAG, "Queue full, dropped buffer #$dropCount")
            }
        }
    }

    /** Enqueue raw integer PCM (libFLAC path). Non-blocking, drop-oldest on full. */
    fun enqueueRaw(rawBytes: ByteArray, encoding: Int) {
        val buf = AudioBuffer.RawBuffer(rawBytes, encoding)
        if (!audioQueue.offer(buf)) {
            audioQueue.poll()
            audioQueue.offer(buf)
            dropCount++
            if (dropCount <= 3 || dropCount % 100 == 0) {
                Log.w(TAG, "Queue full, dropped raw buffer #$dropCount")
            }
        }
    }

    fun pauseStreaming() { paused = true }
    fun resumeStreaming() { paused = false }

    fun hasPendingData(): Boolean = !audioQueue.isEmpty()

    fun queueSize(): Int = audioQueue.size

    fun flush() {
        audioQueue.clear()
    }

    fun stop() {
        running = false
        audioQueue.clear()
        thread?.join(2000)
        thread = null
    }
}
