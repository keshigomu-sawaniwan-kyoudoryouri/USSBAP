package com.jin.bitperfect

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** Small on-device preference model. No account data leaves the phone. */
object SearchRecommendationStore {
    private const val PREFS = "BitPerfectPrefs"
    private const val KEY = "search_recommendation_model_v1"
    private const val MAX_TERMS = 80

    private data class Model(
        val queries: MutableMap<String, Double> = mutableMapOf(),
        val artists: MutableMap<String, Double> = mutableMapOf(),
        val titles: MutableMap<String, Double> = mutableMapOf(),
        val disliked: MutableMap<String, Double> = mutableMapOf()
    )

    fun recordSearch(context: Context, query: String) = update(context) {
        val value = normalize(query)
        if (!value.startsWith("http")) bump(it.queries, value, 1.0)
    }
    fun recordPlay(context: Context, title: String, artist: String) = update(context) {
        bump(it.titles, normalize(title), 2.0); bump(it.artists, normalizeArtist(artist), 3.0)
    }
    fun recordQueue(context: Context, title: String, artist: String) = update(context) {
        bump(it.titles, normalize(title), 1.2); bump(it.artists, normalizeArtist(artist), 1.5)
    }
    fun recordSave(context: Context, title: String, artist: String) = update(context) {
        bump(it.titles, normalize(title), 2.5); bump(it.artists, normalizeArtist(artist), 4.0)
    }
    fun recordCompletion(context:Context,title:String,artist:String,ratio:Float)=update(context){if(ratio>=.75f){bump(it.titles,normalize(title),1.8);bump(it.artists,normalizeArtist(artist),1.2)}else if(ratio<.12f){bump(it.titles,normalize(title),-.8)}}
    fun dislike(context:Context,url:String,title:String){update(context){it.disliked[url]=10.0;it.titles.remove(normalize(title))}}
    fun isDisliked(context:Context,url:String)=load(context).disliked.containsKey(url)

    fun recommendationQuery(context: Context): String? {
        val model = load(context)
        val artist = model.artists.maxByOrNull { it.value }?.key.orEmpty()
        val query = model.queries.maxByOrNull { it.value }?.key.orEmpty()
        val title = model.titles.maxByOrNull { it.value }?.key.orEmpty()
        return when { artist.isNotBlank() -> "$artist music"; query.isNotBlank() -> query; title.isNotBlank() -> "$title related music"; else -> null }
    }

    private fun update(context: Context, block: (Model) -> Unit) {
        val model = load(context)
        decay(model.queries); decay(model.artists); decay(model.titles)
        block(model)
        trim(model.queries); trim(model.artists); trim(model.titles);trim(model.disliked)
        save(context, model)
    }
    private fun bump(map: MutableMap<String, Double>, key: String, amount: Double) { if (key.length > 1) map[key] = (map[key] ?: 0.0) + amount }
    private fun decay(map: MutableMap<String, Double>) { map.keys.toList().forEach { map[it] = (map[it] ?: 0.0) * 0.997 } }
    private fun trim(map: MutableMap<String, Double>) { map.entries.sortedByDescending { it.value }.drop(MAX_TERMS).forEach { map.remove(it.key) } }
    private fun normalize(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT).take(100)
    private fun normalizeArtist(value: String) = normalize(value).replace(Regex("(?i)\\s*[-/]?\\s*(official|topic|vevo|channel)$"), "").trim()

    private fun load(context: Context): Model {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return Model()
        return try { val root = JSONObject(raw); Model(readMap(root.optJSONObject("queries")), readMap(root.optJSONObject("artists")), readMap(root.optJSONObject("titles")),readMap(root.optJSONObject("disliked"))) } catch (_: Exception) { Model() }
    }
    private fun readMap(json: JSONObject?): MutableMap<String, Double> {
        val result = mutableMapOf<String, Double>(); if (json == null) return result
        json.keys().forEach { result[it] = json.optDouble(it, 0.0) }; return result
    }
    private fun save(context: Context, model: Model) {
        fun json(map: Map<String, Double>) = JSONObject().apply { map.forEach { (key, value) -> put(key, value) } }
        val root = JSONObject().apply { put("queries", json(model.queries)); put("artists", json(model.artists)); put("titles", json(model.titles));put("disliked",json(model.disliked)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, root.toString()).apply()
    }
}
