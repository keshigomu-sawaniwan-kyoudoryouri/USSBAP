package com.jin.bitperfect

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object StreamHistoryStore {
    private const val PREFS = "BitPerfectPrefs"
    private const val KEY = "stream_history_json"
    private const val MAX_HISTORY = 50

    data class Entry(val url: String, val title: String, val uploader: String, val thumbnailUrl: String = "", val sharedAt: Long = 0L)

    fun load(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url", "")
                if (url.isBlank()) continue
                result.add(Entry(
                    url,
                    obj.optString("title", ""),
                    obj.optString("uploader", ""),
                    obj.optString("thumbnailUrl", ""),
                    obj.optLong("sharedAt", 0L)
                ))
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, entry: Entry) {
        val list = load(context).toMutableList()
        val existing = list.find { it.url == entry.url }
        if (existing != null) {
            // Update sharedAt and move to top
            list.remove(existing)
            list.add(0, existing.copy(sharedAt = System.currentTimeMillis()))
        } else {
            list.add(0, entry.copy(sharedAt = System.currentTimeMillis()))
        }
        while (list.size > MAX_HISTORY) list.removeAt(list.lastIndex)
        save(context, list)
    }

    fun remove(context: Context, url: String) {
        val list = load(context).toMutableList()
        list.removeAll { it.url == url }
        save(context, list)
    }

    private fun save(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("url", e.url)
                    put("title", e.title)
                    put("uploader", e.uploader)
                    put("thumbnailUrl", e.thumbnailUrl)
                    put("sharedAt", e.sharedAt)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }
}
