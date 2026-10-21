package com.miruronative.data.catalog

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import com.miruronative.data.model.SubtitleItem
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONArray
import org.json.JSONObject

/**
 * VeoHentai (veohentai.com) — Spanish catalog. It has no series page: the home and the search list
 * individual episodes at `/ver/<slug>-episodio-N/`. We group those links by `<slug>` so the UI
 * still sees one card per series, and [detail] rebuilds the episode list from the search page.
 *
 * Playback is resolved over plain HTTP (no WebView), replicating the player handshake:
 * 1. The episode page embeds `hentaiplayer.com/v/<id>/`.
 * 2. That page exposes a `/player.php?vid=<b64>&s=<b64>&i=<b64>` server. `vid` is the file URL
 *    plus expiry/signature, `s` is the Spanish subtitle file.
 * 3. `player.php` ships an anti-bot challenge: `player-core-v2.php` carries a signed `c` token and
 *    a proof-of-work (SHA-256 with two leading zero bytes over four rotating page fragments).
 *    `get-video-url-v2.php` exchanges the solved challenge for the final signed MP4 URL.
 *
 * Every random id in that handshake is scoped to one page load, so all of it is parsed per call.
 * If the site changes the challenge, only this file needs updating.
 */
internal class VeoHentaiSource : CatalogSource {
    override val id = "veohentai"
    override val label = "VeoHentai"

    override suspend fun home(page: Int): CatalogPage = withContext(Dispatchers.IO) {
        val path = if (page <= 1) "$BASE/" else "$BASE/page/$page/"
        parseGrouped(doc(path), page)
    }

    override suspend fun search(query: String, page: Int): CatalogPage = withContext(Dispatchers.IO) {
        val suffix = if (page > 1) "&page=$page" else ""
        parseGrouped(doc("$BASE/buscar?q=${encode(query)}$suffix"), page)
    }

    override suspend fun detail(key: String): CatalogDetail = withContext(Dispatchers.IO) {
        val d = doc("$BASE/buscar?q=${encode(key.replace('-', ' '))}")
        val episodes = d.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            val m = EPISODE.find(href) ?: return@mapNotNull null
            if (!m.groupValues[1].equals(key, ignoreCase = true)) return@mapNotNull null
            m.groupValues[2].toIntOrNull()
        }.toSortedSet().toList()
        if (episodes.isEmpty()) error("VeoHentai: no se encontraron episodios para $key")
        val firstCard = d.selectFirst("a[href*=-episodio-]")
        val poster = firstCard?.selectFirst("img")
            ?.let { it.absUrl("src").ifBlank { it.attr("data-src") } }
            ?.takeIf { it.isNotBlank() }
        CatalogDetail(
            item = CatalogItem(
                source = id,
                key = key,
                title = humanize(key),
                poster = poster,
                episodes = episodes.size,
            ),
            episodes = episodes,
        )
    }

    override suspend fun sources(key: String, episode: Int, audio: String): SourcesResult =
        withContext(Dispatchers.IO) {
            val episodeUrl = "$BASE/ver/$key-episodio-$episode/"
            val episodePage = CatalogHttp.get(episodeUrl, referer = "$BASE/")
            val embedId = EMBED.find(episodePage)?.groupValues?.get(1)
                ?: error("VeoHentai: el episodio no tiene reproductor")
            val embedUrl = "$PLAYER_HOST/v/$embedId/"
            val embedPage = CatalogHttp.get(embedUrl, referer = episodeUrl)

            val dataId = DATA_ID.find(embedPage)?.groupValues?.get(1)
                ?.replace("&amp;", "&")
                ?: error("VeoHentai: falta el reproductor de $PLAYER_HOST")
            val playerUrl = PLAYER_HOST + dataId
            val playerPage = CatalogHttp.get(playerUrl, referer = embedUrl)

            val streamUrl = resolveSignedUrl(playerPage, playerUrl)
            val subtitle = subtitleFrom(dataId)

            SourcesResult(
                streams = listOf(
                    StreamItem(
                        url = streamUrl,
                        type = "mp4",
                        quality = "VeoHentai auto",
                        audio = "sub",
                        referer = "$PLAYER_HOST/",
                        isActive = true,
                        width = null,
                        height = null,
                    ),
                ),
                subtitles = listOfNotNull(subtitle),
                skip = null,
                download = null,
            )
        }

    /**
     * Solves the proof-of-work and exchanges the signed `c` token for the final MP4 URL.
     *
     * The challenge fragments and the `c` token are generated per page load, so they are always
     * read from the same [playerPage] / core script pair.
     */
    private fun resolveSignedUrl(playerPage: String, playerUrl: String): String {
        val p1 = LAST_SPAN_HEX.findAll(playerPage).lastOrNull()?.groupValues?.get(1)
        val p2 = INPUT_HEX.findAll(playerPage).lastOrNull()?.groupValues?.get(1)
        val p3 = LAST_DIV_HEX.findAll(playerPage).lastOrNull()?.groupValues?.get(1)
        val p4 = TEMPLATE_HEX.findAll(playerPage).lastOrNull()?.groupValues?.get(1)
        if (p1 == null || p2 == null || p3 == null || p4 == null) {
            error("VeoHentai: página de reto incompleta")
        }

        val corePath = CORE_SRC.find(playerPage)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            ?: error("VeoHentai: falta player-core")
        val core = CatalogHttp.get(PLAYER_HOST + corePath, referer = playerUrl)

        val token = C_TOKEN.find(core)?.groupValues?.get(1)
            ?: error("VeoHentai: falta token del reproductor")
        val stamp = T_STAMP.find(core)?.groupValues?.get(1)
            ?: error("VeoHentai: falta marca de tiempo")

        val challenge = "$p1$p2$p3$p4$stamp"
        val pow = solvePow(challenge)
        Log.d(TAG, "pow solved after $pow tries")

        val query = mapOf(
            "c" to token,
            "p1" to p1,
            "p2" to p2,
            "p3" to p3,
            "p4" to p4,
            "t" to stamp,
            "pow" to Integer.toHexString(pow),
            "fp" to Base64.encodeToString(
                fingerprint().toString().toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP,
            ),
            "df" to "",
        ).entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" }

        val response = CatalogHttp.get(
            "$PLAYER_HOST/get-video-url-v2.php?$query",
            referer = playerUrl,
            xhr = true,
        )
        val json = CatalogHttp.json(response)
        return json.optString("url").takeIf { it.isNotBlank() }
            ?: error("VeoHentai: el servidor no devolvió una URL")
    }

    /** Two leading zero bytes on SHA-256(challenge + hex(counter)), as `player-core-v2` does. */
    private fun solvePow(challenge: String): Int {
        val digest = MessageDigest.getInstance("SHA-256")
        var counter = 0
        while (counter < MAX_POW) {
            digest.reset()
            digest.update((challenge + Integer.toHexString(counter)).toByteArray(StandardCharsets.UTF_8))
            val hash = digest.digest()
            if (hash[0].toInt() == 0 && hash[1].toInt() == 0) return counter
            counter++
        }
        error("VeoHentai: no se pudo resolver el reto")
    }

    /**
     * The fingerprint payload the player sends. The server only sanity-checks it, so a stable,
     * non-identifying shape is enough and keeps the TV from looking like an automation client.
     */
    private fun fingerprint(): JSONObject = JSONObject()
        .put("t", 2600)
        .put("i", 0)
        .put("mc", 0)
        .put("tc", 0)
        .put("cc", 0)
        .put("kc", 0)
        .put("mm", JSONArray())
        .put("tm", JSONArray())
        .put(
            "b",
            JSONObject()
                .put("w", "")
                .put("v", "")
                .put("sw", 1920)
                .put("sh", 1080)
                .put("aw", 1920)
                .put("ah", 1040)
                .put("cd", 24)
                .put("tz", -120)
                .put("hc", 8)
                .put("dm", 8)
                .put("pl", "Win32")
                .put("lang", "es-ES")
                .put("dpr", 1)
                .put("ww", 1280)
                .put("wh", 720)
                .put("touch", false),
        )

    private fun subtitleFrom(dataId: String): SubtitleItem? = runCatching {
        val encoded = Uri.parse("$PLAYER_HOST$dataId").getQueryParameter("s")
            ?: return@runCatching null
        val url = decodeBase64(encoded)
        if (url.isBlank()) null else SubtitleItem(url, "spanish", "es")
    }.getOrNull()

    private fun decodeBase64(value: String): String {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
    }

    private fun parseGrouped(d: Document, page: Int): CatalogPage {
        val grouped = LinkedHashMap<String, CatalogItem>()
        for (a in d.select("a[href]")) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            val m = EPISODE.find(href) ?: continue
            val slug = m.groupValues[1]
            if (grouped.containsKey(slug)) continue
            val img = a.selectFirst("img")
            val poster = img?.let { it.absUrl("src").ifBlank { it.attr("data-src") } }
                ?.takeIf { it.isNotBlank() }
            grouped[slug] = CatalogItem(
                source = id,
                key = slug,
                title = humanize(slug),
                poster = poster,
            )
        }
        val hasNext = d.selectFirst("a[href*=/page/${page + 1}/]") != null
        return CatalogPage(grouped.values.toList(), hasNext)
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
        throw last ?: IllegalStateException("VeoHentai: no se pudo cargar $url")
    }

    private fun humanize(slug: String): String =
        slug.replace('-', ' ').replaceFirstChar { it.uppercase() }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    companion object {
        private const val TAG = "ATVLOG-VEOHENTAI"
        private const val BASE = "https://veohentai.com"
        private const val PLAYER_HOST = "https://hentaiplayer.com"
        private const val MAX_POW = 5_000_000
        private val EPISODE = Regex(
            "/ver/(.+?)-episodio-(\\d+)/?$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val EMBED = Regex("https://hentaiplayer\\.com/v/([A-Za-z0-9]+)/")
        private val DATA_ID = Regex("data-id=\"([^\"]+)\"")
        private val CORE_SRC = Regex("src=\"(/player-core-v2\\.php\\?t=[^\"]+)\"")
        private val C_TOKEN = Regex("c:\"([^\"]+)\"")
        private val T_STAMP = Regex("t:'(\\d+)'")
        private val LAST_SPAN_HEX = Regex("<span id=\"[^\"]+\" data-[\\w]+=\"([0-9a-f]{8})\"")
        private val INPUT_HEX = Regex("<input[^>]+value=\"([0-9a-f]{8})\"")
        private val LAST_DIV_HEX = Regex("<div id=\"[^\"]+\" data-[\\w]+=\"([0-9a-f]{8})\"")
        private val TEMPLATE_HEX = Regex("<template[^>]*><p>([0-9a-f]{8})</p>")
    }
}
