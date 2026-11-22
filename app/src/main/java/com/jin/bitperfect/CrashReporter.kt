package com.jin.bitperfect

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

object CrashReporter {
    private const val CRASH_DIR = "crash"
    private const val MAX_REPORTS = 20

    // 再生状態の記録用（AudioPlaybackServiceから随時更新）
    var currentBackend: String = "NONE"
    var currentSong: String = "None"
    var currentPath: String = "None"
    var currentMime: String = "Unknown"
    var usbConnected: Boolean = false
    var usbDeviceName: String = "None"
    var sampleRate: Int = 0
    var channels: Int = 0
    var pcmEncoding: String = "None"
    var isPlaying: Boolean = false

    fun init(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrashReport(context, thread, throwable)
            } catch (e: Exception) {
                // クラッシュレポート自体の失敗は無視
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        cleanupOldReports(context)
    }

    private fun saveCrashReport(context: Context, thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val fileName = "crash_$timestamp.txt"
        val dir = File(context.filesDir, CRASH_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, fileName)
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val report = buildString {
            appendLine("======================================")
            appendLine("USB BitPerfect Crash Report")
            appendLine("======================================")
            appendLine("\nTime:")
            appendLine(displayTime)
            appendLine("\nApp Version:")
            appendLine(getAppVersion(context))
            appendLine("\nAndroid Version:")
            appendLine("${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("\nManufacturer / Device:")
            appendLine("${Build.MANUFACTURER} / ${Build.MODEL}")
            appendLine("\nABI / CPU:")
            appendLine("${Build.SUPPORTED_ABIS.joinToString(", ")} / ${System.getProperty("os.arch")}")
            
            appendLine("\nThread:")
            appendLine(thread.name)
            
            appendLine("\nException:")
            appendLine(throwable.javaClass.name)
            appendLine("\nMessage:")
            appendLine(throwable.message ?: "No message")
            
            appendLine("\n--- Playback Context ---")
            appendLine("Backend: $currentBackend")
            appendLine("Song: $currentSong")
            appendLine("Path: $currentPath")
            appendLine("Mime: $currentMime")
            appendLine("USB Connected: $usbConnected")
            appendLine("USB Device: $usbDeviceName")
            appendLine("SampleRate: $sampleRate")
            appendLine("Channels: $channels")
            appendLine("Encoding: $pcmEncoding")
            appendLine("IsPlaying: $isPlaying")
            appendLine("Session: ${AppLogger.currentSessionId}")

            appendLine("\n--- Memory ---")
            val runtime = Runtime.getRuntime()
            appendLine("Free: ${runtime.freeMemory() / 1024 / 1024}MB")
            appendLine("Total: ${runtime.totalMemory() / 1024 / 1024}MB")
            appendLine("Max: ${runtime.maxMemory() / 1024 / 1024}MB")

            appendLine("\n--- StackTrace ---")
            appendLine(sw.toString())

            appendLine("\n======================================")
            appendLine("Recent Logs (Last 1000 lines)")
            appendLine("======================================")
            appendLine(AppLogger.getRecentLogs(1000))
            appendLine("======================================")
        }

        file.writeText(report)
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "Unknown"
        } catch (e: Exception) { "Unknown" }
    }

    fun hasCrash(context: Context): Boolean {
        val dir = File(context.filesDir, CRASH_DIR)
        return dir.exists() && dir.listFiles()?.isNotEmpty() == true
    }

    fun getLatestReport(context: Context): File? {
        val dir = File(context.filesDir, CRASH_DIR)
        return dir.listFiles()?.sortedByDescending { it.lastModified() }?.firstOrNull()
    }

    fun deleteReports(context: Context) {
        val dir = File(context.filesDir, CRASH_DIR)
        dir.deleteRecursively()
    }

    private fun cleanupOldReports(context: Context) {
        val dir = File(context.filesDir, CRASH_DIR)
        if (dir.exists()) {
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
            if (files.size > MAX_REPORTS) {
                files.take(files.size - MAX_REPORTS).forEach { it.delete() }
            }
        }
    }
}
