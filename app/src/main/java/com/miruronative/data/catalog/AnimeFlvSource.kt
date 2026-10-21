package com.miruronative.data.catalog

import android.util.Base64
import android.util.Log
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import com.miruronative.data.model.SubtitleItem
import java.net.URI
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONArray
import org.json.JSONObject

/**
 * AnimeFLV (animeflv.or.at) — Spanish catalog, independent from AniList.
 *
 * This is the WordPress mirror the user watches:
 * - Home: latest episode posts (`div.Episode`), whose permalink slug is `<series>-episodio-N`, so the
 *   series key is derived by stripping the episode suffix.
 * - Archive: `/anime/page/N/` lists series cards. It is small (~11 pages), so [search] builds a
 *   local index once and filters it; the site's own WordPress search does not index the series.
 * - Detail: `/anime/<slug>/` carries title, poster, synopsis and an `animeflv-episodes-data` JSON
 *   with `{number, permalink}` per episode.
 * - Playback: the episode post exposes base64 `data-src` embeds. Voe is the only one that yields a
 *   compatible stream: its page ships an obfuscated JSON (rot13 + token removal + base64 + shift +
 *   reverse + base64) with a signed HLS master. Verified as H.264 High 3.1, 720p, SDR.
 *   Zilla's segments are Cloudflare-blocked and MP4Upload serves AV1 10-bit, so both are skipped.
 */
internal class AnimeFlvSource : CatalogSource {
    override val id = "animeflv"
    override val label = "AnimeFLV"

    @Volatile private var index: List<Row>? = null
    @Volatile private var indexAt: Long = 0L

    private data class Row(val slug: String, val title: String, val poster: String?)

    override suspend fun home(page: Int): CatalogPage = withContext(Dispatchers.IO) {
        if (page <= 1) {
            parseLatest(doc("$BASE/")).takeIf { it.isNotEmpty() }
                ?.let { CatalogPage(it, hasNext = true) }
                ?: archive(page)
        } else {
            archive(page)
        }
    }

    override suspend fun search(query: String, page: Int): CatalogPage = withContext(Dispatchers.IO) {
        val all = index()
        val needle = normalize(query)
        val hits = if (needle.isBlank()) all else all.filter {
            normalize(it.title).contains(needle) || normalize(it.slug.replace('-', ' ')).contains(needle)
        }
        val from = ((page.coerceAtLeast(1) - 1) * PAGE_SIZE).coerceAtMost(hits.size)
        val to = (from + PAGE_SIZE).coerceAtMost(hits.size)
        CatalogPage(
            items = hits.subList(from, to).map { it.toItem() },
            hasNext = to < hits.size,
        )
    }

    override suspend fun detail(key: String): CatalogDetail = withContext(Dispatchers.IO) {
        val d = doc("$BASE/anime/$key/")
        val title = d.selectFirst("h1.anime-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: humanize(key)
        val poster = proxied(d.selectFirst("img.poster-image")?.absUrl("src"))
        val description = d.select("div.anime-synopsis p").map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
            ?: d.selectFirst("div.anime-synopsis")?.text()?.replaceFirst("Sinopsis", "")?.trim()
                ?.takeIf { it.isNotBlank() }
        val episodes = episodeData(d).map { it.first }.ifEmpty { listOf(1) }
        CatalogDetail(
            item = CatalogItem(
                source = id,
                key = key,
                title = title,
                poster = poster,
                description = description,
                episodes = episodes.size,
                format = "TV",
                year = null,
            ),
            episodes = episodes,
        )
    }

    override suspend fun sources(key: String, episode: Int, audio: String): SourcesResult =
        withContext(Dispatchers.IO) {
            val anime = doc("$BASE/anime/$key/")
            val permalink = episodeData(anime).firstOrNull { it.first == episode }?.second
                ?: error("AnimeFLV: no existe el episodio $episode")
            val post = doc(permalink)
            val embeds = EMBED_DATA.findAll(post.html()).mapNotNull { m ->
                runCatching {
                    String(Base64.decode(pad(m.groupValues[1]), Base64.DEFAULT), StandardCharsets.UTF_8)
                }.getOrNull()
            }.toList()

            val voe = embeds.firstOrNull { it.contains("voe.sx") || it.contains("derricknationtop") }
                ?: error("AnimeFLV: este episodio no tiene Voe (solo Zilla/MP4Upload, no compatibles)")
            resolveVoe(voe)
        }

    private fun parseLatest(d: Document): List<CatalogItem> {
        val seen = LinkedHashSet<String>()
        val items = mutableListOf<CatalogItem>()
        for (a in d.select("div.Episode a[href]")) {
            val href = a.absUrl("href")
            val postSlug = href.trimEnd('/').substringAfterLast('/')
            val m = EPISODE_POST.find(postSlug) ?: continue
            val series = m.groupValues[1]
            if (!seen.add(series)) continue
            val rawTitle = a.selectFirst("h2.Title")?.text()?.trim().orEmpty()
            val title = rawTitle.replace(EPISODE_SUFFIX, "").trim().ifBlank { humanize(series) }
            val poster = proxied(a.selectFirst("img")?.absUrl("src"))
            items += CatalogItem(id, series, title, poster)
        }
        return items
    }

    private fun archive(page: Int): CatalogPage {
        val d = doc("$BASE/anime/page/$page/")
        val items = mutableListOf<CatalogItem>()
        for (a in d.select("a.thumbnail-link[href]")) {
            val href = a.absUrl("href")
            if (!href.contains("/anime/")) continue
            val slug = href.trimEnd('/').substringAfterLast('/')
            val title = a.selectFirst("h2.entry-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: humanize(slug)
            val poster = proxied((a.selectFirst("img.anime-image") ?: a.selectFirst("img"))?.absUrl("src"))
            items += CatalogItem(id, slug, title, poster)
        }
        val hasNext = d.select("a.page-numbers, a.next").any { it.attr("href").contains("/anime/page/${page + 1}/") }
        return CatalogPage(items, hasNext)
    }

    private suspend fun index(): List<Row> = coroutineScope {
        val cached = index
        if (cached != null && System.currentTimeMillis() - indexAt < INDEX_TTL_MS) {
            return@coroutineScope cached
        }
        loadIndexFromDisk()?.let {
            index = it
            indexAt = System.currentTimeMillis()
            Log.d(TAG, "index loaded from disk: ${it.size} series")
            return@coroutineScope it
        }
        val first = withContext(Dispatchers.IO) { fetchArchive(1) }
        val maxPage = first.second ?: 1
        val gate = Semaphore(5)
        val rest = (2..maxPage).map { p ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching { fetchArchive(p).first }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten()
        val rows = (first.first + rest).distinctBy { it.slug }
        index = rows
        indexAt = System.currentTimeMillis()
        saveIndexToDisk(rows)
        Log.d(TAG, "index built: ${rows.size} series in $maxPage pages")
        rows
    }

    private fun loadIndexFromDisk(): List<Row>? {
        if (CatalogCache.ageMs(INDEX_FILE) > INDEX_DISK_TTL_MS) return null
        val raw = CatalogCache.read(INDEX_FILE) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val row = arr.optJSONArray(i) ?: return@mapNotNull null
                val slug = row.optString(0)
                val title = row.optString(1)
                if (slug.isBlank() || title.isBlank()) return@mapNotNull null
                Row(slug, title, row.optString(2).takeIf { it.isNotBlank() })
            }.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun saveIndexToDisk(rows: List<Row>) {
        val arr = JSONArray()
        for (row in rows) {
            arr.put(JSONArray().put(row.slug).put(row.title).put(row.poster ?: ""))
        }
        CatalogCache.write(INDEX_FILE, arr.toString())
    }

    private fun fetchArchive(page: Int): Pair<List<Row>, Int?> {
        val d = doc("$BASE/anime/page/$page/")
        val rows = mutableListOf<Row>()
        for (a in d.select("a.thumbnail-link[href]")) {
            val href = a.absUrl("href")
            if (!href.contains("/anime/")) continue
            val slug = href.trimEnd('/').substringAfterLast('/')
            val title = a.selectFirst("h2.entry-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: continue
            val poster = proxied((a.selectFirst("img.anime-image") ?: a.selectFirst("img"))?.absUrl("src"))
            rows += Row(slug, title, poster)
        }
        val maxPage = d.select("a.page-numbers, a.next")
            .mapNotNull { pageNumber(it.attr("href")) }
            .maxOrNull()
        return rows to maxPage
    }

    private fun episodeData(d: Document): List<Pair<Int, String>> {
        val raw = d.selectFirst("script.animeflv-episodes-data")?.data() ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val number = o.optInt("number")
                val link = o.optString("permalink")
                if (number > 0 && link.isNotBlank()) number to link else null
            }.sortedBy { it.first }
        }.getOrDefault(emptyList())
    }

    /** Voe's player: rotate, drop tokens, base64, shift -3, reverse, base64, JSON. */
    private fun resolveVoe(embed: String): SourcesResult {
        var pageUrl = embed
        var html = get(pageUrl)
        REDIRECT.find(html)?.groupValues?.get(1)?.let { next ->
            pageUrl = next
            html = get(pageUrl)
        }
        val doc = Jsoup.parse(html, pageUrl)
        for (script in doc.select("script[type=application/json]")) {
            val json = decodeVoe(script.data()) ?: continue
            val source = json.optString("source")
            if (!source.startsWith("http")) continue
            val subtitles = runCatching {
                val caps = json.optJSONArray("captions") ?: return@runCatching emptyList()
                (0 until caps.length()).mapNotNull { i ->
                    val c = caps.optJSONObject(i) ?: return@mapNotNull null
                    val file = c.optString("file")
                    if (file.isBlank()) null else SubtitleItem(
                        file,
                        c.optString("label", c.optString("language", "sub")),
                        c.optString("language", "und"),
                    )
                }
            }.getOrDefault(emptyList())
            val origin = origin(pageUrl)
            Log.d(TAG, "Voe stream: $source")
            return SourcesResult(
                streams = listOf(
                    StreamItem(
                        url = source,
                        type = "hls",
                        quality = "AnimeFLV Voe",
                        audio = "sub",
                        referer = "$origin/",
                        isActive = true,
                        width = null,
                        height = null,
                    ),
                ),
                subtitles = subtitles,
                skip = null,
                download = null,
            )
        }
        error("Voe: no se pudo decodificar el reproductor")
    }

    private fun decodeVoe(raw: String): JSONObject? = runCatching {
        var value = rotate(raw)
        for (token in VOID_TOKENS) value = value.replace(token, "")
        value = value.replace("_", "")
        val first = String(Base64.decode(pad(value), Base64.DEFAULT), StandardCharsets.UTF_8)
        val shifted = buildString(first.length) {
            for (ch in first) append((ch.code - 3).toChar())
        }
        val reversed = shifted.reversed()
        val second = String(Base64.decode(pad(reversed), Base64.DEFAULT), StandardCharsets.UTF_8)
        JSONObject(second)
    }.getOrNull()

    private fun rotate(value: String): String = buildString(value.length) {
        for (ch in value) {
            append(
                when (ch) {
                    in 'a'..'z' -> 'a' + (ch - 'a' + 13) % 26
                    in 'A'..'Z' -> 'A' + (ch - 'A' + 13) % 26
                    else -> ch
                },
            )
        }
    }

    private fun pad(value: String): String =
        value + "=".repeat((4 - value.length % 4) % 4)

    private fun Row.toItem() = CatalogItem(id, slug, title, poster)

    /**
     * AnimeFLV answers 403 to its own images when the Referer is not the site. The WebView's
     * internal `/__proxy/` handler fetches without a Referer, so route posters through it.
     */
    private fun proxied(url: String?): String? = url
        ?.takeIf { it.isNotBlank() }
        ?.let { if (it.startsWith("http")) "/__proxy/$it" else it }

    private fun pageNumber(href: String): Int? {
        val m = PAGE_NUMBER.find(href) ?: return null
        return m.groupValues.getOrNull(1)?.toIntOrNull()
            ?: m.groupValues.getOrNull(2)?.toIntOrNull()
    }

    private fun get(url: String): String {
        val request = okhttp3.Request.Builder().url(url)
            .header("User-Agent", CatalogHttp.DESKTOP_UA)
            .header("Referer", "$BASE/")
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .get().build()
        CatalogHttp.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("AnimeFLV HTTP ${response.code} $url")
            return body
        }
    }

    private fun doc(url: String): Document {
        var last: Exception? = null
        repeat(2) {
            try {
                return Jsoup.connect(url)
                    .userAgent(CatalogHttp.DESKTOP_UA)
                    .referrer("$BASE/")
                    .timeout(12_000)
                    .get()
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("AnimeFLV: no se pudo cargar $url")
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .trim()

    private fun humanize(slug: String): String =
        slug.replace('-', ' ').replaceFirstChar { it.uppercase() }

    private fun origin(url: String): String = runCatching {
        URI(url).let { "${it.scheme}://${it.authority}" }
    }.getOrDefault(BASE)

    companion object {
        private const val TAG = "ATVLOG-ANIMEFLV"
        private const val BASE = "https://animeflv.or.at"
        private const val PAGE_SIZE = 20
        private const val INDEX_TTL_MS = 30 * 60 * 1000L
        private const val INDEX_DISK_TTL_MS = 24 * 60 * 60 * 1000L
        private const val INDEX_FILE = "animeflv-index.json"
        private val EPISODE_POST = Regex("(.+)-episodio-(\\d+)$", RegexOption.IGNORE_CASE)
        private val EPISODE_SUFFIX = Regex("\\s+episodio\\s+\\d+\\s*$", RegexOption.IGNORE_CASE)
        private val EMBED_DATA = Regex("data-src=\"([A-Za-z0-9+/=]+)\"")
        private val REDIRECT = Regex("window\\.location\\.href\\s*=\\s*['\"]([^'\"]+)['\"]")
        private val PAGE_NUMBER = Regex("\\?paged?=(\\d+)|/page/(\\d+)/", RegexOption.IGNORE_CASE)
        private val VOID_TOKENS = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
    }
}
