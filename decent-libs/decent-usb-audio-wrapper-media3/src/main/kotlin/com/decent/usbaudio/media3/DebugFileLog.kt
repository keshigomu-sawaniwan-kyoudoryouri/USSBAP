package com.decent.usbaudio.media3

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal on-device file logger. The target device's logd prunes everything
 * immediately, making logcat useless. UsbAudioSink decisions are appended to
 * a plain file so the toggle (OFF -> DIRECT_USB) failure can be diagnosed.
 * Root of the blame is /data/local/tmp or app cache; default goes to cacheDir.
 */
object DebugFileLog {
    @Volatile private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private const val TAG = "DebugFileLog"

    fun init(context: Context) {
        if (file != null) return
        try {
            val f = File(context.getExternalFilesDir(null) ?: context.cacheDir, "usb_sink_debug.log")
            if (!f.exists()) f.createNewFile()
            file = f
            append("==== session start ====")
        } catch (t: Throwable) {
            Log.w(TAG, "init failed: ${t.message}")
        }
    }

    fun append(msg: String) {
        val f = file ?: return
        try {
            f.appendText("${fmt.format(Date())} ${Thread.currentThread().name}: $msg\n")
        } catch (_: Throwable) {}
    }
}
