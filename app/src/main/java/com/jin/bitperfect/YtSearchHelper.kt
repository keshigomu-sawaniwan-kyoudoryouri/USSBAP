package com.jin.bitperfect

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class YtSearchItem(
    val url: String,
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val thumbnailUrl: String
)

object YtSearchHelper {
    // Recommendations are fetched in the background; keeping them on a
    // separate executor means opening the search tab never queues a user
    // query behind a slow recommendation request.
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "YtSearchUser") }
    private val recommendationExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "YtSearchRec") }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private const val CACHE_TTL_MS = 5L * 60L * 1000L
    private const val CLIENT_VERSION = "2.20260708.00.00"

    private data class CachedResults(val items: List<YtSearchItem>, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, CachedResults>()

    fun search(
        query: String,
        contentFilter: List<String> = emptyList(),
        onResults: (List<YtSearchItem>) -> Unit,
        onError: (String) -> Unit,
        isRecommendation: Boolean = false
    ) {
        if (query.isBlank()) {
            onResults(emptyList())
            return
        }
        val key = query.trim().lowercase() + "|" + contentFilter.joinToString(",")
        cache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
            AppLogger.i("YT_SEARCH", "cache hit for \"$query\" (${it.items.size} results)")
            onResults(it.items)
            return
        }
        val target = if (isRecommendation) recommendationExecutor else executor
        target.execute {
            // Innertube is a single JSON request; the old HTML/NewPipe path
            // costs several round trips and cannot be shared with playback.
            val innertube = try {
                searchInnertube(query, contentFilter)
            } catch (t: Throwable) {
                AppLogger.w("YT_SEARCH", "Innertube search failed, falling back to NewPipe: ${t.message}")
                null
            }
            if (innertube != null) {
                cache[key] = CachedResults(innertube, System.currentTimeMillis() + CACHE_TTL_MS)
                logResults(query, innertube)
                onResults(innertube)
                return@execute
            }
            searchWithNewPipe(query, contentFilter, onResults, onError)
        }
    }

    private fun logResults(query: String, results: List<YtSearchItem>) {
        AppLogger.i("YT_SEARCH", "Query \"$query\" returned ${results.size} results")
        results.take(5).forEachIndexed { index, result ->
            AppLogger.i("YT_SEARCH_RESULT", "[$index] ${result.title} | ${result.uploader} | ${result.url}")
        }
    }

    private fun searchInnertube(query: String, contentFilter: List<String>): List<YtSearchItem>? {
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "ja")
                    put("gl", "JP")
                    put("platform", "DESKTOP")
                    put("utcOffsetMinutes", 0)
                })
                put("user", JSONObject().apply { put("lockedSafetyMode", false) })
                put("request", JSONObject().apply { put("useSsl", true); put("internalExperimentFlags", JSONArray()) })
            })
            put("query", query)
        }
        if (contentFilter.contains("videos")) {
            // Only videoRenderer entries are parsed below, so channels and
            // playlists are already excluded without relying on `params`.
        }
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com")
            .header("Accept-Language", "ja-JP,ja;q=0.9")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Search HTTP ${response.code}")
            val root = JSONObject(text)
            val renderers = mutableListOf<JSONObject>()
            collectObjects(root, "videoRenderer", renderers)
            val results = renderers.mapNotNull { renderer ->
                val videoId = renderer.optString("videoId")
                if (videoId.length != 11) return@mapNotNull null
                val title = readText(renderer.optJSONObject("title"))
                if (title.isBlank()) return@mapNotNull null
                val thumbs = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val thumbnail = if (thumbs != null && thumbs.length() > 0) {
                    thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
                } else ""
                YtSearchItem(
                    url = "https://www.youtube.com/watch?v=$videoId",
                    title = title,
                    uploader = readText(renderer.optJSONObject("ownerText")),
                    durationSeconds = parseDuration(renderer.optJSONObject("lengthText")?.optString("simpleText").orEmpty()),
                    thumbnailUrl = thumbnail.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
                )
            }.distinctBy { it.url }
            if (results.isEmpty()) error("Innertube returned no video results")
            return results
        }
    }

    private fun searchWithNewPipe(
        query: String,
        contentFilter: List<String>,
        onResults: (List<YtSearchItem>) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val service = NewPipe.getServiceByUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            val factory = service.searchQHFactory
            val handler = factory.fromQuery(query, contentFilter, "")
            val extractor: SearchExtractor = service.getSearchExtractor(handler)
            extractor.fetchPage()
            val items = extractor.initialPage.items
            val results = items.filterIsInstance<StreamInfoItem>().map { item ->
                val thumbnails = item.thumbnails
                val thumbUrl = if (thumbnails.isNotEmpty()) thumbnails[0].getUrl() else ""
                YtSearchItem(
                    url = item.url,
                    title = item.name,
                    uploader = item.uploaderName ?: "",
                    durationSeconds = item.duration,
                    thumbnailUrl = thumbUrl
                )
            }
            logResults(query, results)
            onResults(results)
        } catch (e: ExtractionException) {
            if (e is SearchExtractor.NothingFoundException) {
                AppLogger.i("YT_SEARCH", "No results for \"$query\"")
                onResults(emptyList())
            } else {
                AppLogger.e("YT_SEARCH", "Search error", e)
                onError("Search failed: ${e.message}")
            }
        } catch (e: IOException) {
            AppLogger.e("YT_SEARCH", "Network error", e)
            onError("Network error: ${e.message}")
        } catch (e: Exception) {
            AppLogger.e("YT_SEARCH", "Unexpected error", e)
            onError("${e.message}")
        }
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

    private fun readText(value: JSONObject?): String {
        if (value == null) return ""
        value.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = value.optJSONArray("runs") ?: return ""
        return buildString {
            for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }.trim()
    }

    private fun parseDuration(raw: String): Long {
        val match = Regex("(\\d{1,3}):(\\d{2})(?::(\\d{2}))?").find(raw) ?: return 0L
        val first = match.groupValues[1].toLongOrNull() ?: return 0L
        val second = match.groupValues[2].toLongOrNull() ?: return 0L
        val third = match.groupValues[3].toLongOrNull()
        return if (third != null) first * 3600L + second * 60L + third else first * 60L + second
    }
}
