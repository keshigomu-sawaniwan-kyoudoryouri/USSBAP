package com.jin.bitperfect

import android.content.Context
import android.os.SystemClock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.Normalizer
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class LyricsLine(val timeMs: Long, val text: String)

data class LyricsResult(
    val text: String,
    val source: String,
    val lines: List<LyricsLine> = emptyList()
)

/**
 * Free, account-free lyrics lookup.
 *
 * Primary source: LRCLIB (plain and synced lyrics, no API key).
 * Fallback: lyrics.ovh (no API key).
 */
object LyricsFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .build()
    private val executor = Executors.newFixedThreadPool(3) { r -> Thread(r, "LyricsFetcher") }
    private val cache = ConcurrentHashMap<String, LyricsResult>()
    private val negativeCache = ConcurrentHashMap<String, Long>()
    private val pendingLock = Any()
    private val pending = HashMap<String, MutableList<(LyricsResult?, String?) -> Unit>>()
    private val lrclibRequestLock = Any()
    private var lrclibNextAllowedAt = 0L
    private var lrclibRetryAfterAt = 0L
    private val USER_AGENT = "USSBAP v${BuildConfig.VERSION_NAME} (com.jin.bitperfect; Android)"
    private const val MAX_CACHE_BYTES = 16L * 1024L * 1024L
    private const val NEGATIVE_CACHE_TTL_MS = 60L * 60L * 1000L
    private data class DiskCacheEntry(val result: LyricsResult?, val expiresAt: Long)

    fun fetch(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        callback: (LyricsResult?, String?) -> Unit
    ) {
        val cleanArtist = cleanMetadata(artist)
        val cleanAlbum = cleanMetadata(album)
        val clean = cleanTitle(title, cleanArtist)
        if (clean.isBlank()) {
            callback(null, "empty title")
            return
        }
        val key = cacheKey(clean, cleanArtist, cleanAlbum)
        var cachedFallback = cache[key]?.takeUnless { isPreferredLrclib(it) }
        cache[key]?.takeIf { isPreferredLrclib(it) }?.let { callback(it, null); return }
        negativeCache[key]?.let { expiresAt ->
            if (expiresAt > System.currentTimeMillis()) { callback(null, "not found"); return }
            negativeCache.remove(key, expiresAt)
        }
        cachedFallback?.let { callback(it, null) }
        var startFetch = false
        synchronized(pendingLock) {
            val waiters = pending[key]
            if (waiters == null) {
                pending[key] = mutableListOf(callback)
                startFetch = true
            } else {
                waiters += callback
            }
        }
        if (!startFetch) return

        val appContext = context.applicationContext
        executor.execute {
            val started = SystemClock.elapsedRealtime()
            val stored = readDiskCache(appContext, key)
                ?: readDiskCache(appContext, legacyCacheKey(clean, cleanArtist, cleanAlbum))?.takeIf { it.result != null }
            if (stored != null) {
                AppLogger.i("LYRICS_CACHE", "disk hit: $title source=${stored.result?.source ?: "none"}")
                if (stored.result == null) {
                    negativeCache[key] = stored.expiresAt
                    finish(key, null, "not found")
                    return@execute
                }
                if (isPreferredLrclib(stored.result)) {
                    cache[key] = stored.result
                    finish(key, stored.result, null)
                    return@execute
                }
                if (cachedFallback == null) {
                    cachedFallback = stored.result
                    cache[key] = stored.result
                    notifyPending(key, stored.result, null)
                }
            }

            var lastError = "not found"
            var hadNetworkError = false
            var lrclibPlain: LyricsResult? = null
            try {
                measureAttempt("LRCLIB get") { lrclibGet(clean, cleanArtist, cleanAlbum, durationMs) }?.let { result ->
                    if (result.lines.isNotEmpty()) {
                        storeResult(appContext, key, result)
                        finish(key, result, null)
                        return@execute
                    }
                    lrclibPlain = result
                }
            } catch (t: Throwable) {
                val message = t.message ?: t.javaClass.simpleName
                AppLogger.w("LYRICS_FETCH", "LRCLIB get failed: $message")
                if (message.contains("HTTP 404")) lastError = "not found"
                else { lastError = message; hadNetworkError = true }
            }
            if (!hadNetworkError) try {
                Thread.sleep(250L)
                measureAttempt("LRCLIB search") { lrclibSearch(clean, cleanArtist, cleanAlbum, durationMs) }?.let { result ->
                    storeResult(appContext, key, result)
                    finish(key, result, null)
                    return@execute
                }
            } catch (t: Throwable) {
                val message = t.message ?: t.javaClass.simpleName
                AppLogger.w("LYRICS_FETCH", "LRCLIB search failed: $message")
                if (message.contains("HTTP 404")) lastError = "not found"
                else { lastError = message; hadNetworkError = true }
            }
            if (lrclibPlain != null) {
                storeResult(appContext, key, lrclibPlain!!)
                AppLogger.i("LYRICS_FETCH", "LRCLIB plain fallback ${SystemClock.elapsedRealtime() - started}ms: $title")
                finish(key, lrclibPlain, null)
                return@execute
            }
            if (cachedFallback == null) {
                try {
                    measureAttempt("lyrics.ovh") { lyricsOvh(clean, artist) }?.let {
                        storeResult(appContext, key, it)
                        finish(key, it, null)
                        return@execute
                    }
                } catch (t: Throwable) {
                    val message = t.message ?: t.javaClass.simpleName
                    AppLogger.w("LYRICS_FETCH", "lyrics.ovh failed: $message")
                    if (message.contains("HTTP 404")) lastError = "not found"
                    else { lastError = message; hadNetworkError = true }
                }
            }
            if (cachedFallback != null) {
                cache[key] = cachedFallback!!
                AppLogger.i("LYRICS_FETCH", "keeping cached ${cachedFallback!!.source}; LRCLIB refresh miss ${SystemClock.elapsedRealtime() - started}ms: $title ($lastError)")
                finish(key, cachedFallback, null)
                return@execute
            }
            if (!hadNetworkError && lastError == "not found") {
                val expiresAt = System.currentTimeMillis() + NEGATIVE_CACHE_TTL_MS
                negativeCache[key] = expiresAt
                storeNegativeResult(appContext, key, expiresAt)
            }
            AppLogger.i("LYRICS_FETCH", "miss ${SystemClock.elapsedRealtime() - started}ms: $title ($lastError)")
            finish(key, null, lastError)
        }
    }

    private inline fun <T> measureAttempt(provider: String, block: () -> T): T {
        val started = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            AppLogger.i("LYRICS_FETCH", "$provider ${SystemClock.elapsedRealtime() - started}ms")
        }
    }

    private fun cacheKey(title: String, artist: String, album: String): String =
        "v4|" + legacyCacheKey(title, artist, album)

    private fun legacyCacheKey(title: String, artist: String, album: String): String =
        listOf(artist, title, album).joinToString("|") { it.trim().lowercase().replace(Regex("\\s+"), " ") }

    private fun isLrclib(result: LyricsResult): Boolean = result.source.equals("LRCLIB", ignoreCase = true)

    private fun isPreferredLrclib(result: LyricsResult): Boolean = isLrclib(result) && result.lines.isNotEmpty()

    private fun notifyPending(key: String, result: LyricsResult?, error: String?) {
        val callbacks = synchronized(pendingLock) { pending[key]?.toList().orEmpty() }
        callbacks.forEach { callback -> try { callback(result, error) } catch (_: Throwable) {} }
    }

    private fun finish(key: String, result: LyricsResult?, error: String?) {
        val callbacks = synchronized(pendingLock) { pending.remove(key).orEmpty() }
        callbacks.forEach { callback -> try { callback(result, error) } catch (_: Throwable) {} }
    }

    private fun storeResult(context: Context, key: String, result: LyricsResult) {
        cache[key] = result
        val dir = File(context.filesDir, "lyrics_cache")
        if (!dir.exists() && !dir.mkdirs()) return
        val file = cacheFile(dir, key)
        val temp = File(dir, "${file.name}.tmp")
        try {
            val lines = JSONArray()
            result.lines.forEach { lines.put(JSONObject().put("time", it.timeMs).put("text", it.text)) }
            val json = JSONObject().put("text", result.text).put("source", result.source).put("lines", lines)
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            trimDiskCache(dir)
        } catch (_: Throwable) { temp.delete() }
    }

    private fun readDiskCache(context: Context, key: String): DiskCacheEntry? {
        val file = cacheFile(File(context.filesDir, "lyrics_cache"), key)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            if (json.optBoolean("notFound")) {
                val expiresAt = json.optLong("expiresAt")
                if (expiresAt <= System.currentTimeMillis()) { file.delete(); return null }
                file.setLastModified(System.currentTimeMillis())
                return DiskCacheEntry(null, expiresAt)
            }
            val linesJson = json.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until linesJson.length()).mapNotNull { index ->
                val line = linesJson.optJSONObject(index) ?: return@mapNotNull null
                val text = line.optString("text")
                if (text.isBlank()) null else LyricsLine(line.optLong("time"), text)
            }
            val text = json.optString("text").trim()
            if (text.isBlank() && lines.isEmpty()) null
            else {
                file.setLastModified(System.currentTimeMillis())
                DiskCacheEntry(LyricsResult(text, json.optString("source", "cache"), lines), 0L)
            }
        } catch (_: Throwable) {
            file.delete()
            null
        }
    }

    private fun storeNegativeResult(context: Context, key: String, expiresAt: Long) {
        val dir = File(context.filesDir, "lyrics_cache")
        if (!dir.exists() && !dir.mkdirs()) return
        val file = cacheFile(dir, key)
        val temp = File(dir, "${file.name}.tmp")
        try {
            temp.writeText(JSONObject().put("notFound", true).put("expiresAt", expiresAt).toString())
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            trimDiskCache(dir)
        } catch (_: Throwable) { temp.delete() }
    }

    private fun cacheFile(directory: File, key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(directory, "$name.json")
    }

    private fun trimDiskCache(directory: File) {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "json" }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_CACHE_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private class LrclibRateLimitException(message: String, val retryDelayMs: Long) : Exception(message)

    private fun request(url: String): String {
        val isLrclib = url.startsWith("https://lrclib.net/", ignoreCase = true)
        if (!isLrclib) return executeRequest(url, false)

        // LRCLIB asks clients to serialize requests and leave 200-500 ms
        // between them. Keep this process-wide, including concurrent lookups.
        return synchronized(lrclibRequestLock) {
            var retried = false
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val waitUntil = maxOf(lrclibNextAllowedAt, lrclibRetryAfterAt)
                if (waitUntil > now) Thread.sleep(waitUntil - now)
                try {
                    return executeRequest(url, true)
                } catch (t: LrclibRateLimitException) {
                    if (retried) throw t
                    retried = true
                    lrclibRetryAfterAt = 0L
                    AppLogger.i("LYRICS_FETCH", "LRCLIB rate limited; retrying once in ${t.retryDelayMs}ms")
                    Thread.sleep(t.retryDelayMs)
                } finally {
                    lrclibNextAllowedAt = SystemClock.elapsedRealtime() + 500L
                }
            }
            @Suppress("UNREACHABLE_CODE")
            ""
        }
    }

    private fun executeRequest(url: String, isLrclib: Boolean): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val contentType = response.header("Content-Type") ?: "unknown content type"
                val detail = response.peekBody(512L).string().replace(Regex("\\s+"), " ").take(240)
                if (isLrclib && response.code in setOf(429, 503)) {
                    val retryMs = (parseRetryAfterMs(response.header("Retry-After")) ?: 1000L).coerceAtMost(5_000L)
                    throw LrclibRateLimitException("HTTP ${response.code} ($contentType): $detail", retryMs)
                }
                error("HTTP ${response.code} ($contentType): $detail")
            }
            val contentType = response.header("Content-Type").orEmpty()
            if (isLrclib && !contentType.contains("application/json", ignoreCase = true)) {
                error("Unexpected LRCLIB Content-Type: ${contentType.ifBlank { "missing" }}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun parseRetryAfterMs(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        value.trim().toLongOrNull()?.let { return (it.coerceAtLeast(0L) * 1000L).coerceAtMost(24L * 60L * 60L * 1000L) }
        return try {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis())
                .coerceAtLeast(0L)
                .coerceAtMost(24L * 60L * 60L * 1000L)
        } catch (_: Throwable) { null }
    }

    private fun lrclibGet(title: String, artist: String, album: String, durationMs: Long): LyricsResult? {
        if (artist.isBlank()) return null
        val albumForQuery = album.trim().takeUnless {
            it.isBlank() ||
                it.equals("YouTube", true) ||
                it.equals("Shared", true) ||
                it.equals("Unknown", true)
        }
        val durationSeconds = ((durationMs + 500L) / 1000L).takeIf { it in 1L..3600L }
        val attempts = mutableListOf<Pair<String?, Long?>>()
        if (albumForQuery != null) attempts += albumForQuery to durationSeconds
        attempts += null to durationSeconds
        attempts += null to null
        for ((albumAttempt, durationAttempt) in attempts.distinct()) {
            val builder = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("artist_name", artist.trim())
                .addQueryParameter("track_name", title.trim())
            if (albumAttempt != null) builder.addQueryParameter("album_name", albumAttempt)
            if (durationAttempt != null) builder.addQueryParameter("duration", durationAttempt.toString())
            try {
                val body = request(builder.build().toString())
                return lyricsFromJson(JSONObject(body))
                    ?.let { LyricsResult(it.first, "LRCLIB", it.second) }
            } catch (t: Throwable) {
                if (t.message?.contains("HTTP 404") != true) throw t
                // 404: an album or duration mismatch is likely, so retry
                // with progressively looser parameters.
            }
        }
        return null
    }

    private fun lrclibSearch(title: String, artist: String, album: String, durationMs: Long): LyricsResult? {
        if (title.isBlank()) return null
        // LRCLIB's search endpoint keyword-matches track_name, so decorated
        // YouTube/file names must be reduced to their actual song title.
        val titles = titleCandidates(title, artist)
        val artists = artistCandidates(artist)
        val primaryArtist = artists.firstOrNull().orEmpty()
        val queries = LinkedHashSet<Pair<String, String>>()
        for (candidate in titles) {
            queries += candidate to primaryArtist
            queries += candidate to ""
            artists.getOrNull(1)?.let { queries += candidate to it }
            artists.getOrNull(2)?.let { queries += candidate to it }
        }
        // Album is deliberately not sent: stale tags often make it noise.
        val maxRequests = 4
        var requests = 0
        for ((candidateTitle, candidateArtist) in queries) {
            if (requests >= maxRequests) break
            requests++
            val builder = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", candidateTitle.trim())
            if (candidateArtist.isNotBlank()) builder.addQueryParameter("artist_name", candidateArtist.trim())
            val array = JSONArray(request(builder.build().toString()))
            var best: LyricsResult? = null
            var bestScore = Int.MIN_VALUE
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val parsed = lyricsFromJson(item) ?: continue
                val score = scoreLrclibCandidate(item, candidateTitle, artist, album, durationMs, parsed.second.isNotEmpty())
                if (score > bestScore) {
                    bestScore = score
                    best = LyricsResult(parsed.first, "LRCLIB", parsed.second)
                }
            }
            AppLogger.i("LYRICS_FETCH", "LRCLIB search title=\"$candidateTitle\" artist=${candidateArtist.isNotBlank()} results=${array.length()} bestScore=$bestScore")
            if (best != null && bestScore >= 60) return best
        }
        // As a last resort use LRCLIB's documented keyword search, but still
        // score metadata strictly enough to avoid returning another song.
        if (requests < maxRequests) {
            val query = listOf(title, artist, album).filter { it.isNotBlank() }.joinToString(" ")
            if (query.isNotBlank()) {
                val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query)
                    .build()
                val array = JSONArray(request(url.toString()))
                var best: LyricsResult? = null
                var bestScore = Int.MIN_VALUE
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val parsed = lyricsFromJson(item) ?: continue
                    val score = scoreLrclibCandidate(item, title, artist, album, durationMs, parsed.second.isNotEmpty())
                    if (score > bestScore) {
                        bestScore = score
                        best = LyricsResult(parsed.first, "LRCLIB", parsed.second)
                    }
                }
                AppLogger.i("LYRICS_FETCH", "LRCLIB keyword search results=${array.length()} bestScore=$bestScore")
                return best?.takeIf { bestScore >= 60 }
            }
        }
        return null
    }

    /** Title variants used for LRCLIB keyword search, most specific first. */
    private fun titleCandidates(raw: String, artist: String): List<String> {
        val out = LinkedHashSet<String>()
        fun add(value: String) {
            val cleaned = value.replace(Regex("\\s+"), " ").trim()
            if (cleaned.isNotBlank()) out += cleaned
        }
        add(cleanTitle(raw, artist))
        add(raw.replace(Regex("[（(\\[【「『].*?[）)\\]】」』]"), " "))
        add(raw.split(Regex("\\s*[/｜]\\s*")).firstOrNull().orEmpty())
        add(raw.replace(Regex("(?i)\\s*(feat\\.?|ft\\.?|with)\\s+.*$"), " "))
        add(raw.replace(Regex(
            "(?i)\\s*[-–—]?\\s*" +
                "(official\\s*(music\\s*)?(video|audio)|mv|m/v|lyric\\s*video|" +
                "audio|4k|hd|remaster(ed)?|full\\s*ver\\.?|short\\s*ver\\.?|teaser)\\s*$"
        ), " "))
        return out.take(4)
    }

    /** Artist variants used for LRCLIB keyword search, most specific first. */
    private fun artistCandidates(raw: String): List<String> {
        val cleaned = cleanMetadata(raw)
        val out = LinkedHashSet<String>()
        if (cleaned.isNotBlank()) out += cleaned
        out += cleaned.replace(Regex("(?i)\\s*[-–—]\\s*topic\\s*$"), "").trim()
        out += cleaned.replace(Regex("(?i)\\s*(vevo|official|channel)\\s*$"), "").trim()
        out += cleaned.substringBefore(" / ").trim()
        return out.filter { it.isNotBlank() }.take(3)
    }

    private fun scoreLrclibCandidate(
        item: JSONObject,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        hasSyncedLyrics: Boolean
    ): Int {
        val wantedTitle = normalizeMatch(title)
        val actualTitle = normalizeMatch(item.optString("trackName", item.optString("name")))
        val wantedArtist = normalizeMatch(artist)
        val actualArtist = normalizeMatch(item.optString("artistName"))
        val wantedAlbum = normalizeMatch(album)
        val actualAlbum = normalizeMatch(item.optString("albumName"))
        if (wantedTitle.isNotEmpty() && actualTitle.isNotEmpty() &&
            actualTitle != wantedTitle && !actualTitle.contains(wantedTitle) && !wantedTitle.contains(actualTitle)) return Int.MIN_VALUE
        val artistMismatch = wantedArtist.isNotEmpty() && actualArtist.isNotEmpty() &&
            actualArtist != wantedArtist && !actualArtist.contains(wantedArtist) && !wantedArtist.contains(actualArtist)
        val albumMismatch = wantedAlbum.isNotEmpty() && actualAlbum.isNotEmpty() && actualAlbum != wantedAlbum

        var score = 0
        if (wantedTitle.isNotEmpty() && actualTitle == wantedTitle) score += 100
        else if (wantedTitle.isNotEmpty() && actualTitle.isNotEmpty()) score += 65
        if (wantedArtist.isNotEmpty() && actualArtist == wantedArtist) score += 100
        else if (wantedArtist.isNotEmpty() && actualArtist.isNotEmpty() && !artistMismatch) score += 55
        else if (artistMismatch) score -= 55
        if (wantedAlbum.isNotEmpty() && actualAlbum == wantedAlbum) score += 20
        else if (albumMismatch) score -= 20
        val wantedSeconds = durationMs / 1000.0
        val actualSeconds = item.optDouble("duration", 0.0)
        if (wantedSeconds > 0 && actualSeconds > 0) {
            val difference = kotlin.math.abs(wantedSeconds - actualSeconds)
            if (difference <= 2.0) score += 35
            else if (difference <= 6.0) score += 15
            else score -= 60
        }
        if (hasSyncedLyrics) score += 10
        return score
    }

    private fun normalizeMatch(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun lyricsOvh(title: String, artist: String): LyricsResult? {
        if (artist.isBlank()) return null
        val url = "https://api.lyrics.ovh/v1/" +
            URLEncoder.encode(artist.trim(), "UTF-8") + "/" +
            URLEncoder.encode(title.trim(), "UTF-8")
        val body = request(url)
        val text = JSONObject(body).optString("lyrics").trim()
        return text.takeIf { it.isNotBlank() }?.let { LyricsResult(it, "lyrics.ovh") }
    }

    private fun lyricsFromJson(obj: JSONObject): Pair<String, List<LyricsLine>>? {
        // LRCLIB explicitly uses JSON null for missing/instrumental lyrics.
        // JSONObject.optString may stringify that sentinel as "null" on some
        // Android versions, so only accept actual JSON strings.
        val plain = (obj.opt("plainLyrics") as? String).orEmpty().trim()
        val synced = (obj.opt("syncedLyrics") as? String).orEmpty().trim()
        val lines = if (synced.isNotBlank()) parseLrc(synced) else emptyList()
        val text = plain.ifBlank { lines.joinToString("\n") { it.text } }.trim()
        if (text.isBlank() && lines.isEmpty()) return null
        return text to lines
    }

    /** Parse LRC lines, including multiple timestamps on one line. */
    private fun parseLrc(raw: String): List<LyricsLine> {
        val result = mutableListOf<LyricsLine>()
        val timestamp = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
        var offsetMs = 0L
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[offset:", ignoreCase = true)) {
                offsetMs = trimmed.substringAfter(':').substringBefore(']').trim().toLongOrNull() ?: 0L
                continue
            }
            val tags = timestamp.findAll(trimmed).toList()
            if (tags.isEmpty()) continue
            val text = trimmed.substring(tags.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (tag in tags) {
                val minutes = tag.groupValues[1].toLongOrNull() ?: continue
                val seconds = tag.groupValues[2].toLongOrNull() ?: continue
                val fraction = tag.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> (fraction.toLongOrNull() ?: 0L) * 100L
                    2 -> (fraction.toLongOrNull() ?: 0L) * 10L
                    else -> fraction.take(3).toLongOrNull() ?: 0L
                }
                result += LyricsLine(minutes * 60_000L + seconds * 1_000L + millis + offsetMs, text)
            }
        }
        return result.sortedBy { it.timeMs }
    }

    private fun cleanMetadata(value: String): String = value.trim().takeUnless {
        it.isBlank() || it.equals("unknown", ignoreCase = true) ||
            it.equals("unknown artist", ignoreCase = true) ||
            it.equals("various artists", ignoreCase = true) ||
            it.equals("n/a", ignoreCase = true) || it == "-" || it == "?" ||
            it == "不明" || it == "不明なアーティスト" || it == "不明なアルバム"
    }.orEmpty()

    private fun cleanTitle(raw: String, artist: String): String {
        var title = raw
            .replace(Regex("""\.(?:flac|wav|mp3|aac|m4a|dsf|dff|dsdiff|opus|ogg|wma)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^\s*\d{1,3}[\s._-]+"""), "")
            .replace(Regex("""\s*[\(\[](?:official|lyric|music|mv|m/v|audio|video|4k|hd|remaster).*?[\)\]]""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*[-–—]\s*(?:official|lyric|music|mv|m/v|audio|video).*$""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val prefix = artist.trim()
        if (prefix.isNotEmpty() && title.startsWith(prefix, ignoreCase = true)) {
            title = title.removePrefix(prefix).trimStart(' ', '-', '–', '—', ':', '：')
        }
        return title.ifBlank { raw.trim() }
    }
}
