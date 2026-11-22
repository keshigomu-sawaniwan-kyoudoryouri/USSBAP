package com.jin.bitperfect

import android.content.Context
import android.hardware.usb.UsbDevice
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioDeviceInfo
import com.decent.usbaudio.UsbAudioStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class DsdTransport {
    DOP,
    NATIVE
}

object DsdPacker {
    /**
     * DoP: 24-bit PCM frames. The top byte is the alternating 0x05/0xFA
     * marker; the lower 16 bits contain two MSB-first DSD bytes.
     */
    fun packDop(left: ByteArray, right: ByteArray, frames: Int, out: ByteArray): Int {
        var o = 0
        var marker = 0x05
        for (i in 0 until frames) {
            val li = i * 2
            val ri = li
            val l0 = left[li].toInt() and 0xff
            val l1 = left[li + 1].toInt() and 0xff
            val r0 = right[ri].toInt() and 0xff
            val r1 = right[ri + 1].toInt() and 0xff
            // 24-bit little-endian sample: data low/high, marker high.
            out[o++] = l1.toByte()
            out[o++] = l0.toByte()
            out[o++] = marker.toByte()
            out[o++] = r1.toByte()
            out[o++] = r0.toByte()
            out[o++] = marker.toByte()
            marker = if (marker == 0x05) 0xFA else 0x05
        }
        return o
    }

    /**
     * Native DSD U32 (XMOS / USB Audio Class 2 style): four DSD bytes per
     * 32-bit word, most-significant byte first. DSD64 therefore runs at
     * 88.2 kHz with a 32-bit slot.
     */
    fun packNative(left: ByteArray, right: ByteArray, frames: Int, out: ByteArray): Int {
        var o = 0
        for (i in 0 until frames) {
            val p = i * 4
            out[o++] = left[p]
            out[o++] = left[p + 1]
            out[o++] = left[p + 2]
            out[o++] = left[p + 3]
            out[o++] = right[p]
            out[o++] = right[p + 1]
            out[o++] = right[p + 2]
            out[o++] = right[p + 3]
        }
        return o
    }
}

interface DsdOutput {
    fun play()
    fun pause()
    fun write(data: ByteArray, size: Int)
    fun release()
}

/**
 * DoP through Android's AudioTrack. This is used in HAL_API/OFF modes. If the
 * USB HAL exposes an exact 24-bit mixer attribute it is requested, otherwise
 * Android may mix the stream and the UI must not claim bit-perfect output.
 */
class AudioTrackDsdOutput(
    private val context: Context,
    private val transport: DsdTransport,
    private val sampleRate: Int
) : DsdOutput {
    private var track: AudioTrack? = null
    private var usb: android.media.AudioDeviceInfo? = null
    private val encoding = when (transport) {
        DsdTransport.DOP -> AudioFormat.ENCODING_PCM_24BIT_PACKED
        DsdTransport.NATIVE -> AudioFormat.ENCODING_PCM_32BIT
    }

    init {
        require(transport == DsdTransport.DOP) { "Native DSD requires direct USB" }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        usb = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }
        if (usb != null) {
            try {
                val exact = am.getSupportedMixerAttributes(usb!!).firstOrNull {
                    it.format.sampleRate == sampleRate && it.format.encoding == encoding
                }
                if (exact != null) {
                    val rc = am.setPreferredMixerAttributes(
                        PlatformAudioAttributes.Builder()
                            .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                            .build(),
                        usb!!,
                        exact
                    )
                    AppLogger.i("DSD", "DoP mixer attribute ${sampleRate}Hz/24bit rc=$rc")
                } else {
                    AppLogger.w("DSD", "No exact 24-bit mixer attribute for ${sampleRate}Hz; DoP may be mixed")
                }
            } catch (t: Throwable) {
                AppLogger.w("DSD", "setPreferredMixerAttributes failed", t)
            }
        }
        val min = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            encoding
        ).let { if (it > 0) it else 16 * 1024 }
        val built = AudioTrack.Builder()
            .setAudioAttributes(
                PlatformAudioAttributes.Builder()
                    .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                    .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes((min * 8).coerceAtLeast(16 * 1024))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (built.state != AudioTrack.STATE_INITIALIZED) {
            try { built.release() } catch (_: Throwable) {}
            throw IllegalStateException("DoP AudioTrack initialization failed")
        }
        track = built
    }

    override fun play() {
        track?.play()
    }

    override fun pause() {
        try { track?.pause() } catch (_: Throwable) {}
    }

    override fun write(data: ByteArray, size: Int) {
        val t = track ?: throw IllegalStateException("AudioTrack is not initialized")
        var offset = 0
        while (offset < size) {
            val n = t.write(data, offset, size - offset)
            if (n < 0) throw IllegalStateException("AudioTrack write failed: $n")
            if (n == 0) {
                Thread.sleep(1)
                continue
            }
            offset += n
        }
    }

    override fun release() {
        try { track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            usb?.let {
                am.clearPreferredMixerAttributes(
                    PlatformAudioAttributes.Builder()
                        .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                        .build(),
                    it
                )
            }
        } catch (_: Throwable) {}
        usb = null
    }
}

data class UsbAltSetting(
    val alt: Int,
    val bitDepth: Int,
    val subframeSize: Int,
    val sampleRates: List<Int>
)

/**
 * Android's UsbDevice API exposes only a subset of alternate settings, while
 * the raw configuration descriptor still contains the UAC1/UAC2 24/32-bit
 * formats and sample-rate lists needed for DoP/native selection.
 */
object UsbAltSettingParser {
    fun parse(raw: ByteArray?): List<UsbAltSetting> =
        com.decent.usbaudio.UacFormatDescriptorParser.parse(raw).map {
            UsbAltSetting(it.altSetting, it.bitDepth, it.subframeSize, it.sampleRates)
        }
}

/**
 * Direct UAC2 output. DoP uses a 24-bit alternate setting; native DSD uses a
 * 32-bit U32 alternate setting. Both bypass Android's mixer.
 */
class UsbDsdOutput(
    private val context: Context,
    private val transport: DsdTransport,
    private val sampleRate: Int
) : DsdOutput {
    private val driver = UsbAudioDevice.getInstance(context)
    private var device: UsbDevice? = null
    private var info: UsbAudioDeviceInfo? = null
    private var stream: UsbAudioStream? = null
    private var altSetting = 0
    private var nativeEnabled = false

    init {
        try {
            initialize()
        } catch (t: Throwable) {
            // A failed direct attempt must not leave the interface claimed when
            // the caller falls back to Android's USB HAL.
            release()
            throw t
        }
    }

    private fun initialize() {
        val dev = driver.findUsbAudioDevice()
            ?: throw IllegalStateException("USB DAC not found")
        device = dev
        if (!driver.hasPermission(dev)) throw IllegalStateException("USB DAC permission denied")
        val di = driver.openDevice(dev) ?: throw IllegalStateException("Cannot open USB DAC")
        info = di
        val bits = if (transport == DsdTransport.NATIVE) 32 else 24
        val pair = driver.findAltSettingForBitDepth(bits)
            ?: throw IllegalStateException("No ${bits}-bit UAC2 alternate setting")
        if (pair.second != bits) {
            throw IllegalStateException("No exact ${bits}-bit UAC2 alternate setting (found ${pair.second}-bit)")
        }
        val parsedAlts = UsbAltSettingParser.parse(di.connection.rawDescriptors)
        val matchingAlt = parsedAlts
            .filter {
                it.bitDepth == bits &&
                    it.subframeSize == bits / 8 &&
                    (it.sampleRates.isEmpty() || sampleRate in it.sampleRates)
            }
            .maxByOrNull { it.alt }
            ?.alt
        if (transport == DsdTransport.NATIVE && matchingAlt == null) {
            throw IllegalStateException("No 32-bit UAC2 alternate setting for ${sampleRate}Hz native DSD")
        }
        altSetting = matchingAlt ?: pair.first
        if (altSetting <= 0) throw IllegalStateException("Invalid UAC2 alternate setting")
        // The native stream owns the endpoint; reset first, then program the
        // clock and select the alternate setting.
        try { driver.setAltSetting(0) } catch (_: Throwable) {}
        if (transport == DsdTransport.NATIVE) {
            nativeEnabled = NativeDsdVendor.enable(di, dev)
        }
        val rateOk = try { driver.setSampleRate(sampleRate) } catch (_: Throwable) { false }
        if (!rateOk) AppLogger.w("DSD", "UAC2 clock rate ${sampleRate}Hz was rejected; trying anyway")
        if (!driver.setAltSetting(altSetting)) {
            if (altSetting != pair.first && driver.setAltSetting(pair.first)) {
                AppLogger.w("DSD", "Alt $altSetting rejected; using first matching alt ${pair.first}")
                altSetting = pair.first
            } else {
                throw IllegalStateException("Cannot select UAC2 alt setting $altSetting")
            }
        }
        // Give the DAC PLL a moment to lock after the clock/alt change.
        Thread.sleep(50)
        val us = UsbAudioStream(
            di.fd,
            di.interfaceId,
            di.endpointOutAddress,
            di.endpointFeedbackAddress,
            sampleRate,
            2,
            bits,
            di.maxPacketSize
        )
        if (!us.isReady) {
            try { us.release() } catch (_: Throwable) {}
            throw IllegalStateException("USB DSD stream creation failed")
        }
        if (!us.start()) {
            try { us.release() } catch (_: Throwable) {}
            throw IllegalStateException("USB DSD stream start failed")
        }
        stream = us
        AppLogger.i(
            "DSD",
            "${if (transport == DsdTransport.NATIVE) "Native" else "DoP"} USB: " +
                "${sampleRate}Hz/${bits}bit alt=$altSetting native=$nativeEnabled dac=${di.deviceName}"
        )
    }

    override fun play() {
        // The stream is already started; the playback thread controls pacing.
    }

    override fun pause() {
        // The playback thread stops writing while paused. Keeping the stream
        // alive avoids a full UAC2 reconfiguration on every pause.
    }

    override fun write(data: ByteArray, size: Int) {
        val s = stream ?: throw IllegalStateException("USB DSD stream is not open")
        require(size in 0..data.size) { "Invalid DSD buffer size: $size/${data.size}" }
        if (size == 0) return
        // The second writeRaw argument is a Media3 PCM encoding, not a byte
        // count. Passing `size` there silently discarded every DSD buffer.
        val output = if (size == data.size) data else data.copyOf(size)
        val encoding = when (transport) {
            DsdTransport.DOP -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            DsdTransport.NATIVE -> AudioFormat.ENCODING_PCM_32BIT
        }
        s.writeRaw(output, encoding)
    }

    override fun release() {
        try { stream?.stop() } catch (_: Throwable) {}
        try { stream?.drainUrbs() } catch (_: Throwable) {}
        try { stream?.release() } catch (_: Throwable) {}
        stream = null
        if (nativeEnabled) {
            try { info?.let { NativeDsdVendor.disable(it) } } catch (_: Throwable) {}
            nativeEnabled = false
        }
        try { driver.setAltSetting(0) } catch (_: Throwable) {}
        driver.closeDevice()
        info = null
        device = null
    }
}

/**
 * Amanero Combo384 uses a vendor control request to switch its 32-bit
 * alternate setting between PCM and native DSD. Other UAC2 DACs expose their
 * native alt setting directly; for those this is a no-op.
 */
object NativeDsdVendor {
    private const val AMANERO_VID = 0x16d0
    private const val AMANERO_PID_071A = 0x071a
    private const val AMANERO_PID_0A23 = 0x0a23

    fun enable(info: UsbAudioDeviceInfo, device: UsbDevice): Boolean {
        if (device.vendorId != AMANERO_VID) return false
        if (device.productId != AMANERO_PID_071A && device.productId != AMANERO_PID_0A23) return false
        return request(info, 0x8000)
    }

    fun disable(info: UsbAudioDeviceInfo) {
        request(info, 0x10000)
    }

    private fun request(info: UsbAudioDeviceInfo, command: Int): Boolean = try {
        val data = ByteBuffer.allocate(264).order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(command)
        data.putInt(0)
        // buffer[64] remains zero-initialised (4 + 4 + 256 = 264 bytes).
        val rc = info.connection.controlTransfer(
            0x40, // vendor, host-to-device, device recipient
            0xB1,
            0,
            0,
            data.array(),
            data.capacity(),
            1000
        )
        AppLogger.i("DSD", "Amanero native DSD command=0x${command.toString(16)} rc=$rc")
        rc >= 0
    } catch (t: Throwable) {
        AppLogger.w("DSD", "Amanero native DSD request failed", t)
        false
    }
}
