package com.jin.bitperfect

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object YtMusicSearchHelper {
    private const val PLAYER_URL = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val CLIENT_NAME = "ANDROID_MUSIC"
    private const val CLIENT_VERSION = "7.29.53"
    private const val CLIENT_ID = "26"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val executor = Executors.newSingleThreadExecutor()

    fun search(
        context: Context,
        query: String,
        localization: String = "ja-JP",
        onResults: (List<YtSearchItem>) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                val parts = localization.split("-")
                val hl = parts[0]
                val gl = if (parts.size > 1) parts[1] else "JP"

                val bodyJson = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", CLIENT_NAME)
                            put("clientVersion", CLIENT_VERSION)
                            put("hl", hl)
                            put("gl", gl)
                            put("platform", "MOBILE")
                            put("osName", "Android")
                            put("osVersion", "15")
                            put("androidSdkVersion", 34)
                            put("utcOffsetMinutes", -540)
                        })
                        put("user", JSONObject().apply {
                            put("lockedSafetyMode", false)
                        })
                        put("request", JSONObject().apply {
                            put("useSsl", true)
                            put("internalExperimentFlags", JSONArray())
                        })
                    })
                    put("query", query)
                }

val req = okhttp3.Request.Builder()
                    .url(PLAYER_URL)
                    .header("Origin", "https://music.youtube.com")
                    .header("Referer", "https://music.youtube.com")
                    .header("X-YouTube-Client-Name", CLIENT_ID)
                    .header("X-YouTube-Client-Version", CLIENT_VERSION)
                    .header("User-Agent", "com.google.android.apps.youtube.music/${CLIENT_VERSION} (Linux; U; Android 15; JP) gzip")
                    .post(bodyJson.toString().toRequestBody(jsonType))
                    .build()

                val resp = client.newCall(req).execute()
                val respBody = resp.body?.string() ?: ""
                resp.close()

                if (resp.code != 200 || respBody.length < 200) {
                    onError("HTTP ${resp.code} bodyLen=${respBody.length}")
                    return@execute
                }

                val results = parseSearchResults(respBody, context)
                // Save raw response for investigation
                if (BuildConfig.DEBUG) {
                    try {
                        val file = java.io.File(context.cacheDir, "ytm_search_response.json")
                        file.writeText(respBody)
                        AppLogger.i("YTM_DEBUG", "Saved ${respBody.length}B to cache")
                    } catch (_: Exception) {}
                }
                onResults(results)
            } catch (e: Exception) {
                onError("${e.message}")
            }
        }
    }

    private fun buildConsentCookie(): String {
        return "SOCS=CAESNQgDEitib3F1ZXN0c19iaW5hcnlfbW9kZWxfY29uc2VudF9zZWN1cml0eV9lbmFibGVk; " +
            "CONSENT=PENDING+" + (System.currentTimeMillis() / 1000).toInt()
    }

    private fun parseSearchResults(json: String, context: Context): List<YtSearchItem> {
        val results = mutableListOf<YtSearchItem>()
        val root = JSONObject(json)
        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs") ?: return results

        val sectionCounts = mutableMapOf<String, Int>()

        for (ti in 0 until tabs.length()) {
            val tab = tabs.getJSONObject(ti)
            val sections = tab.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: continue

            for (si in 0 until sections.length()) {
                val section = sections.getJSONObject(si)

                // list all renderer types in this section
                val keys = section.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.endsWith("Renderer") || key.endsWith("ShelfRenderer")) {
                        sectionCounts[key] = (sectionCounts[key] ?: 0) + 1
                    }
                }

                // 1. musicCardShelfRenderer (Top Result card)
                val cardShelf = section.optJSONObject("musicCardShelfRenderer")
                if (cardShelf != null && cardShelf.length() > 0) {
                    parseSongCard(cardShelf, results)
                }

                // 2. musicShelfRenderer (song list shelf)
                val shelf = section.optJSONObject("musicShelfRenderer")
                if (shelf != null && shelf.length() > 0) {
                    val items = shelf.optJSONArray("contents")
                    if (items != null) {
                        for (ii in 0 until items.length()) {
                            val renderer = items.getJSONObject(ii)
                                .optJSONObject("musicResponsiveListItemRenderer")
                            if (renderer != null && renderer.length() > 0) {
                                parseListItemSong(renderer, results)
                            }
                        }
                    }
                }

                // 3. itemSectionRenderer (song list without shelf wrapper)
                val itemSection = section.optJSONObject("itemSectionRenderer")
                if (itemSection != null && itemSection.length() > 0) {
                    val items = itemSection.optJSONArray("contents")
                    if (items != null) {
                        for (ii in 0 until items.length()) {
                            val renderer = items.getJSONObject(ii)
                                .optJSONObject("musicResponsiveListItemRenderer")
                            if (renderer != null && renderer.length() > 0) {
                                logAndParseListItemSong(renderer, ii, results, context)
                            }
                        }
                    }
                }
            }
        }

        // Log shelf types found
        AppLogger.i("YTM_DEBUG", "Shelf types: $sectionCounts, total results: ${results.size}")

        return results
    }

    private fun parseSongCard(cardShelf: JSONObject, results: MutableList<YtSearchItem>) {
        val titleRuns = cardShelf.optJSONObject("title")?.optJSONArray("runs")
        var title = ""
        var videoId = ""
        if (titleRuns != null) {
            for (ri in 0 until titleRuns.length()) {
                val run = titleRuns.getJSONObject(ri)
                title += run.optString("text", "")
                if (videoId.isEmpty()) {
                    videoId = run.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("watchEndpoint")
                        ?.optString("videoId") ?: ""
                }
            }
        }
        if (title.isEmpty() || videoId.isEmpty()) return

        val subtitleRuns = cardShelf.optJSONObject("subtitle")?.optJSONArray("runs")
        var artist = ""
        if (subtitleRuns != null) {
            for (ri in 0 until subtitleRuns.length()) {
                val text = subtitleRuns.getJSONObject(ri).optString("text", "")
                if (text != "曲" && text != " • " && !text.matches(Regex("\\d+:\\d+"))) {
                    artist += text
                }
            }
        }

        val thumbs = cardShelf.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        var thumbUrl = ""
        if (thumbs != null && thumbs.length() > 0) {
            thumbUrl = thumbs.getJSONObject(thumbs.length() - 1).optString("url", "")
        }

        if (videoId.isEmpty()) {
            val buttons = cardShelf.optJSONArray("buttons")
            if (buttons != null) {
                for (bi in 0 until buttons.length()) {
                    val cmd = buttons.getJSONObject(bi)
                        .optJSONObject("buttonRenderer")
                        ?.optJSONObject("command")
                    val wid = cmd?.optJSONObject("watchEndpoint")?.optString("videoId")
                    if (!wid.isNullOrEmpty()) { videoId = wid; break }
                }
            }
        }

        results.add(YtSearchItem(
            url = "https://www.youtube.com/watch?v=$videoId",
            title = title,
            uploader = artist,
            durationSeconds = 0,
            thumbnailUrl = thumbUrl
        ))
    }

    private fun logAndParseListItemSong(renderer: JSONObject, index: Int, results: MutableList<YtSearchItem>, context: Context) {
        // Extract all metadata for logging
        val titleRuns = renderer.optJSONArray("flexColumns")
            ?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
        val title = if (titleRuns != null && titleRuns.length() > 0) {
            titleRuns.getJSONObject(0).optString("text", "?")
        } else "?"

        val artistRuns = renderer.optJSONArray("flexColumns")
            ?.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
        val artist = if (artistRuns != null && artistRuns.length() > 0) {
            artistRuns.getJSONObject(artistRuns.length() - 1).optString("text", "?")
        } else "?"

        val flexColsCount = renderer.optJSONArray("flexColumns")?.length() ?: 0

        // videoId from overlay
        val videoIdOverlay = renderer.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId") ?: ""

        val musicVideoType = renderer.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optJSONObject("watchEndpointMusicSupportedConfigs")
            ?.optJSONObject("watchEndpointMusicConfig")
            ?.optString("musicVideoType") ?: "?"

        // navigationEndpoint at top level
        val navEndpoint = renderer.optJSONObject("navigationEndpoint")
        val navWatchEndpoint = navEndpoint?.optJSONObject("watchEndpoint")
        val navVideoId = navWatchEndpoint?.optString("videoId") ?: ""

        // browseId from flexColumns[1] runs
        var browseId = ""
        if (artistRuns != null) {
            for (ri in 0 until artistRuns.length()) {
                val bid = artistRuns.getJSONObject(ri)
                    .optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId") ?: ""
                if (bid.isNotEmpty()) { browseId = bid; break }
            }
        }

        // playlistId from menu items
        var playlistId = ""
        val menu = renderer.optJSONObject("menu")
            ?.optJSONObject("menuRenderer")
            ?.optJSONArray("items")
        if (menu != null) {
            for (mi in 0 until menu.length()) {
                val pid = menu.getJSONObject(mi)
                    .optJSONObject("menuNavigationItemRenderer")
                    ?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("playlistId") ?: ""
                if (pid.isNotEmpty()) { playlistId = pid; break }
            }
        }

        val trackingParams = renderer.optString("trackingParams", "").take(20)

        // Now try to parse and report reason
        val parseResult = tryExtractVideoId(renderer)
        val reason = if (parseResult == null) {
            val navDirect = renderer.optJSONObject("navigationEndpoint")
            val hasDirectNav = navDirect != null && navDirect.length() > 0
            val hasOverlay = videoIdOverlay.isNotEmpty()
            if (!hasDirectNav && !hasOverlay)
                "no navigationEndpoint, no overlay videoId"
            else if (hasDirectNav && navVideoId.isEmpty())
                "navigationEndpoint exists but no watchEndpoint.videoId"
            else if (hasOverlay)
                "overlay videoId=$videoIdOverlay but extractVideoId failed"
            else
                "unknown reason"
        } else "OK (videoId=$parseResult)"

        AppLogger.i("YTM_ITEM", "[$index] title=\"$title\" artist=\"$artist\" flexCols=$flexColsCount " +
            "videoIdOverlay=$videoIdOverlay navVideoId=$navVideoId browseId=$browseId " +
            "playlistId=$playlistId musicVideoType=$musicVideoType tr=$trackingParams → $reason")

        // Still call the original parser
        parseListItemSong(renderer, results)
    }

    private fun tryExtractVideoId(renderer: JSONObject): String? {
        var videoId = renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId") ?: ""
        if (videoId.isEmpty()) {
            videoId = renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId") ?: ""
        }
        if (videoId.isEmpty()) {
            val flexCols = renderer.optJSONArray("flexColumns")
            if (flexCols != null && flexCols.length() >= 1) {
                val runs = flexCols.getJSONObject(0)
                    .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")
                if (runs != null && runs.length() >= 1) {
                    videoId = runs.getJSONObject(0)
                        .optJSONObject("navigationEndpoint")
                        ?.optJSONObject("watchEndpoint")
                        ?.optString("videoId") ?: ""
                }
            }
        }
        return if (videoId.isEmpty()) null else videoId
    }

    private fun parseListItemSong(renderer: JSONObject, results: MutableList<YtSearchItem>) {
        // Try multiple locations for videoId
        var videoId = renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId") ?: ""

        if (videoId.isEmpty()) {
            // try overlay.play button
            videoId = renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId") ?: ""
        }

        if (videoId.isEmpty()) {
            // try flexColumns[0].runs[0].navigationEndpoint
            val flexCols = renderer.optJSONArray("flexColumns")
            if (flexCols != null && flexCols.length() >= 1) {
                val runs = flexCols.getJSONObject(0)
                    .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")
                if (runs != null && runs.length() >= 1) {
                    videoId = runs.getJSONObject(0)
                        .optJSONObject("navigationEndpoint")
                        ?.optJSONObject("watchEndpoint")
                        ?.optString("videoId") ?: ""
                }
            }
        }

        if (videoId.isEmpty()) return

        val flexCols = renderer.optJSONArray("flexColumns")
        var title = ""
        var artist = ""

        if (flexCols.length() >= 1) {
            val runs = flexCols.getJSONObject(0)
                .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
            if (runs != null) {
                for (ri in 0 until runs.length()) {
                    title += runs.getJSONObject(ri).optString("text", "")
                }
            }
        }

        if (flexCols.length() >= 2) {
            val runs = flexCols.getJSONObject(1)
                .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
            if (runs != null) {
                for (ri in 0 until runs.length()) {
                    artist += runs.getJSONObject(ri).optString("text", "")
                }
            }
        }

        if (title.isEmpty()) return

        val thumbs = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        var thumbUrl = ""
        if (thumbs != null && thumbs.length() > 0) {
            thumbUrl = thumbs.getJSONObject(thumbs.length() - 1).optString("url", "")
        }

        results.add(YtSearchItem(
            url = "https://www.youtube.com/watch?v=$videoId",
            title = title,
            uploader = artist,
            durationSeconds = 0,
            thumbnailUrl = thumbUrl
        ))
    }
}
