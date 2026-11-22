package com.jin.dacprobe

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal USB DAC descriptor probe.
 *
 * - Lists USB audio devices, interfaces and endpoints (Android API level).
 * - Requests USB permission, reads raw descriptors, parses UAC1/UAC2
 *   Format Type I entries with the same parser as the main app.
 * - Lists AudioManager mixer attributes (BIT-PERFECT vs Mixing).
 * - Shows everything on screen and saves it to a text file for `adb pull`.
 */
class MainActivity : Activity() {

    private lateinit var output: TextView
    private var lastReport: String = ""
    private var lastFile: File? = null

    private val permissionAction = "com.jin.dacprobe.USB_PERMISSION"
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == permissionAction) {
                val granted =
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                appendLine("USB permission result: granted=$granted")
                if (granted) runScan()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scanButton = Button(this).apply { text = "スキャン/権限" }
        val preflightButton = Button(this).apply { text = "DSD preflight" }
        val saveButton = Button(this).apply { text = "保存" }
        output = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(16, 16, 16, 16)
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(scanButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(preflightButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(saveButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val scroll = ScrollView(this).apply { addView(output) }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(buttons)
                addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
            }
        )

        scanButton.setOnClickListener { requestPermissionsThenScan() }
        preflightButton.setOnClickListener {
            Toast.makeText(this, "再生を止めてから実行してください", Toast.LENGTH_LONG).show()
            runPreflight()
        }
        saveButton.setOnClickListener { saveReport() }

        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, filter)
        }

        runScan()
    }

    override fun onResume() {
        super.onResume()
        runScan()
    }

    override fun onDestroy() {
        try { unregisterReceiver(permissionReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun appendLine(s: String) {
        lastReport += s + "\n"
        output.text = lastReport
        Log.i("DAC_PROBE", s)
    }

    private fun requestPermissionsThenScan() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        var requested = false
        for (device in usbManager.deviceList.values) {
            if (!isUsbAudioDevice(device)) continue
            if (!usbManager.hasPermission(device)) {
                val intent = Intent(permissionAction).setPackage(packageName)
                val pi = PendingIntent.getBroadcast(
                    this, device.deviceId, intent, PendingIntent.FLAG_MUTABLE
                )
                usbManager.requestPermission(device, pi)
                requested = true
            }
        }
        if (!requested) runScan()
        else appendLine("USB permission requested; rescan on grant.")
    }

    private fun runScan() {
        lastReport = ""
        lastFile = null
        output.text = ""
        appendLine("=== DAC Probe ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===")
        appendLine("manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
        scanAudioManager()
        scanUsb()
    }

    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2
            ) return true
        }
        return false
    }

    private fun scanAudioManager() {
        appendLine("")
        appendLine("--- AudioManager USB outputs + mixer attributes ---")
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter {
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
            if (devs.isEmpty()) {
                appendLine("(no USB audio output in AudioManager)")
                return
            }
            for (d in devs) {
                appendLine("device: ${d.productName} type=${d.type}")
                if (Build.VERSION.SDK_INT < 34) {
                    appendLine("  mixer attributes require API 34+")
                    continue
                }
                val attrs = try {
                    am.getSupportedMixerAttributes(d)
                } catch (t: Throwable) {
                    appendLine("  getSupportedMixerAttributes failed: ${t.message}")
                    continue
                }
                if (attrs.isEmpty()) appendLine("  (no mixer attributes)")
                for (a in attrs.sortedBy { it.format.sampleRate }) {
                    val enc = when (a.format.encoding) {
                        AudioFormat.ENCODING_PCM_16BIT -> "16"
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24p"
                        AudioFormat.ENCODING_PCM_32BIT -> "32"
                        AudioFormat.ENCODING_PCM_FLOAT -> "float"
                        else -> a.format.encoding.toString()
                    }
                    val bp = if (a.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) {
                        "BIT-PERFECT"
                    } else {
                        "Mixing"
                    }
                    appendLine("  ${a.format.sampleRate}Hz/${enc}/${bp}")
                }
            }
        } catch (t: Throwable) {
            appendLine("AudioManager scan failed: ${t.message}")
        }
    }

    private fun scanUsb() {
        appendLine("")
        appendLine("--- USB devices (UsbManager) ---")
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) {
            appendLine("(no USB devices)")
            return
        }
        for (device in devices) {
            appendLine("")
            appendLine("device: ${device.manufacturerName} ${device.productName}")
            appendLine("  vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)} class=${device.deviceClass} bcdDevice=${device.version}")
            val audio = isUsbAudioDevice(device)
            appendLine("  isUsbAudio=$audio permission=${usbManager.hasPermission(device)}")
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                appendLine("  iface id=${iface.id} alt=${iface.alternateSetting} class=${iface.interfaceClass}/${iface.interfaceSubclass}/${iface.interfaceProtocol} eps=${iface.endpointCount}")
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                        else -> ep.type.toString()
                    }
                    appendLine("    ep 0x${ep.address.toString(16)} $dir $type maxPacket=${ep.maxPacketSize} interval=${ep.interval} attrs=${ep.attributes}")
                }
            }
            if (!audio) continue
            if (!usbManager.hasPermission(device)) {
                appendLine("  (no permission: tap scan button to request)")
                continue
            }
            parseRawDescriptors(usbManager, device)
        }
    }

    private fun parseRawDescriptors(usbManager: UsbManager, device: UsbDevice) {
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            appendLine("  openDevice failed")
            return
        }
        try {
            val speed = try { readUsbSpeed(conn.fileDescriptor) } catch (t: Throwable) { "error:${t.javaClass.simpleName}:${t.message}" }
            appendLine("  nativeUsbSpeed=$speed")
            val raw = conn.rawDescriptors
            appendLine("  rawDescriptors=${if (raw == null) "null" else "${raw.size} bytes"}")
            if (raw == null) return
            appendLine("  RAWHEX=" + raw.joinToString("") { "%02x".format(it.toInt() and 0xff) })
            appendLine("  ISO endpoint class-specific descriptors:")
            var descOffset = 0
            var currentIf = -1
            var currentAlt = -1
            while (descOffset + 2 <= raw.size) {
                val len = raw[descOffset].toInt() and 0xff
                if (len < 2 || descOffset + len > raw.size) break
                when (raw[descOffset + 1].toInt() and 0xff) {
                    0x04 -> if (len >= 9) {
                        currentIf = raw[descOffset + 2].toInt() and 0xff
                        currentAlt = raw[descOffset + 3].toInt() and 0xff
                    }
                    0x05 -> if (len >= 7) {
                        val addr = raw[descOffset + 2].toInt() and 0xff
                        val attr = raw[descOffset + 3].toInt() and 0xff
                        if ((attr and 3) == 1) appendLine("    if=$currentIf alt=$currentAlt endpoint=0x${addr.toString(16)} standard=${raw.copyOfRange(descOffset, descOffset + len).joinToString("") { "%02x".format(it.toInt() and 0xff) }}")
                    }
                    0x25 -> if (len >= 4) appendLine("    if=$currentIf alt=$currentAlt csEndpoint=${raw.copyOfRange(descOffset, descOffset + len).joinToString("") { "%02x".format(it.toInt() and 0xff) }}")
                }
                descOffset += len
            }
            val clockId = UacClockSourceParser.parse(raw)
            appendLine("  clockSourceId=${if (clockId > 0) "0x${clockId.toString(16)}" else "not found"}")
            val formats = UacFormatDescriptorParser.parse(raw)
            if (formats.isEmpty()) {
                appendLine("  (no Format Type I entries parsed)")
                return
            }
            for (f in formats.sortedBy { it.altSetting }) {
                val rates = if (f.sampleRates.isEmpty()) "(UAC2/continuous)" else f.sampleRates.joinToString(",")
                appendLine("  alt=${f.altSetting} validBits=${f.bitDepth} subframe=${f.subframeSize} transportBits=${f.transportBitDepth} rates=$rates")
            }
            val best = AltSelector.best(formats)
            appendLine("  best: alt=${best.altSetting} transportBits=${best.transportBitDepth}")
            val for24 = AltSelector.findForBitDepth(formats, 24)
            val for32 = AltSelector.findForBitDepth(formats, 32)
            appendLine("  findAlt(24): alt=${for24.altSetting} transportBits=${for24.transportBitDepth} exact24slot=${for24.transportBitDepth == 24}")
            appendLine("  findAlt(32): alt=${for32.altSetting} transportBits=${for32.transportBitDepth} exact32slot=${for32.transportBitDepth == 32}")
            val dopAlt = formats
                .filter { it.bitDepth == 24 && it.subframeSize == 3 }
                .maxByOrNull { it.altSetting }?.altSetting
            appendLine("  DoP-direct verdict: ${if (for24.transportBitDepth == 24 && dopAlt != null) "OK (alt=$dopAlt)" else "FAIL (main app falls back)"}")
            val nativeAlt = formats
                .filter { it.bitDepth == 32 && it.subframeSize == 4 }
                .maxByOrNull { it.altSetting }?.altSetting
            appendLine("  Native-direct verdict: ${if (for32.transportBitDepth == 32 && nativeAlt != null) "OK (alt=$nativeAlt)" else "FAIL (main app falls back)"}")
        } catch (t: Throwable) {
            appendLine("  descriptor parse failed: ${t.message}")
        } finally {
            try { conn.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Replicates the exact pre-stream steps of the main app's UsbDsdOutput
     * (claim, setAlt, SET_CUR/GET_CUR/CLOCK_VALID) plus AudioTrack creation,
     * and reports each step. Claims are released and alt 0 restored.
     * Run while nothing is playing: claiming detaches the HAL path.
     */
    private fun runPreflight() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val sb = StringBuilder()
        sb.appendLine("")
        sb.appendLine("--- DSD preflight ---")
        val device = usbManager.deviceList.values.firstOrNull { isUsbAudioDevice(it) }
        if (device == null) {
            sb.appendLine("(no USB audio device)")
            emitBlock(sb)
            return
        }
        if (!usbManager.hasPermission(device)) {
            sb.appendLine("(no permission)")
            emitBlock(sb)
            return
        }
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            sb.appendLine("openDevice failed")
            emitBlock(sb)
            return
        }
        try {
            val control = (0 until device.interfaceCount).map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == 1 && it.interfaceSubclass == 1 }
            val streamIfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
                .filter { it.interfaceClass == 1 && it.interfaceSubclass == 2 }
            val streamId = streamIfaces.firstOrNull { it.endpointCount > 0 }?.id
                ?: streamIfaces.firstOrNull()?.id ?: -1
            sb.appendLine("controlId=${control?.id} streamId=$streamId")
            if (control != null) sb.appendLine("claim control: ${conn.claimInterface(control, true)}")
            val streamClaim = streamIfaces.firstOrNull { it.endpointCount > 0 }
                ?: streamIfaces.firstOrNull()
            if (streamClaim != null) sb.appendLine("claim stream: ${conn.claimInterface(streamClaim, true)}")
            val clockId = UacClockSourceParser.parse(conn.rawDescriptors).let { if (it > 0) it else 1 }
            val acId = control?.id ?: 0
            sb.appendLine("clockId=0x${clockId.toString(16)} acId=$acId")
            fun altObj(alt: Int) =
                streamIfaces.firstOrNull { it.id == streamId && it.alternateSetting == alt }
            val zero = altObj(0)
            if (zero != null) sb.appendLine("setAlt(0): ${conn.setInterface(zero)}")
            val a2 = altObj(2)
            if (a2 != null) sb.appendLine("setAlt(2) [DoP]: ${conn.setInterface(a2)}")
            sb.appendLine("SET_CUR 352800 [DoP DSD128]: ${setRate(conn, clockId, acId, 352800)}")
            sb.appendLine("GET_CUR: ${getRate(conn, clockId, acId)} CLOCK_VALID: ${getClockValid(conn, clockId, acId)}")
            val a4 = altObj(4)
            if (a4 != null) sb.appendLine("setAlt(4) [Native]: ${conn.setInterface(a4)}")
            sb.appendLine("SET_CUR 176400 [Native DSD128]: ${setRate(conn, clockId, acId, 176400)}")
            sb.appendLine("GET_CUR: ${getRate(conn, clockId, acId)} CLOCK_VALID: ${getClockValid(conn, clockId, acId)}")
            if (zero != null) sb.appendLine("restore setAlt(0): ${conn.setInterface(zero)}")
            if (streamClaim != null) sb.appendLine("release stream: ${conn.releaseInterface(streamClaim)}")
            if (control != null) sb.appendLine("release control: ${conn.releaseInterface(control)}")
        } catch (t: Throwable) {
            sb.appendLine("preflight exception: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            try { conn.close() } catch (_: Throwable) {}
        }
        for ((rate, encName, enc) in listOf(
            Triple(352800, "24p", AudioFormat.ENCODING_PCM_24BIT_PACKED),
            Triple(176400, "24p", AudioFormat.ENCODING_PCM_24BIT_PACKED)
        )) {
            try {
                val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, enc)
                var state = -999
                var track: AudioTrack? = null
                try {
                    track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .setEncoding(enc)
                                .build()
                        )
                        .setBufferSizeInBytes(min.coerceAtLeast(4096) * 8)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    state = track.state
                } finally {
                    try { track?.release() } catch (_: Throwable) {}
                }
                val ok = state == AudioTrack.STATE_INITIALIZED
                sb.appendLine("AudioTrack $rate/$encName: minBuf=$min state=${if (ok) "INITIALIZED" else "UNINITIALIZED($state)"}")
            } catch (t: Throwable) {
                sb.appendLine("AudioTrack $rate/$encName: FAILED ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        emitBlock(sb)
    }

    private fun emitBlock(sb: StringBuilder) {
        val s = sb.toString().trimEnd('\n') + "\n"
        lastReport += s
        output.text = lastReport
        for (line in s.lines()) Log.i("DAC_PROBE", line)
    }

    private fun setRate(conn: UsbDeviceConnection, clockId: Int, acId: Int, rate: Int): String {
        val data = byteArrayOf(
            (rate and 0xFF).toByte(),
            ((rate shr 8) and 0xFF).toByte(),
            ((rate shr 16) and 0xFF).toByte(),
            ((rate shr 24) and 0xFF).toByte()
        )
        val rc = conn.controlTransfer(0x21, 0x01, 0x0100, (clockId shl 8) or acId, data, data.size, 1000)
        return "rc=$rc"
    }

    private fun getRate(conn: UsbDeviceConnection, clockId: Int, acId: Int): String {
        val data = ByteArray(4)
        val rc = conn.controlTransfer(0xA1, 0x01, 0x0100, (clockId shl 8) or acId, data, 4, 1000)
        if (rc < 4) return "fail rc=$rc"
        val r = (data[0].toInt() and 0xFF) or
            ((data[1].toInt() and 0xFF) shl 8) or
            ((data[2].toInt() and 0xFF) shl 16) or
            ((data[3].toInt() and 0xFF) shl 24)
        return "${r}Hz rc=$rc"
    }

    private fun getClockValid(conn: UsbDeviceConnection, clockId: Int, acId: Int): String {
        val data = ByteArray(1)
        val rc = conn.controlTransfer(0xA1, 0x01, 0x0200, (clockId shl 8) or acId, data, 1, 1000)
        if (rc < 1) return "fail rc=$rc"
        return "valid=${data[0].toInt() and 0x01} rc=$rc"
    }

    private fun readUsbSpeed(fd: Int): String {
        val path = "/proc/self/fd/$fd"
        val target = File(path).canonicalPath
        val match = Regex("/dev/bus/usb/(\\d+)/(\\d+)$").find(target)
            ?: return "fd=$fd path=$target"
        val bus = match.groupValues[1].toInt()
        val dev = match.groupValues[2].toInt()
        val result = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "echo -n \"$dev\" > /dev/null"))
        result.waitFor()
        // Android doesn't expose USBDEVFS_GET_SPEED through UsbDeviceConnection;
        // report the resolved bus/device path, and infer only from descriptor capacity.
        return "fd=$fd path=$target bus=$bus dev=$dev (USBDEVFS_GET_SPEED not exposed by Java API)"
    }

    private fun saveReport() {        if (lastReport.isEmpty()) {
            Toast.makeText(this, "先にスキャンしてください", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val name = "dac-probe-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
            val file = File(dir, name)
            file.writeText(lastReport)
            lastFile = file
            val msg = "保存: ${file.absolutePath}"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            appendLine("")
            appendLine("saved to: ${file.absolutePath}")
            appendLine("pull: adb pull \"${file.absolutePath}\"")
        } catch (t: Throwable) {
            Toast.makeText(this, "保存失敗: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }
}
