package com.miruronative.data.catalog

import android.util.Log
import com.miruronative.data.model.SourcesResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON facade used by the WebView. It deliberately mimics the AniList shapes the existing Home and
 * detail screens already consume, so a new source only has to implement [CatalogSource].
 */
object CatalogBridge {

    fun list(): String {
        val out = JSONArray()
        for (source in CatalogRegistry.all()) {
            out.put(JSONObject().put("id", source.id).put("label", source.label))
        }
        return out.toString()
    }

    fun home(source: String, page: Int): String = runBlocking {
        val catalog = CatalogRegistry.get(source)
            ?: return@runBlocking emptyPage()
        val cached = pageCache.get(source, page)
        if (cached != null) {
            Log.d(TAG, "home $source p=$page (cache)")
            return@runBlocking cached
        }
        runCatching {
            val result = catalog.home(page.coerceAtLeast(1))
            Log.d(TAG, "home $source p=$page -> ${result.items.size} items")
            pageJson(result).also { pageCache.put(source, page, it) }
        }.getOrElse {
            Log.e(TAG, "home $source failed: ${it.message}", it)
            emptyPage()
        }
    }

    fun search(source: String, query: String, page: Int): String = runBlocking {
        val catalog = CatalogRegistry.get(source)
            ?: return@runBlocking emptyPage()
        runCatching {
            val result = catalog.search(query, page.coerceAtLeast(1))
            Log.d(TAG, "search $source '$query' p=$page -> ${result.items.size} items")
            pageJson(result)
        }.getOrElse {
            Log.e(TAG, "search $source failed: ${it.message}", it)
            emptyPage()
        }
    }

    fun view(source: String, key: String): String = runBlocking {
        val catalog = CatalogRegistry.get(source)
            ?: return@runBlocking errorJson("Fuente desconocida: $source")
        runCatching {
            val detail = catalog.detail(key)
            viewJson(catalog, detail)
        }.getOrElse { errorJson(it.message ?: "No se pudo cargar la ficha") }
    }

    fun video(source: String, key: String, episode: Int, audio: String): String = runBlocking {
        val catalog = CatalogRegistry.get(source)
            ?: return@runBlocking errorJson("Fuente desconocida: $source")
        runCatching {
            val result = catalog.sources(key, episode, audio)
            Log.d(TAG, "video $source $key ep=$episode -> ${result.streams.size} streams")
            sourceJson(result)
        }.getOrElse {
            Log.e(TAG, "video $source $key ep=$episode failed: ${it.message}", it)
            errorJson(it.message ?: "No hay fuentes para este episodio")
        }
    }

    private fun pageJson(page: CatalogPage): String {
        val media = JSONArray()
        for (item in page.items) {
            media.put(
                JSONObject()
                    .put("id", CatalogRegistry.idFor(item.source, item.key))
                    .put("title", JSONObject().put("romaji", item.title).put("english", item.title))
                    .put("key", item.key)
                    .put("coverImage", JSONObject().put("large", item.poster ?: "").put("medium", item.poster ?: ""))
                    .put("bannerImage", item.banner ?: item.poster ?: "")
                    .put("description", item.description ?: "")
                    .put("format", item.format ?: "TV")
                    .put("episodes", item.episodes ?: 0)
                    .put("seasonYear", item.year ?: 0)
                    .put("status", "RELEASING")
                    .put("isAdult", false)
                    .put("averageScore", 0)
                    .put("nextAiringEpisode", JSONObject.NULL)
            )
        }
        return JSONObject()
            .put("data", JSONObject().put("Page", JSONObject()
                .put("pageInfo", JSONObject().put("hasNextPage", page.hasNext))
                .put("media", media)))
            .toString()
    }

    private fun viewJson(catalog: CatalogSource, detail: CatalogDetail): String {
        val item = detail.item
        val episodes = JSONArray()
        for (ep in detail.episodes) {
            episodes.put(JSONObject()
                .put("ep", ep)
                .put("url", "${item.key}#$ep")
                .put("title", "Episodio $ep")
                .put("filler", false)
                .put("active", ep == detail.episodes.first())
                .put("dub", false)
                .put("epuri", "${item.key}#$ep"))
        }
        val servers = JSONArray().put(JSONArray().put(catalog.label).put(0))
        return JSONObject()
            .put("status", true)
            .put("title", item.title)
            .put("title_jp", item.title)
            .put("synopsis", item.description ?: "")
            .put("poster", item.poster ?: "")
            .put("banner", item.banner ?: item.poster ?: "")
            .put("url", item.key)
            .put("episodes", episodes)
            .put("epavail", detail.episodes.size)
            .put("epdub", 0)
            .put("genres", "[]")
            .put("type", item.format ?: "TV")
            .put("rating", "0")
            .put("status_text", "RELEASING")
            .put("streamtype", "sub")
            .put("related", JSONArray())
            .put("seasons", JSONArray())
            .put("recs", JSONArray())
            .put("info", JSONObject().put("type", "").put("rating", "").put("quality", ""))
            .put("stream_url", JSONObject().put("hard", "").put("soft", item.key).put("dub", ""))
            .put("stream_vurl", "")
            .put("skip", JSONArray().put(JSONArray().put(0).put(0)).put(JSONArray().put(0).put(0)))
            .put("servers", JSONObject().put("sub", servers).put("dub", JSONArray()).put("softsub", JSONArray()))
            .toString()
    }

    private fun sourceJson(result: SourcesResult): String {
        val sources = JSONArray()
        for (stream in result.streams) {
            sources.put(JSONObject()
                .put("file", stream.url)
                .put("quality", stream.quality ?: "auto")
                .put("type", stream.type)
                .put("referer", stream.referer ?: ""))
        }
        val tracks = JSONArray()
        for (sub in result.subtitles) {
            tracks.put(JSONObject()
                .put("file", sub.url)
                .put("label", sub.label)
                .put("lang", sub.language)
                .put("kind", "captions"))
        }
        return JSONObject()
            .put("sources", sources)
            .put("subtitles", tracks)
            .put("skip", JSONArray()
                .put(JSONArray().put(result.skip?.introStart ?: 0).put(result.skip?.introEnd ?: 0))
                .put(JSONArray().put(result.skip?.outroStart ?: 0).put(result.skip?.outroEnd ?: 0)))
            .toString()
    }

    private fun emptyPage(): String =
        """{"data":{"Page":{"pageInfo":{"hasNextPage":false},"media":[]}}}"""

    private fun errorJson(message: String): String =
        JSONObject().put("status", false).put("error", message).toString()

    private const val TAG = "ATVLOG-CATALOG"

    /**
     * The Home screen asks for page 1 twice (banner + row) and reloads it on every source switch.
     * Scraping twice is wasteful on a 1.4 GB TV, so keep the rendered JSON for a short while.
     */
    private object pageCache {
        private const val TTL_MS = 120_000L
        private val entries = HashMap<String, Pair<Long, String>>()

        fun get(source: String, page: Int): String? = synchronized(this) {
            val key = "$source|$page"
            val entry = entries[key] ?: return null
            if (System.currentTimeMillis() - entry.first > TTL_MS) {
                entries.remove(key)
                null
            } else {
                entry.second
            }
        }

        fun put(source: String, page: Int, json: String) {
            synchronized(this) {
                entries["$source|$page"] = System.currentTimeMillis() to json
            }
        }
    }
}
