package com.jin.bitperfect

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object PlaylistStore {
    private const val PREFS = "BitPerfectPrefs"
    private const val KEY = "playlists_json"

    fun load(context: Context): MutableList<Playlist> {
        val result = mutableListOf<Playlist>()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return result
        return try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val songs = mutableListOf<MusicItem>()
                val songArr = obj.optJSONArray("songs") ?: JSONArray()
                for (j in 0 until songArr.length()) {
                    val s = songArr.optJSONObject(j) ?: continue
                    val uriText = s.optString("uri", "")
                    if (uriText.isBlank()) continue
                    songs.add(MusicItem(
                        id = s.optLong("id", 0L),
                        title = s.optString("title", ""),
                        artist = s.optString("artist", ""),
                        album = s.optString("album", ""),
                        trackNumber = s.optInt("trackNum", 0),
                        duration = s.optLong("duration", 0L),
                        contentUri = Uri.parse(uriText),
                        albumArtUri = s.optString("art", "").takeIf { it.isNotEmpty() }?.let(Uri::parse),
                        format = s.optString("format", "")
                    ))
                }
                result.add(Playlist(obj.optLong("id", 0L), obj.optString("name", ""), songs))
            }
            result
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(context: Context, playlists: List<Playlist>) {
        val arr = JSONArray()
        playlists.forEach { pl ->
            val obj = JSONObject()
            obj.put("id", pl.id)
            obj.put("name", pl.name)
            val songArr = JSONArray()
            pl.songs.forEach { s ->
                val so = JSONObject()
                so.put("id", s.id)
                so.put("title", s.title)
                so.put("artist", s.artist)
                so.put("album", s.album)
                so.put("trackNum", s.trackNumber)
                so.put("duration", s.duration)
                so.put("uri", s.contentUri.toString())
                so.put("format", s.format)
                so.put("art", s.albumArtUri?.toString() ?: "")
                songArr.put(so)
            }
            obj.put("songs", songArr)
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }
}
