package com.miruronative.data.catalog

import android.util.Base64
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import com.miruronative.data.model.SubtitleItem
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject

/**
 * Hentaila (hentaila.tv) — WordPress catalog in Spanish.
 *
 * Structure: home and `?s=` search list series at `/ver/<slug>/`; the series page lists
 * `/ver/<slug>/episodio-N/`.
 *
 * Playback is resolved over plain HTTP (no WebView):
 * 1. The episode page embeds `player-logic/player.php?data=<token>&lang=es`.
 * 2. That player carries an `x-secure-token` meta. The token is rot13 + base64 applied three
 *    times; decoding it yields the config with the `en`/`iv` pair and the plugin `uri`.
 * 3. POSTing `action=zarat_get_data_player_ajax` to `api.php` with `a=en`, `b=iv` returns the
 *    signed HLS master playlist (H.264 8-bit, compatible with the MT5867) and its subtitle set.
 *
 * If the site ever changes this handshake, this is the only place that needs updating.
 */
internal class HentailaSource : CatalogSource {
    override val id = "hentaila"
    override val label = "Hentaila"

    override suspend fun home(page: Int): CatalogPage = withContext(Dispatchers.IO) {
        val path = if (page <= 1) "$BASE/" else "$BASE/page/$page/"
        parseList(doc(path), page)
    }

    override suspend fun search(query: String, page: Int): CatalogPage = withContext(Dispatchers.IO) {
        val base = if (page <= 1) "$BASE/" else "$BASE/page/$page/"
        parseList(doc("$base?s=${encode(query)}"), page)
    }

    override suspend fun detail(key: String): CatalogDetail = withContext(Dispatchers.IO) {
        val d = doc("$BASE/ver/$key/")
        val title = d.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: humanize(key)
        val poster = d.selectFirst("meta[property=og:image]")?.attr("content")
            ?: d.selectFirst("img")?.absUrl("src")
        val description = d.selectFirst("meta[name=description]")?.attr("content")
        val episodes = d.select("a[href*=/episodio-]").mapNotNull { a ->
            EPISODE.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.toSortedSet().toList().ifEmpty { listOf(1) }
        CatalogDetail(
            item = CatalogItem(
                source = id,
                key = key,
                title = title,
                poster = poster,
                description = description,
                episodes = episodes.size,
            ),
            episodes = episodes,
        )
    }

    override suspend fun sources(key: String, episode: Int, audio: String): SourcesResult =
        withContext(Dispatchers.IO) {
            val episodeUrl = "$BASE/ver/$key/episodio-$episode/"
            val episodePage = CatalogHttp.get(episodeUrl, referer = "$BASE/")
            val data = PLAYER_DATA.find(episodePage)?.groupValues?.get(1)
                ?: error("Hentaila: el episodio no tiene reproductor")

            val playerUrl = "$BASE/wp-content/plugins/player-logic/player.php?data=$data&lang=es"
            val playerPage = CatalogHttp.get(playerUrl, referer = episodeUrl)
            val token = SECURE_TOKEN.find(playerPage)?.groupValues?.get(1)
                ?: error("Hentaila: falta x-secure-token")

            val config = decodeToken(token) ?: error("Hentaila: token ilegible")
            val en = config.optString("en")
            val iv = config.optString("iv")
            val uri = config.optString("uri").ifBlank {
                "/wp-content/plugins/player-logic/"
            }
            val apiUrl = apiUrl(uri)

            val payload = CatalogHttp.postForm(
                apiUrl,
                mapOf(
                    "action" to "zarat_get_data_player_ajax",
                    "a" to en,
                    "b" to iv,
                ),
                referer = episodeUrl,
                origin = BASE,
            )
            val json = CatalogHttp.json(payload)
            val source = json.optJSONObject("data")
                ?.optJSONArray("sources")
                ?.optJSONObject(0)
                ?.optString("src")
                ?.takeIf { it.isNotBlank() }
                ?: error("Hentaila: sin fuentes de vídeo")

            val subtitles = runCatching { subtitles(source) }.getOrDefault(emptyList())
            SourcesResult(
                streams = listOf(
                    StreamItem(
                        url = source,
                        type = "hls",
                        quality = "Hentaila auto",
                        audio = "sub",
                        referer = "$BASE/",
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

    /** Rot13 + base64, three times, as the player script does. */
    private fun decodeToken(raw: String): JSONObject? = runCatching {
        var value = raw.removePrefix("sha512-")
        repeat(3) {
            value = rotate(value)
            value = String(
                Base64.decode(value, Base64.DEFAULT),
                StandardCharsets.UTF_8,
            )
        }
        JSONObject(value)
    }.getOrNull()

    private fun rotate(value: String): String {
        val out = StringBuilder(value.length)
        for (ch in value) {
            out.append(
                when (ch) {
                    in 'a'..'z' -> 'a' + (ch - 'a' + 13) % 26
                    in 'A'..'Z' -> 'A' + (ch - 'A' + 13) % 26
                    else -> ch
                },
            )
        }
        return out.toString()
    }

    private fun apiUrl(uri: String): String = when {
        uri.startsWith("http://") || uri.startsWith("https://") -> uri
        uri.startsWith("//") -> "https:$uri"
        uri.startsWith("/") -> "$BASE$uri"
        else -> "$BASE/$uri"
    }.let { if (it.endsWith("/")) "${it}api.php" else "$it/api.php" }

    /** Side-load the Spanish track from the HLS master, so the app's own subtitle overlay uses it. */
    private fun subtitles(masterUrl: String): List<SubtitleItem> {
        val manifest = CatalogHttp.get(masterUrl, referer = "$BASE/")
        val base = URI(masterUrl)
        val tracks = mutableListOf<SubtitleItem>()
        for (line in manifest.lineSequence()) {
            if (!line.startsWith("#EXT-X-MEDIA:") || !line.contains("TYPE=SUBTITLES")) continue
            val uri = ATTR_URI.find(line)?.groupValues?.get(1) ?: continue
            val lang = ATTR_LANG.find(line)?.groupValues?.get(1)?.lowercase() ?: "und"
            val name = ATTR_NAME.find(line)?.groupValues?.get(1) ?: lang
            val absolute = runCatching { base.resolve(uri).toString() }.getOrDefault(uri)
            tracks += SubtitleItem(
                url = absolute,
                label = if (lang == "es") "spanish" else name.lowercase(),
                language = lang,
            )
        }
        // Spanish first: this build prioritises Spanish for the home audience.
        return tracks.sortedBy { if (it.language.startsWith("es")) 0 else 1 }
            .distinctBy { it.language }
    }

    private fun parseList(d: Document, page: Int): CatalogPage {
        val seen = LinkedHashSet<String>()
        val items = mutableListOf<CatalogItem>()
        for (a in d.select("a[href]")) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            val match = SERIES.find(href) ?: continue
            val slug = match.groupValues[1]
            if (!seen.add(slug)) continue
            val img = a.selectFirst("img")
            val title = a.selectFirst("h3, h2, .title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: humanize(slug)
            val poster = img?.let { it.absUrl("src").ifBlank { it.attr("data-src") } }
                ?.takeIf { it.isNotBlank() }
            items += CatalogItem(id, slug, title, poster)
        }
        val hasNext = d.selectFirst("a[href*=/page/${page + 1}/]") != null
        return CatalogPage(items, hasNext)
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
        throw last ?: IllegalStateException("Hentaila: no se pudo cargar $url")
    }

    private fun humanize(slug: String): String =
        slug.replace('-', ' ').replaceFirstChar { it.uppercase() }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    companion object {
        private const val BASE = "https://hentaila.tv"
        private val SERIES = Regex("^https?://hentaila\\.tv/ver/([a-z0-9-]+)/$", RegexOption.IGNORE_CASE)
        private val EPISODE = Regex("/episodio-(\\d+)/?", RegexOption.IGNORE_CASE)
        private val PLAYER_DATA = Regex("player\\.php\\?data=([A-Za-z0-9+/=%]+)")
        private val SECURE_TOKEN = Regex("name=\"x-secure-token\"\\s+content=\"([^\"]+)\"")
        private val ATTR_URI = Regex("URI=\"([^\"]+)\"")
        private val ATTR_LANG = Regex("LANGUAGE=\"([^\"]+)\"")
        private val ATTR_NAME = Regex("NAME=\"([^\"]+)\"")
    }
}
