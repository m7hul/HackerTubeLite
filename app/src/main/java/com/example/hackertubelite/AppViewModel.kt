package com.example.hackertubelite

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlin.random.Random

enum class Sort(val label: String) {
    Top("Top rated"),
    Recent("Recently discussed"),
    Shuffle("Surprise me"),
}

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

@OptIn(ExperimentalSerializationApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val sp = app.getSharedPreferences("state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // ---- catalog (loaded once, in the background) ----
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set
    var videos by mutableStateOf<List<Video>>(emptyList())
        private set
    var byId by mutableStateOf<Map<String, Video>>(emptyMap())
        private set
    var areas by mutableStateOf<List<Area>>(emptyList())
        private set
    var related by mutableStateOf<Map<String, Related>>(emptyMap())
        private set
    var mentions by mutableStateOf<Map<String, List<Mention>>>(emptyMap())
        private set
    private var index: Map<String, SearchEntry> = emptyMap()

    // ---- settings + personal lists (stored on this device only) ----
    var themeMode by mutableStateOf(
        runCatching { ThemeMode.valueOf(sp.getString("theme", "System") ?: "System") }
            .getOrDefault(ThemeMode.System)
    )
        private set
    var hideNews by mutableStateOf(sp.getBoolean("hideNews", true))
        private set
    var includeCited by mutableStateOf(sp.getBoolean("includeCited", false))
        private set
    var liked by mutableStateOf(readList("liked"))
        private set
    var later by mutableStateOf(readList("later"))
        private set
    var history by mutableStateOf(readList("history"))
        private set

    init {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.Default) {
                    val vs = readAsset<List<Video>>("videos.json").filter { VIDEO_ID_REGEX.matches(it.id) }
                    val ar = readAsset<List<Area>>("taxonomy.json")
                    val idx = vs.associate { v ->
                        v.id to SearchEntry(
                            title = v.title.lowercase(),
                            meta = (v.channel + " " + v.topic + " " + v.tags.joinToString(" ")).lowercase(),
                            body = (v.summary + " " + v.why).lowercase(),
                        )
                    }
                    Triple(vs, ar, idx)
                }
                index = loaded.third
                byId = loaded.first.associateBy { it.id }
                areas = loaded.second
                videos = loaded.first
            } catch (e: Exception) {
                failed = true
            }
            loading = false

            // Second phase: extras that the detail page needs, loaded after the feed is on screen.
            try {
                related = withContext(Dispatchers.Default) { readAsset<Map<String, Related>>("related.json") }
            } catch (_: Exception) {
            }
            try {
                mentions = withContext(Dispatchers.Default) {
                    readAsset<Map<String, List<List<String>>>>("mentions.json")
                        .mapValues { (_, rows) ->
                            rows.filter { it.size >= 5 }
                                .map { Mention(it[0], it[1], it[2], it[3], it[4]) }
                        }
                }
            } catch (_: Exception) {
            }
        }
    }

    private inline fun <reified T> readAsset(name: String): T =
        getApplication<Application>().assets.open(name).use { json.decodeFromStream<T>(it) }

    // ---- filtering ----
    private fun visible(v: Video) = (!hideNews || v.news == 0) && (includeCited || v.recommenders > 0)

    fun feed(type: String?, sort: Sort, seed: Int): List<Video> {
        val base = videos.filter { visible(it) && (type == null || it.type == type) }
        return when (sort) {
            Sort.Top -> base
            Sort.Recent -> base.sortedWith(
                compareByDescending<Video> { it.lastYear }.thenByDescending { it.score }
            )
            Sort.Shuffle -> base.shuffled(Random(seed))
        }
    }

    fun search(query: String): List<Video> {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<Video, Int>>()
        for (v in videos) {
            if (!visible(v)) continue
            val h = index[v.id] ?: continue
            var total = 0
            var all = true
            for (t in tokens) {
                val s = when {
                    h.title.contains(t) -> 4
                    h.meta.contains(t) -> 3
                    h.body.contains(t) -> 1
                    else -> 0
                }
                if (s == 0) {
                    all = false
                    break
                }
                total += s
            }
            if (all) hits.add(v to total)
        }
        return hits
            .sortedWith(compareByDescending<Pair<Video, Int>> { it.second }.thenByDescending { it.first.score })
            .take(120)
            .map { it.first }
    }

    fun inCategory(id: String): List<Video> = videos.filter { visible(it) && id in it.cats }

    fun categoryCounts(): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (v in videos) {
            if (!visible(v)) continue
            for (c in v.cats) counts[c] = (counts[c] ?: 0) + 1
        }
        return counts
    }

    // ---- actions ----
    fun setTheme(m: ThemeMode) {
        themeMode = m
        sp.edit().putString("theme", m.name).apply()
    }

    @JvmName("applyHideNews")
    fun setHideNews(b: Boolean) {
        hideNews = b
        sp.edit().putBoolean("hideNews", b).apply()
    }

    @JvmName("applyIncludeCited")
    fun setIncludeCited(b: Boolean) {
        includeCited = b
        sp.edit().putBoolean("includeCited", b).apply()
    }

    fun toggleLiked(id: String) {
        liked = if (id in liked) liked - id else listOf(id) + liked
        saveList("liked", liked)
    }

    fun toggleLater(id: String) {
        later = if (id in later) later - id else listOf(id) + later
        saveList("later", later)
    }

    fun pushHistory(id: String) {
        history = (listOf(id) + history.filter { it != id }).take(300)
        saveList("history", history)
    }

    fun removeHistory(id: String) {
        history = history.filter { it != id }
        saveList("history", history)
    }

    fun clearHistory() {
        history = emptyList()
        saveList("history", history)
    }

    fun clearSaved() {
        liked = emptyList()
        later = emptyList()
        saveList("liked", liked)
        saveList("later", later)
    }

    private fun readList(key: String): List<String> =
        (sp.getString(key, "") ?: "").split(',').filter { it.isNotBlank() }

    private fun saveList(key: String, list: List<String>) {
        sp.edit().putString(key, list.joinToString(",")).apply()
    }
}