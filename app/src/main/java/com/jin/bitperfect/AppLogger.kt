package com.jin.bitperfect

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

object AppLogger {
    private const val TAG_PREFIX = "!!!BITPERFECT!!!"
    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
    
    data class LogEntry(
        val timestamp: String,
        val level: String,
        val tag: String,
        val threadInfo: String,
        val sessionId: String,
        val message: String,
        val isError: Boolean = false
    ) {
        override fun toString(): String {
            return "$timestamp [$level] [$tag] [$threadInfo] [Session $sessionId]\n$message"
        }
    }

    private val _logs = MutableSharedFlow<LogEntry>(replay = 100)
    val logs = _logs.asSharedFlow()
    
    private val history = ConcurrentLinkedQueue<LogEntry>()
    private const val MAX_HISTORY = 5000
    
    var currentSessionId: String = "0"
    var isVerboseMode: Boolean = false
    var onLogUpdate: ((String) -> Unit)? = null

    fun v(tag: String, msg: String) { if (isVerboseMode) log("VERB", tag, msg) }
    fun d(tag: String, msg: String) { log("DEBUG", tag, msg) }
    fun i(tag: String, msg: String) { log("INFO", tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable? = null) {
        log("WARN", tag, if (tr != null) "$msg\n${expandThrowable(tr)}" else msg)
    }
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        log("ERROR", tag, if (tr != null) "$msg\n${expandThrowable(tr)}" else msg, true)
    }

    private fun log(level: String, tag: String, msg: String, isError: Boolean = false) {
        val entry = LogEntry(
            timestamp = requireNotNull(dateFormat.get()).format(Date()),
            level = level,
            tag = tag,
            threadInfo = "${Thread.currentThread().name}:${Thread.currentThread().id}",
            sessionId = currentSessionId,
            message = msg,
            isError = isError
        )
        
        // Logcat
        when (level) {
            "ERROR" -> Log.e("$TAG_PREFIX$tag", msg)
            "WARN" -> Log.w("$TAG_PREFIX$tag", msg)
            "INFO" -> Log.i("$TAG_PREFIX$tag", msg)
            "DEBUG" -> Log.d("$TAG_PREFIX$tag", msg)
            else -> Log.v("$TAG_PREFIX$tag", msg)
        }

        // History management
        history.add(entry)
        if (history.size > MAX_HISTORY) history.poll()
        
        // Broadcast
        _logs.tryEmit(entry)
        onLogUpdate?.invoke(getHistory())
    }

    fun getHistory(): String = history.joinToString("\n---\n")

    fun getRecentLogs(limit: Int): String {
        val list = history.toList()
        return list.takeLast(limit).joinToString("\n")
    }

    fun clear() {
        history.clear()
        val entry = LogEntry(requireNotNull(dateFormat.get()).format(Date()), "INFO", "System", "", "0", "Logs cleared")
        _logs.tryEmit(entry)
        onLogUpdate?.invoke(getHistory())
    }

    private fun expandThrowable(tr: Throwable): String {
        val sb = StringBuilder()
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = tr
        
        while (current != null && !seen.contains(current)) {
            seen.add(current)
            sb.append("${current.javaClass.name}: ${current.message}\n")
            current.stackTrace.take(15).forEach { sb.append("  at $it\n") }
            
            current.suppressed.forEach { sup ->
                sb.append("  [Suppressed] ${sup.javaClass.name}: ${sup.message}\n")
            }
            
            current = current.cause
            if (current != null) sb.append("Caused by: ")
        }
        return sb.toString()
    }
}
