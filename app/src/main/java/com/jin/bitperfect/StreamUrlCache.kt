package com.jin.bitperfect

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Short-lived cache for YouTube CDN URLs. Stable watch URLs remain in playlists. */
object StreamUrlCache {
    // v2 stores the progressive AAC URL separately from the visual MV URL.
    private const val PREFS = "StreamUrlCacheV4"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 40
    private const val DEFAULT_TTL_MS = 3L * 60L * 60L * 1000L
    private const val SAFETY_MARGIN_MS = 5L * 60L * 1000L

    // L1 memory cache. The on-disk JSON format is unchanged, so entries
    // survive a process restart and are re-hydrated on the first lookup.
    private data class MemoryEntry(val result: YtAudioResult, val expiresAt: Long)
    private val memory = HashMap<String, MemoryEntry>()

    private fun load(context: Context): JSONArray = try {
        JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
    } catch (_: Exception) { JSONArray() }

    @Synchronized
    fun get(context: Context, videoId: String): YtAudioResult? {
        val now = System.currentTimeMillis()
        memory[videoId]?.let { entry ->
            if (entry.expiresAt > now + SAFETY_MARGIN_MS) return entry.result
            memory.remove(videoId)
        }
        val array = load(context)
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("videoId") != videoId || item.optLong("expiresAt") <= now + SAFETY_MARGIN_MS) continue
            val audio = item.optString("audioUrl")
            val video = item.optString("videoUrl")
            if (audio.isEmpty() && video.isEmpty()) continue
            val result = YtAudioResult(
                audioUrl = audio,
                title = item.optString("title"),
                uploader = item.optString("uploader"),
                durationSeconds = item.optLong("duration"),
                bestBitrate = item.optInt("bitrate"),
                thumbnailUrl = item.optString("thumbnail"),
                videoUrl = video,
                videoId = videoId,
                muxedUrl = item.optString("muxedUrl"),
            )
            memory[videoId] = MemoryEntry(result, item.optLong("expiresAt"))
            return result
        }
        return null
    }

    @Synchronized
    fun put(context: Context, videoId: String, result: YtAudioResult) {
        val now = System.currentTimeMillis()
        val expiresAt = listOf(result.audioUrl, result.videoUrl).mapNotNull(::expiryFromUrl).minOrNull()
            ?.coerceAtLeast(now + 15L * 60L * 1000L) ?: (now + DEFAULT_TTL_MS)
        val old = load(context)
        val fresh = JSONArray()
        fresh.put(JSONObject().apply {
            put("videoId", videoId); put("audioUrl", result.audioUrl); put("videoUrl", result.videoUrl)
            put("muxedUrl", result.muxedUrl)
            put("title", result.title); put("uploader", result.uploader); put("duration", result.durationSeconds)
            put("bitrate", result.bestBitrate); put("thumbnail", result.thumbnailUrl); put("expiresAt", expiresAt)
        })
        for (index in 0 until old.length()) {
            val item = old.optJSONObject(index) ?: continue
            if (item.optString("videoId") != videoId && item.optLong("expiresAt") > now && fresh.length() < MAX_ENTRIES) fresh.put(item)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, fresh.toString()).apply()
        memory[videoId] = MemoryEntry(result, expiresAt)
    }

    @Synchronized
    fun remove(context: Context, videoId: String) {
        val old = load(context)
        val fresh = JSONArray()
        for (index in 0 until old.length()) {
            val item = old.optJSONObject(index) ?: continue
            if (item.optString("videoId") != videoId) fresh.put(item)
        }
        memory.remove(videoId)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, fresh.toString()).apply()
    }

    private fun expiryFromUrl(url: String): Long? = try {
        Uri.parse(url).getQueryParameter("expire")?.toLongOrNull()?.times(1000L)
    } catch (_: Exception) { null }
}
