package com.jin.bitperfect

import android.net.Uri

data class YtParsedUrl(
    val type: Type,
    val videoId: String,
    val playlistId: String = "",
    val browseId: String = ""
) {
    enum class Type { WATCH, PLAYLIST, MIX, BROWSE, UNKNOWN }
}

object YtUrlParser {
    private fun mixSeed(mixId: String): String {
        val suffix = when {
            mixId.startsWith("RDAMVM") -> mixId.removePrefix("RDAMVM")
            mixId.startsWith("RD") -> mixId.removePrefix("RD")
            else -> ""
        }
        return if (suffix.length == 11 && suffix.matches(Regex("[a-zA-Z0-9_-]{11}"))) suffix else ""
    }

    fun parse(url: String): YtParsedUrl {
        val cleaned = Regex("""https?://\S+""").find(url)?.value ?: url.trim()
        val uri = Uri.parse(cleaned)
        val path = uri.path ?: ""

        // watch?v=
        if (path.contains("/watch") || cleaned.contains("youtu.be/")) {
            val videoId = uri.getQueryParameter("v")
                ?: uri.lastPathSegment
                ?: Regex("[a-zA-Z0-9_-]{11}").find(cleaned)?.value ?: ""
            if (videoId.isNotEmpty()) {
                return YtParsedUrl(YtParsedUrl.Type.WATCH, videoId, uri.getQueryParameter("list").orEmpty())
            }
        }

        // playlist?list=
        if (path.contains("/playlist")) {
            val playlistId = uri.getQueryParameter("list") ?: ""
            if (playlistId.isNotEmpty()) {
                if (playlistId.startsWith("RD")) {
                    val seed = mixSeed(playlistId)
                    return YtParsedUrl(YtParsedUrl.Type.MIX, seed, playlistId)
                }
                return YtParsedUrl(YtParsedUrl.Type.PLAYLIST, "", playlistId)
            }
        }

        val listId = uri.getQueryParameter("list").orEmpty()
        if (listId.isNotEmpty() && !path.contains("/watch")) {
            return YtParsedUrl(YtParsedUrl.Type.PLAYLIST, "", listId)
        }

        // browse/
        if (path.contains("/browse")) {
            val browseId = uri.lastPathSegment ?: ""
            // also check channel/ prefix
            val altBrowseId = if (path.contains("/channel/")) {
                path.substringAfterLast("/channel/")
            } else if (path.contains("/c/")) {
                path.substringAfterLast("/c/")
            } else {
                browseId
            }
            if (altBrowseId.isNotEmpty()) {
                return YtParsedUrl(YtParsedUrl.Type.BROWSE, "", "", altBrowseId)
            }
        }

        // try to extract videoId from any path
        val fallbackVideoId = Regex("[a-zA-Z0-9_-]{11}").find(cleaned)?.value ?: ""
        if (fallbackVideoId.length == 11) {
            return YtParsedUrl(YtParsedUrl.Type.WATCH, fallbackVideoId)
        }

        return YtParsedUrl(YtParsedUrl.Type.UNKNOWN, "")
    }
}
