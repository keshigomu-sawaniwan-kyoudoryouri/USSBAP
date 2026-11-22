package com.jin.bitperfect

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.chaquo.python.Python
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

data class YtAudioResult(
    val audioUrl: String,
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val bestBitrate: Int,
    val thumbnailUrl: String,
    val videoUrl: String = "",
    val videoId: String = "",
    val fullJson: String = "",
    val muxedUrl: String = ""
) {
    val hasVideo: Boolean get() = videoUrl.isNotEmpty()
}

data class YtPlaylistEntry(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val thumbnailUrl: String,
    val album: String = ""
)

data class YtPlaylistInfo(
    val playlistId: String,
    val title: String,
    val entries: List<YtPlaylistEntry>
)

object YtStreamHelper {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val playerApiClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(2500, TimeUnit.MILLISECONDS)
        .build()
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .callTimeout(1, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    @Volatile var cookies: String = ""
        private set
    @Volatile var cookieMap: Map<String, String> = emptyMap()
        private set
    @Volatile var visitorData: String = ""
        private set
    private var cachePath: String = ""

    private val safeCachePath: String
        get() = cachePath.ifBlank {
            if (::appContext.isInitialized) appContext.cacheDir.absolutePath
            else System.getProperty("java.io.tmpdir") ?: "."
        }
    private lateinit var appContext: Context

    private const val VISITOR_KEY = "yt_visitor_data"
    private const val IOS_USER_AGENT = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X;)"
    private data class FetchWaiter(val success:(YtAudioResult)->Unit,val error:(String)->Unit)
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<YtAudioResult>>()
    private val musicMetaCache=ConcurrentHashMap<String,YtPlaylistEntry>()
    @Volatile private var loadedYtDlpPath: String? = null
    @Volatile private var cookieFileHash: String? = null
    private val ytdlpExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "YtDlp").apply { isDaemon = true }
    }

    private val playerClients = listOf(
        // Prefer mobile clients whose googlevideo URLs are known to work on
        // cellular networks; WEB is a compatibility fallback. Keep the list
        // short because player API calls are attempted sequentially.
        PlayerClient("VISIONOS", 101, "1.02", "visionOS", "26.5.23O471", "Apple", "RealityDevice17,1"),
        PlayerClient("IOS", 5, "20.10.4", "iPhone", "18.3.1.22D72", "Apple", "iPhone16,2"),
        PlayerClient("WEB", 1, "2.20260708.00.00", null, null, null, null)
    )

    private data class PlayerClient(val name: String, val id: Int, val version: String, val osName: String?, val osVersion: String?, val deviceMake: String?, val deviceModel: String?)

    @Volatile private var securePrefs: SharedPreferences? = null

    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        securePrefs?.let { return it }
        return synchronized(this) {
            securePrefs ?: run {
                val app = context.applicationContext
                val mk = MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    app,
                    "yt_secure_prefs",
                    mk,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { securePrefs = it }
            }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = getEncryptedPrefs(context)
        cookies = prefs.getString("yt_cookies", "") ?: ""
        cookieMap = parseCookies(cookies)
        visitorData = prefs.getString(VISITOR_KEY, "") ?: ""
        cachePath = context.cacheDir.absolutePath
        // Warm the bundled yt-dlp once so the compatibility fallback does not
        // pay the full import cost on the first restricted video.
        Thread {
            runCatching { Python.getInstance().getModule("yt_dlp") }
        }.apply {
            name = "YtDlpWarmup"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    fun setCookies(context: Context, value: String) { cookies = value; cookieMap = parseCookies(value); getEncryptedPrefs(context).edit().putString("yt_cookies", value).apply() }
    fun setCookieMap(context: Context, map: Map<String, String>) { cookieMap = map.filterValues { it.isNotEmpty() }; cookies = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }; getEncryptedPrefs(context).edit().putString("yt_cookies", cookies).apply() }
    fun clearCookies(context: Context) { cookies = ""; cookieMap = emptyMap(); getEncryptedPrefs(context).edit().remove("yt_cookies").apply() }
    fun hasCookies(): Boolean = cookies.isNotEmpty()
    private fun saveVisitorData(value: String) {
        if (value.isEmpty() || value == visitorData) return
        visitorData = value
        if (::appContext.isInitialized) {
            try { getEncryptedPrefs(appContext).edit().putString(VISITOR_KEY, value).apply() } catch (_: Exception) {}
        }
    }
    fun getCookie(name: String) = cookieMap[name] ?: ""
    private fun parseCookies(raw: String) = raw.split(";").mapNotNull { pair -> val i = pair.indexOf('='); if (i > 0) pair.substring(0, i).trim() to pair.substring(i + 1).trim() else null }.toMap()

    fun fetchAndPlay(url: String, onSuccess: (YtAudioResult) -> Unit, onError: (String) -> Unit, enrichMetadata: Boolean = true) {
        val videoId = extractVideoId(url) ?: run { onError("Could not extract videoId"); return }
        val requestStartedAt = android.os.SystemClock.elapsedRealtime()
        val future = CompletableFuture<YtAudioResult>()
        val existing = inFlight.putIfAbsent(videoId, future)
        val target = existing ?: future
        if (existing != null) {
            // Attach to the in-flight resolution before it can be removed.
            // A list-based handoff could miss the completion entirely and
            // leave the caller waiting forever.
            AppLogger.i("YT_CACHE", "single-flight joined $videoId")
        }
        // The owner must observe completion as well. Completing the future in
        // finishSuccess/finishError does not invoke the caller callbacks by
        // itself; without this the first tap resolves but never starts playback.
        target.whenComplete { result, error ->
            if (error != null || result == null) onError(error?.message ?: "error")
            else onSuccess(result)
        }
        if (existing != null) return
        fun finishSuccess(result:YtAudioResult){
            AppLogger.i("YT_FETCH", "resolved videoId=$videoId elapsed=${android.os.SystemClock.elapsedRealtime()-requestStartedAt}ms")
            future.complete(result)
        }
        fun finishError(message:String){
            AppLogger.w("YT_FETCH", "failed videoId=$videoId elapsed=${android.os.SystemClock.elapsedRealtime()-requestStartedAt}ms error=$message")
            future.completeExceptionally(IllegalStateException(message))
        }
        val cachedSuccess: (YtAudioResult) -> Unit = { result ->
            if (::appContext.isInitialized) StreamUrlCache.put(appContext, videoId, result)
            finishSuccess(result)
            if (enrichMetadata && ::appContext.isInitialized) {
                Thread {
                    val metadataStartedAt = android.os.SystemClock.elapsedRealtime()
                    val enriched = enrichWithMusicMetadata(videoId, result)
                    if (enriched != result) StreamUrlCache.put(appContext, videoId, enriched)
                    AppLogger.i("YT_FETCH", "metadata enrichment videoId=$videoId elapsed=${android.os.SystemClock.elapsedRealtime()-metadataStartedAt}ms changed=${enriched != result}")
                }.apply { name = "YtMetadata-$videoId"; start() }
            }
        }
        val validatedSuccess: (YtAudioResult) -> Unit = { result ->
            val probe = result.audioUrl
                .ifEmpty { result.muxedUrl }
                .ifEmpty { result.videoUrl }
            if (probe.isNotEmpty() && isUrlPlayable(probe)) cachedSuccess(result)
            else finishError("Resolved stream was rejected by the server")
        }
        Thread {
            // The Innertube player endpoint normally returns signed adaptive
            // URLs in well under a second. Starting the embedded yt-dlp/Python
            // runtime first added roughly 6-8 seconds to every cold play.
            // Keep yt-dlp as the compatibility fallback for restricted items.
            try {
                val cached = if (::appContext.isInitialized) StreamUrlCache.get(appContext, videoId) else null
                if (cached != null) {
                    // Cached URLs already passed resolution and are protected by
                    // their expiry timestamp. Avoid a serial network probe on every tap;
                    // playback errors invalidate the cache in AudioPlaybackService.
                    AppLogger.i("YT_CACHE", "hit videoId=$videoId")
                    cachedSuccess(cached)
                    return@Thread
                }
                tryFetch(videoId, 0, null, null, "", cachedSuccess, onError = { apiError ->
                    // yt-dlp runs on its own thread so a hung Python call can be
                    // abandoned after a fixed budget instead of blocking forever.
                    val task = ytdlpExecutor.submit {
                        fetchViaYtDlp(videoId, validatedSuccess) { ytdlpError ->
                            finishError("$apiError / $ytdlpError")
                        }
                    }
                    try {
                        task.get(10, TimeUnit.SECONDS)
                    } catch (_: java.util.concurrent.TimeoutException) {
                        task.cancel(true)
                        finishError("$apiError / yt-dlp timed out")
                    } catch (t: Throwable) {
                        if (!future.isDone) finishError("$apiError / ${t.message}")
                    }
                }, deadline = requestStartedAt + 8_000L)
            }
            catch (e: Exception) { finishError(e.message ?: "error") }
            finally { inFlight.remove(videoId, future) }
        }.apply { name = "YtFetch"; start() }
    }

    fun resolveBlocking(videoId: String): YtAudioResult {
        if (videoId.isBlank()) throw IllegalStateException("Missing video id")
        val result = AtomicReference<YtAudioResult?>(null)
        val error = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        fetchAndPlay(
            "https://www.youtube.com/watch?v=$videoId",
            { value -> result.set(value); latch.countDown() },
            { message -> error.set(message); latch.countDown() },
            enrichMetadata = false
        )
        if (!latch.await(35, TimeUnit.SECONDS)) throw IllegalStateException("Stream resolve timeout")
        return result.get() ?: throw IllegalStateException(error.get() ?: "Stream resolve failed")
    }

    fun prefetch(url: String, enrichMetadata: Boolean = false) {
        val videoId = extractVideoId(url) ?: return
        if (::appContext.isInitialized && StreamUrlCache.get(appContext, videoId) != null) return
        fetchAndPlay(url,
            onSuccess = { AppLogger.i("YT_CACHE", "prefetched ${it.videoId}") },
            onError = { AppLogger.w("YT_CACHE", "prefetch failed: $it") },
            enrichMetadata = enrichMetadata)
    }

    private fun fetchViaYtDlp(videoId: String, onSuccess: (YtAudioResult) -> Unit, onFallback: (String) -> Unit) {
        try {
            val cookieFile = if (::appContext.isInitialized) writeCookieFile(appContext) else null
            val cookieOpt = cookieFile?.let { "'cookiefile': '${it.absolutePath.replace("\\", "/")}', " } ?: ""

            fun runYtDlp(useUpdate: Boolean): String {
                val updatePath = if (useUpdate && ::appContext.isInitialized) YtDlpUpdater.activePath(appContext) else null
                // Re-importing yt_dlp on every fallback cost several seconds.
                // Only clear the module when the active update path changes,
                // e.g. immediately after YtDlpUpdater installs a new version.
                val clearModules = loadedYtDlpPath != updatePath
                val prelude = buildString {
                    appendLine("import sys")
                    if (updatePath != null) {
                        val escaped = updatePath.replace("\\", "/").replace("'", "\\'")
                        appendLine("sys.path.insert(0, '$escaped')")
                    }
                    if (clearModules) {
                        appendLine("for _module in [m for m in list(sys.modules) if m == 'yt_dlp' or m.startswith('yt_dlp.')]:")
                        appendLine("    del sys.modules[_module]")
                    }
                }
                val pyCode = """
${prelude}result = ''
try:
    import yt_dlp
except Exception as _import_error:
    result = 'IMPORT_ERROR:' + str(_import_error)
else:
    import io
    cap = io.StringIO()
    old = sys.stdout
    sys.stdout = cap
    try:
        opts = {${cookieOpt}'format': 'bestaudio/best', 'noplaylist': True, 'skip_download': True, 'dump_single_json': True, 'quiet': True, 'no_warnings': True, 'outtmpl': '${safeCachePath}/%(title)s.%(ext)s'}
        with yt_dlp.YoutubeDL(opts) as y: y.download(['https://www.youtube.com/watch?v=$videoId'])
    finally:
        sys.stdout = old
    result = cap.getvalue()
""".trimIndent()
                val py = Python.getInstance()
                val globals = py.getModule("builtins")?.callAttr("dict")
                py.getModule("builtins")?.callAttr("exec", pyCode, globals)
                val output = globals?.callAttr("get", "result", "")?.toString() ?: ""
                if (!output.startsWith("IMPORT_ERROR:")) loadedYtDlpPath = updatePath
                return output
            }

            var raw = runYtDlp(true)
            if (raw.startsWith("IMPORT_ERROR:") && ::appContext.isInitialized && YtDlpUpdater.activePath(appContext) != null) {
                AppLogger.w("YTDLP_UPDATE", "updated yt-dlp failed to import; reverting to bundled")
                YtDlpUpdater.reset(appContext)
                raw = runYtDlp(false)
            }
            if (raw.startsWith("IMPORT_ERROR:")) { onFallback(raw); return }
            if (raw.length < 100) { onFallback("yt-dlp returned empty"); return }

            val root = JSONObject(raw)
            val title = root.optString("title", "")
            val uploader = root.optString("uploader", root.optString("channel", ""))
            val duration = root.optLong("duration", 0)
            val thumb = root.optString("thumbnail", "")
            var audioUrl = ""
            var muxedUrl = ""
            var bestBr = 0
            var bestAudioScore = -1
            var bestMuxedScore = Int.MIN_VALUE
            val formats = root.optJSONArray("formats")
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val f = formats.getJSONObject(i)
                    val acodec = f.optString("acodec", "none")
                    val vcodec = f.optString("vcodec", "none")
                    val url = f.optString("url", "")
                    val abr = f.optInt("abr", 0)
                    // Android's hardware AAC path reaches first PCM
                    // substantially sooner than software Opus/WebM on NX779J.
                    // Prefer a normal 128-160k AAC stream over a marginally
                    // higher bitrate Opus stream; DAU handles either codec.
                    val fastAac = acodec.contains("mp4a", true) || f.optString("ext") == "m4a"
                    val score = abr + if (fastAac) 10_000 else 0
                    if (url.isNotEmpty() && acodec != "none" && vcodec == "none" && score > bestAudioScore) {
                        bestAudioScore = score; bestBr = abr; audioUrl = url
                    }
                    // Audio-only app: retain a small muxed MP4 only as a
                    // fallback when no separate audio stream was offered.
                    if (url.isNotEmpty() && acodec != "none" && vcodec != "none") {
                        val height = f.optInt("height", 0)
                        val muxedScore = (if (fastAac) 10_000 else 0) - height
                        if (height in 1..720 && muxedScore > bestMuxedScore) {
                            bestMuxedScore = muxedScore; muxedUrl = url
                        }
                    }
                }
            }
            val playbackUrl = audioUrl.ifEmpty { muxedUrl }
            if (playbackUrl.isEmpty()) { onFallback("yt-dlp: no playable URL"); return }
            onSuccess(YtAudioResult(
                playbackUrl, title, uploader, duration, bestBr, thumb,
                videoId = videoId,
                fullJson = raw,
                muxedUrl = if (audioUrl.isEmpty()) muxedUrl else ""
            ))
        } catch (e: Exception) { onFallback("yt-dlp: ${e.message}") }
    }

    private fun tryFetch(videoId: String, clientIndex: Int, prevTitle: String?, prevArtist: String?, fallbackQuery: String, onSuccess: (YtAudioResult) -> Unit, onError: (String) -> Unit, startedAt: Long = android.os.SystemClock.elapsedRealtime(), deadline: Long = startedAt + 8_000L) {
        val remainingMs = deadline - android.os.SystemClock.elapsedRealtime()
        if (remainingMs <= 0L) {
            AppLogger.w("YT_FETCH", "Innertube resolution budget exceeded videoId=$videoId")
            onError("Innertube resolution budget exceeded")
            return
        }
        if (clientIndex >= playerClients.size) {
            if (fallbackQuery.startsWith("FALLBACK_DONE:")) { onError("Fallback also failed: " + fallbackQuery.removePrefix("FALLBACK_DONE:")); return }
            if (fallbackQuery.isNotEmpty() && remainingMs > 3_000L) { doYoutubeFallback(fallbackQuery, onSuccess, onError, deadline); return }
            onError("All ${playerClients.size} clients failed"); return
        }
        val pc = playerClients[clientIndex]
        try {
            val root = JSONObject(callPlayerApi(videoId, pc))
            val pa = root.optJSONObject("playabilityStatus")
            val status = pa?.optString("status", "?") ?: "?"
            val streamingData = root.optJSONObject("streamingData")
            val af = streamingData?.optJSONArray("adaptiveFormats")
            if (status == "OK" && af != null && af.length() > 0) {
                val vd = root.optJSONObject("videoDetails")
                val title = vd?.optString("title", prevTitle ?: "")?.ifEmpty { prevTitle } ?: (prevTitle ?: "")
                val author = vd?.optString("author", prevArtist ?: "")?.ifEmpty { prevArtist } ?: (prevArtist ?: "")
                var audioUrl = ""; var bb = 0; var bestAudioScore = -1
                for (i in 0 until af.length()) {
                    val f = af.getJSONObject(i)
                    val mt = f.optString("mimeType", "")
                    val br = f.optInt("bitrate", 0)
                    val u = f.optString("url", "")
                    // A signatureCipher URL is not usable as-is: decrypting `s`
                    // requires running YouTube's obfuscated player JS. Skipping
                    // it avoids probing a guaranteed-403 URL. yt-dlp handles
                    // these formats when every client returns a cipher.
                    if (u.isEmpty()) continue
                    val codec = f.optString("codecs", mt)
                    val fastAac = mt.contains("audio/mp4") || codec.contains("mp4a", true)
                    val audioScore = br / 1000 + if (fastAac) 10_000 else 0
                    if (mt.startsWith("audio/") && audioScore > bestAudioScore) {
                        bestAudioScore = audioScore; bb = br; audioUrl = u
                    }
                }
                // Prefer a small muxed MP4 for the audio MediaSession. NX779J
                // reaches AAC PCM much faster from this progressive format
                // than from a separate WebM/Opus adaptive stream. The app
                // disables its video track, so background playback never owns
                // a video decoder.
                val progressive = streamingData.optJSONArray("formats")
                var muxedUrl = ""
                if (progressive != null) {
                    var chosenHeight = Int.MAX_VALUE
                    for (i in 0 until progressive.length()) {
                        val f = progressive.optJSONObject(i) ?: continue
                        val mt = f.optString("mimeType")
                        val height = f.optInt("height", Int.MAX_VALUE)
                        val u = f.optString("url")
                        if (u.isNotEmpty() && mt.contains("video/mp4") && mt.contains("mp4a") &&
                            height < chosenHeight) {
                            chosenHeight = height
                            muxedUrl = u
                        }
                    }
                }
                // Audio-only playback. Probe the adaptive audio URL first and
                // fall back to a progressive MP4 (audio track only) if needed.
                val candidates = listOf(audioUrl, muxedUrl)
                    .filter { it.isNotEmpty() }
                    .distinct()
                val playable = candidates.firstOrNull { isUrlPlayable(it) }
                if (playable == null) {
                    AppLogger.w("YT_FETCH", "${pc.name} returned HTTP-rejected stream; trying next client")
                    tryFetch(videoId, clientIndex + 1, title, author, fallbackQuery, onSuccess, onError, startedAt, deadline)
                    return
                }
                val thumb = vd?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { if (it.length() > 0) it.getJSONObject(it.length() - 1).optString("url", "") else "" } ?: ""
                onSuccess(YtAudioResult(
                    playable, title, author,
                    vd?.optString("lengthSeconds", "0")?.toLongOrNull() ?: 0L,
                    bb, thumb,
                    videoId = videoId,
                    muxedUrl = if (audioUrl.isEmpty()) muxedUrl else ""
                ))
            } else {
                val vd = root.optJSONObject("videoDetails")
                val newT = vd?.optString("title", prevTitle ?: "")?.ifEmpty { prevTitle } ?: prevTitle
                val newA = vd?.optString("author", prevArtist ?: "")?.ifEmpty { prevArtist } ?: prevArtist
                val newFq = if (fallbackQuery.isEmpty() && newT != null && newA != null) "$newA $newT" else fallbackQuery
                tryFetch(videoId, clientIndex + 1, newT, newA, newFq, onSuccess, onError, startedAt, deadline)
            }
        } catch (e: Exception) { tryFetch(videoId, clientIndex + 1, prevTitle, prevArtist, fallbackQuery, onSuccess, onError, startedAt, deadline) }
    }

    private fun doYoutubeFallback(query: String, onSuccess: (YtAudioResult) -> Unit, onError: (String) -> Unit, deadline: Long) {
        YtSearchHelper.search(query, listOf("videos"),
            onResults = { items ->
                if (items.isEmpty()) { onError("Fallback: no YouTube results"); return@search }
                val best = items.map { it to scoreMatch(query, it) }.sortedByDescending { it.second }.first()
                extractVideoId(best.first.url)?.let { vid -> tryFetch(vid, 0, best.first.title, best.first.uploader, "FALLBACK_DONE:${best.first.title}", onSuccess, onError, deadline = deadline) } ?: onError("Fallback: no videoId")
            },
            onError = { onError("Fallback search: $it") })
    }

    private fun scoreMatch(query: String, item: YtSearchItem): Int {
        val qp = query.lowercase().split(" ").filter { it.length > 1 }; var s = 0
        val tw = item.title.lowercase().split(" "); s += qp.count { q -> tw.any { it.contains(q) } } * 20
        val aw = item.uploader.lowercase().split(" "); s += qp.count { q -> aw.any { it.contains(q) } } * 15
        if (item.uploader.lowercase().contains(" - topic")) s += 30
        if (item.title.lowercase().contains("official audio")) s += 25
        if (item.title.lowercase().contains("official music video")) s += 20
        if (item.durationSeconds in 120..600) s += 5
        if (item.title.lowercase().let { it.contains("lyrics") || it.contains("cover") || it.contains("karaoke") || it.contains("カバー") }) s -= 30
        return s
    }

    private fun callPlayerApi(videoId: String, pc: PlayerClient): String {
        val isWeb = pc.osName == null
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", pc.name); put("clientVersion", pc.version); put("hl", "ja"); put("gl", "JP")
                    if (pc.osName != null) { put("platform", "MOBILE"); put("osName", pc.osName); put("osVersion", pc.osVersion!!); if (pc.deviceMake != null) put("deviceMake", pc.deviceMake); if (pc.deviceModel != null) put("deviceModel", pc.deviceModel); if (pc.name == "ANDROID_VR") put("androidSdkVersion", 32) }
                    else { put("platform", "DESKTOP") }
                    if (visitorData.isNotEmpty()) put("visitorData", visitorData)
                    put("utcOffsetMinutes", 0); put("timeZone", "UTC")
                })
                put("request", JSONObject().apply { put("useSsl", true); put("internalExperimentFlags", JSONArray()) })
            })
            put("videoId", videoId); put("contentCheckOk", true); put("racyCheckOk", true)
            put("playbackContext", JSONObject().apply { put("contentPlaybackContext", JSONObject().apply { put("html5Preference", "HTML5_PREF_WANTS") }) })
        }
        val rb = okhttp3.Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("Origin", "https://www.youtube.com").header("Referer", "https://www.youtube.com")
            .header("X-YouTube-Client-Name", pc.id.toString()).header("X-YouTube-Client-Version", pc.version)
        if (cookies.isNotEmpty()) rb.header("Cookie", cookies)
        if (visitorData.isNotEmpty()) rb.header("X-Goog-Visitor-Id", visitorData)
        when (pc.name) {
            "ANDROID_VR" -> rb.header("User-Agent", "com.google.android.apps.youtube.vr.oculus/${pc.version} (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip")
            "VISIONOS" -> rb.header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15")
            "IOS" -> rb.header("User-Agent", IOS_USER_AGENT)
        }
        val apiStartedAt = android.os.SystemClock.elapsedRealtime()
        val r = playerApiClient.newCall(rb.post(body.toString().toRequestBody(jsonType)).build()).execute()
        val statusCode = r.code
        val b = r.body?.string() ?: ""; r.close()
        AppLogger.i("YT_FETCH", "player client=${pc.name} http=$statusCode elapsed=${android.os.SystemClock.elapsedRealtime()-apiStartedAt}ms")
        if (BuildConfig.DEBUG) {
            try { File(safeCachePath, "player_response.json").writeText(b) } catch (_: Exception) {}
        }
        try {
            JSONObject(b).optJSONObject("responseContext")
                ?.optString("visitorData")
                ?.takeIf { it.isNotEmpty() }
                ?.let { saveVisitorData(it) }
        } catch (_: Exception) {}
        return b
    }

    fun fetchBrowseItems(browseId: String, isPlaylist: Boolean, onSuccess: (List<String>) -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                val body = JSONObject().apply {
                    put("context", JSONObject().apply { put("client", JSONObject().apply { put("clientName", "WEB"); put("clientVersion", "2.20260708.00.00"); put("hl", "ja"); put("gl", "JP"); put("platform", "DESKTOP"); put("utcOffsetMinutes", 0) }); put("user", JSONObject().apply { put("lockedSafetyMode", false) }); put("request", JSONObject().apply { put("useSsl", true); put("internalExperimentFlags", JSONArray()) }) })
                    put("browseId", browseId)
                }
                val rb = okhttp3.Request.Builder().url("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false").header("X-YouTube-Client-Name", "1").header("X-YouTube-Client-Version", "2.20260708.00.00").header("Origin", "https://www.youtube.com").header("Referer", "https://www.youtube.com")
                if (cookies.isNotEmpty()) rb.header("Cookie", cookies)
                val r = client.newCall(rb.post(body.toString().toRequestBody(jsonType)).build()).execute(); val respBody = r.body?.string() ?: ""; r.close()
                if (r.code != 200) { onError("Browse API ${r.code}"); return@Thread }
                onSuccess(extractVideoIds(respBody))
            } catch (e: Exception) { onError(e.message ?: "error") }
        }.apply { name = "YtBrowse"; start() }
    }

    fun fetchPlaylist(playlistId: String, onSuccess: (YtPlaylistInfo) -> Unit, onError: (String) -> Unit) {
        val requestedId = playlistId.trim().removePrefix("VL")
        if (requestedId.isEmpty()) {
            onError("Empty playlist")
            return
        }
        Thread {
            try {
                val fallbackEntries = LinkedHashMap<String, YtPlaylistEntry>()
                var title = ""
                try {
                    val regular = browse(JSONObject().apply {
                        put("context", browseContext())
                        put("browseId", "VL$requestedId")
                    })
                    title = extractPlaylistTitle(regular)
                    collectPlaylistEntries(regular, fallbackEntries)
                } catch (t: Throwable) {
                    AppLogger.w("YT_PLAYLIST", "regular metadata failed", t)
                }

                val entries = LinkedHashMap<String, YtPlaylistEntry>()
                var continuation: String? = null
                var pages = 0
                try {
                    do {
                        val body = JSONObject().apply {
                            put("context", musicContext())
                            if (continuation == null) put("browseId", "VL$requestedId") else put("continuation", continuation)
                        }
                        val response = browseMusic(body)
                        collectMusicEntries(response, entries)
                        val next = findPlaylistContinuation(response)
                        continuation = if (next != null && next != continuation) next else null
                        pages++
                    } while (continuation != null && pages < 20)
                } catch (t: Throwable) {
                    AppLogger.w("YT_PLAYLIST", "music metadata failed", t)
                }

                val chosen = if (entries.isNotEmpty()) entries else fallbackEntries
                if (chosen.isEmpty()) {
                    onError("No tracks found")
                } else {
                    if (title.isBlank() || looksLikeDuration(title)) {
                        title = chosen.values.firstOrNull()?.album.orEmpty()
                    }
                    onSuccess(YtPlaylistInfo(requestedId, cleanPlaylistTitle(title), chosen.values.toList()))
                }
            } catch (t: Throwable) {
                onError(t.message ?: "Playlist fetch failed")
            }
        }.apply { name = "YtPlaylist"; start() }
    }

    private fun musicContext(): JSONObject = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", "WEB_REMIX"); put("clientVersion", "1.20240710.01.00")
            put("hl", "ja"); put("gl", "JP"); put("platform", "DESKTOP")
            put("utcOffsetMinutes", 0)
        })
        put("user", JSONObject().apply { put("lockedSafetyMode", false) })
        put("request", JSONObject().apply { put("useSsl", true); put("internalExperimentFlags", JSONArray()) })
    }

    private fun browseMusic(body: JSONObject): JSONObject = postMusic("browse", body)

    private fun postMusic(path: String, body: JSONObject): JSONObject {
        val rb = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$path?prettyPrint=false")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", "1.20240710.01.00")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com")
            .header("Accept-Language", "ja-JP,ja;q=0.9")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        if (cookies.isNotEmpty()) rb.header("Cookie", cookies)
        if (visitorData.isNotEmpty()) rb.header("X-Goog-Visitor-Id", visitorData)
        client.newCall(rb.post(body.toString().toRequestBody(jsonType)).build()).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) error("Music HTTP ${response.code}")
            return JSONObject(text)
        }
    }

    private fun collectMusicEntries(root: JSONObject, entries: MutableMap<String, YtPlaylistEntry>) {
        val items = mutableListOf<JSONObject>()
        collectObjects(root, "musicResponsiveListItemRenderer", items)
        for (item in items) {
            val videoId = findFirstString(item, "videoId").orEmpty()
            if (videoId.length != 11 || !videoId.matches(Regex("[a-zA-Z0-9_-]{11}"))) continue
            if (entries.containsKey(videoId)) continue
            val title = musicFlexText(item, 0).ifBlank { "YouTube" }
            val artist = musicFlexText(item, 1)
            val albumText = musicFlexText(item, 2)
            val album = if (looksLikeDuration(albumText)) "" else albumText
            val duration = musicDuration(item).takeIf { it > 0 } ?: parseDurationSeconds(albumText)
            entries[videoId] = YtPlaylistEntry(videoId, title, artist, duration, musicThumbnail(item, videoId), album)
        }
    }

    private fun musicFlexText(renderer: JSONObject, index: Int): String {
        val columns = renderer.optJSONArray("flexColumns") ?: return ""
        val column = columns.optJSONObject(index)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?: return ""
        return readJsonText(column.optJSONObject("text"))
    }

    private fun musicDuration(renderer: JSONObject): Long {
        val fixed = renderer.optJSONArray("fixedColumns")
        if (fixed != null) {
            for (i in 0 until fixed.length()) {
                val text = readJsonText(
                    fixed.optJSONObject(i)
                        ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                        ?.optJSONObject("text")
                )
                val seconds = parseDurationSeconds(text)
                if (seconds > 0) return seconds
            }
        }
        return 0L
    }

    private fun musicThumbnail(renderer: JSONObject, videoId: String): String {
        val thumbs = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val url = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
        return url.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
    }

    fun fetchMix(seedVideoId: String, mixId: String, onSuccess: (YtPlaylistInfo) -> Unit, onError: (String) -> Unit) {
        if (seedVideoId.length != 11) {
            onError("ミックスの起点曲を特定できません")
            return
        }
        Thread {
            try {
                val body = JSONObject().apply {
                    put("context", musicContext())
                    put("videoId", seedVideoId)
                    put("playlistId", mixId)
                }
                val response = postMusic("next", body)
                val panels = mutableListOf<JSONObject>()
                collectObjects(response, "playlistPanelVideoRenderer", panels)
                val entries = LinkedHashMap<String, YtPlaylistEntry>()
                for (panel in panels) {
                    parsePanelEntry(panel)?.let { entry ->
                        if (!entries.containsKey(entry.videoId)) entries[entry.videoId] = entry
                    }
                }
                if (entries.isEmpty()) {
                    onError("ミックスを取得できませんでした")
                    return@Thread
                }
                val header = findObject(response, "musicQueueHeaderRenderer")
                val subtitle = readJsonText(header?.optJSONObject("subtitle"))
                val seedTitle = entries.values.firstOrNull()?.title.orEmpty()
                val title = Regex("「(.+?)」").find(subtitle)?.groupValues?.get(1)?.let { "$it ミックス" }
                    ?: seedTitle.takeIf { it.isNotBlank() }?.let { "$it ミックス" }
                    ?: "YouTube ミックス"
                onSuccess(YtPlaylistInfo(mixId, title, entries.values.toList()))
            } catch (t: Throwable) {
                onError(t.message ?: "ミックス取得失敗")
            }
        }.apply { name = "YtMix"; start() }
    }

    private fun parsePanelEntry(renderer: JSONObject): YtPlaylistEntry? {
        val videoId = renderer.optString("videoId")
        if (videoId.length != 11 || !videoId.matches(Regex("[a-zA-Z0-9_-]{11}"))) return null
        val title = readJsonText(renderer.optJSONObject("title")).ifBlank { "YouTube" }
        val artist = readJsonText(renderer.optJSONObject("shortBylineText")).ifBlank {
            readJsonText(renderer.optJSONObject("longBylineText"))
        }
        val duration = parseDurationSeconds(readJsonText(renderer.optJSONObject("lengthText")))
        val thumb = renderer.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { it.optJSONObject(it.length() - 1)?.optString("url").orEmpty() }
            .orEmpty()
        return YtPlaylistEntry(videoId, title, artist, duration, thumb.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" })
    }

    private fun fetchMusicMetadata(videoId: String): YtPlaylistEntry? {
        if (videoId.isBlank()) return null
        return try {
            val body = JSONObject().apply {
                put("context", musicContext())
                put("videoIds", JSONArray().put(videoId))
            }
            val response = postMusic("music/get_queue", body)
            val queue = response.optJSONArray("queueDatas") ?: return null
            for (i in 0 until queue.length()) {
                val renderer = queue.optJSONObject(i)
                    ?.optJSONObject("content")
                    ?.optJSONObject("playlistPanelVideoRenderer")
                    ?: continue
                if (renderer.optString("videoId") != videoId && i > 0) continue
                val title = readJsonText(renderer.optJSONObject("title"))
                val artist = readJsonText(renderer.optJSONObject("shortBylineText"))
                val duration = parseDurationSeconds(readJsonText(renderer.optJSONObject("lengthText")))
                val thumb = renderer.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
                    ?.let { it.optJSONObject(it.length() - 1)?.optString("url").orEmpty() }
                    .orEmpty()
                if (title.isBlank() && artist.isBlank()) continue
                return YtPlaylistEntry(videoId, title, artist, duration, thumb)
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    fun musicMetadata(videoId: String): YtPlaylistEntry? {
        if (videoId.isBlank()) return null
        return musicMetaCache[videoId]
            ?: fetchMusicMetadata(videoId)?.also { musicMetaCache[videoId] = it }
    }

    private fun enrichWithMusicMetadata(videoId: String, result: YtAudioResult): YtAudioResult {
        val meta = musicMetadata(videoId) ?: return result
        return result.copy(
            title = meta.title.ifBlank { result.title },
            uploader = meta.artist.ifBlank { result.uploader },
            durationSeconds = if (meta.durationSeconds > 0) meta.durationSeconds else result.durationSeconds,
            thumbnailUrl = meta.thumbnailUrl.ifBlank { result.thumbnailUrl }
        )
    }

    private fun browseContext(): JSONObject = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", "WEB"); put("clientVersion", "2.20260708.00.00")
            put("hl", "ja"); put("gl", "JP"); put("platform", "DESKTOP")
            put("utcOffsetMinutes", 0)
        })
        put("user", JSONObject().apply { put("lockedSafetyMode", false) })
        put("request", JSONObject().apply { put("useSsl", true); put("internalExperimentFlags", JSONArray()) })
    }

    private fun browse(body: JSONObject): JSONObject {
        val rb = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", "2.20260708.00.00")
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        if (cookies.isNotEmpty()) rb.header("Cookie", cookies)
        if (visitorData.isNotEmpty()) rb.header("X-Goog-Visitor-Id", visitorData)
        client.newCall(rb.post(body.toString().toRequestBody(jsonType)).build()).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) error("Browse HTTP ${response.code}")
            return JSONObject(text)
        }
    }

    private fun extractPlaylistTitle(root: JSONObject): String {
        for (key in listOf("playlistHeaderRenderer", "playlistSidebarPrimaryInfoRenderer", "musicDetailHeaderRenderer")) {
            val renderer = findObject(root, key) ?: continue
            val title = readJsonText(renderer.optJSONObject("title"))
            if (title.isNotBlank()) return title
        }
        val pageHeader = findObject(root, "pageHeaderViewModel")
        return readJsonText(pageHeader?.optJSONObject("title"))
    }

    private fun cleanPlaylistTitle(raw: String): String {
        var title = raw.trim()
        title = title.replace(Regex("^(アルバム|Album|プレイリスト|Playlist|ミックス|Mix)\\s*[-–—:]\\s*", RegexOption.IGNORE_CASE), "")
        title = title.removeSuffix(" - YouTube").trim()
        if (looksLikeDuration(title)) title = ""
        return title.ifBlank { "YouTube Playlist" }
    }

    private fun looksLikeDuration(value: String): Boolean =
        value.trim().matches(Regex("""\d{1,3}(?::\d{2}){1,2}"""))

    private fun collectPlaylistEntries(root: JSONObject, entries: MutableMap<String, YtPlaylistEntry>) {
        val lockups = mutableListOf<JSONObject>()
        collectObjects(root, "lockupViewModel", lockups)
        for (lockup in lockups) {
            val videoId = lockup.optString("contentId")
            if (videoId.length != 11 || !videoId.matches(Regex("[a-zA-Z0-9_-]{11}"))) continue
            val contentType = lockup.optString("contentType")
            if (contentType.isNotEmpty() && !contentType.contains("VIDEO", ignoreCase = true)) continue
            if (entries.containsKey(videoId)) continue

            val metadata = lockup.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
            val title = readJsonText(metadata?.optJSONObject("title")).ifBlank { "YouTube" }
            var artist = ""
            var duration = badgeDurationSeconds(lockup)
            val rows = metadata?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
            if (rows != null) {
                for (i in 0 until rows.length()) {
                    val parts = rows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
                    if (artist.isBlank() && parts.length() > 0) {
                        artist = readJsonText(parts.optJSONObject(0)?.optJSONObject("text"))
                    }
                    if (duration <= 0) {
                        for (p in 0 until parts.length()) {
                            duration = parseDurationSeconds(readJsonText(parts.optJSONObject(p)?.optJSONObject("text")))
                            if (duration > 0) break
                        }
                    }
                }
            }
            entries[videoId] = YtPlaylistEntry(videoId, title, artist, duration, lockupThumbnail(lockup))
        }
    }

    private fun findPlaylistContinuation(root: JSONObject): String? {
        val items = mutableListOf<JSONObject>()
        collectObjects(root, "continuationItemViewModel", items)
        collectObjects(root, "continuationItemRenderer", items)
        val ordered = items.sortedByDescending { it.optString("trigger").startsWith("CONTINUATION_TRIGGER_ON_ITEM") }
        for (item in ordered) {
            val token = findFirstString(item, "token")
            if (!token.isNullOrBlank()) return token
        }
        return null
    }

    private fun collectObjects(value: Any?, key: String, out: MutableList<JSONObject>) {
        when (value) {
            is JSONObject -> {
                value.optJSONObject(key)?.let { out.add(it) }
                val keys = value.keys()
                while (keys.hasNext()) collectObjects(value.opt(keys.next()), key, out)
            }
            is JSONArray -> {
                for (i in 0 until value.length()) collectObjects(value.opt(i), key, out)
            }
        }
    }

    private fun findObject(value: Any?, key: String): JSONObject? {
        when (value) {
            is JSONObject -> {
                value.optJSONObject(key)?.let { return it }
                val keys = value.keys()
                while (keys.hasNext()) findObject(value.opt(keys.next()), key)?.let { return it }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) findObject(value.opt(i), key)?.let { return it }
            }
        }
        return null
    }

    private fun findFirstString(value: Any?, key: String): String? {
        when (value) {
            is JSONObject -> {
                value.optString(key).takeIf { it.isNotBlank() }?.let { return it }
                val keys = value.keys()
                while (keys.hasNext()) findFirstString(value.opt(keys.next()), key)?.let { return it }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) findFirstString(value.opt(i), key)?.let { return it }
            }
        }
        return null
    }

    private fun readJsonText(value: JSONObject?): String {
        if (value == null) return ""
        value.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        value.optString("content").takeIf { it.isNotBlank() }?.let { return it }
        value.optString("text").takeIf { it.isNotBlank() }?.let { return it }
        value.optJSONObject("dynamicTextViewModel")?.optJSONObject("text")?.let { nested ->
            readJsonText(nested).takeIf { it.isNotBlank() }?.let { return it }
        }
        val runs = value.optJSONArray("runs")
        if (runs != null) {
            val text = buildString {
                for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty())
            }
            if (text.isNotBlank()) return text
        }
        return ""
    }

    private fun lockupThumbnail(lockup: JSONObject): String {
        val sources = lockup.optJSONObject("contentImage")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONObject("image")
            ?.optJSONArray("sources") ?: return ""
        return sources.optJSONObject(sources.length() - 1)?.optString("url").orEmpty()
    }

    private fun badgeDurationSeconds(lockup: JSONObject): Long {
        val badges = mutableListOf<JSONObject>()
        collectObjects(lockup, "thumbnailBadgeViewModel", badges)
        for (badge in badges) {
            val seconds = parseDurationSeconds(badge.optString("text"))
            if (seconds > 0) return seconds
        }
        return 0L
    }

    private fun parseDurationSeconds(raw: String): Long {
        if (raw.isBlank()) return 0L
        Regex("(\\d{1,3}):(\\d{2})(?::(\\d{2}))?").find(raw)?.let { match ->
            val first = match.groupValues[1].toLongOrNull() ?: return 0L
            val second = match.groupValues[2].toLongOrNull() ?: return 0L
            val third = match.groupValues[3].toLongOrNull()
            return if (third != null) first * 3600L + second * 60L + third else first * 60L + second
        }
        Regex("(\\d+)\\s*分\\s*(\\d+)\\s*秒").find(raw)?.let { match ->
            return (match.groupValues[1].toLongOrNull() ?: 0L) * 60L + (match.groupValues[2].toLongOrNull() ?: 0L)
        }
        Regex("(\\d+)\\s*秒").find(raw)?.let { match -> return match.groupValues[1].toLongOrNull() ?: 0L }
        return 0L
    }

    private fun extractVideoIds(json: String): List<String> {
        val ids = mutableListOf<String>(); val root = JSONObject(json)
        fun walk(obj: JSONObject, depth: Int) { if (depth > 15) return; val keys = obj.keys(); while (keys.hasNext()) { val key = keys.next(); if (key == "videoId") { val vid = obj.optString("videoId", ""); if (vid.length == 11) ids.add(vid) }; val child = obj.optJSONObject(key); if (child != null) walk(child, depth + 1); val arr = obj.optJSONArray(key); if (arr != null) for (i in 0 until arr.length()) { val item = arr.optJSONObject(i); if (item != null) walk(item, depth + 1) } } }; walk(root, 0); return ids
    }

    private fun extractVideoId(url: String): String? = Regex("v=([a-zA-Z0-9_-]{11})").find(url)?.groupValues?.get(1) ?: Regex("youtu\\.be/([a-zA-Z0-9_-]{11})").find(url)?.groupValues?.get(1)

    /**
     * YouTube returns different CDN URLs per player client. Some clients
     * (notably ANDROID_VR) hand out URLs which immediately answer HTTP 403
     * on mobile carriers even though the same URL works elsewhere. Probe the
     * URL with a one-byte Range request before exposing it to ExoPlayer.
     */
    private fun isUrlPlayable(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1")
                .header("User-Agent", IOS_USER_AGENT)
                .build()
            val startedAt = android.os.SystemClock.elapsedRealtime()
            probeClient.newCall(request).execute().use { response ->
                AppLogger.i("YT_FETCH", "url probe http=${response.code} elapsed=${android.os.SystemClock.elapsedRealtime()-startedAt}ms")
                response.code in 200..299
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * yt-dlp understands Netscape cookies.txt. Convert the cookie header we
     * captured from the WebView login and hand it to the fallback resolver so
     * authenticated/Premium items can be resolved as well.
     */
    private fun writeCookieFile(context: Context): File? {
        val pairs = parseCookies(cookies)
        if (pairs.isEmpty()) return null
        // Round the expiry to whole days so the file contents (and therefore
        // the hash below) stay stable across calls.
        val expiry = (System.currentTimeMillis() / 1000L / 86_400L + 365L) * 86_400L
        val text = StringBuilder("# Netscape HTTP Cookie File\n")
        for (domain in listOf(".youtube.com", ".google.com", ".googlevideo.com")) {
            for ((name, value) in pairs) {
                if (name.isBlank() || value.isBlank()) continue
                val safeName = name.replace("\t", "").replace("\n", "")
                val safeValue = value.replace("\t", "").replace("\n", "")
                text.append("#HttpOnly_").append(domain)
                    .append("\tTRUE\t/\tTRUE\t").append(expiry)
                    .append('\t').append(safeName)
                    .append('\t').append(safeValue)
                    .append('\n')
            }
        }
        val content = text.toString()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return try {
            val file = File(context.cacheDir, "yt_dlp_cookies.txt")
            if (hash == cookieFileHash && file.isFile) return file
            file.writeText(content)
            cookieFileHash = hash
            file
        } catch (_: Throwable) {
            null
        }
    }
}
