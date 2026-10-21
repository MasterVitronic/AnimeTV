package com.miruronative.data.remote

import com.miruronative.data.model.Media
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * TioAnime (tioanime.com) — catálogo en español latino.
 *
 * Search lives in `/directorio?q=`, the anime page carries `anime_info` and an `episodes` array,
 * and each episode page exposes a `videos` array of embeds. We only resolve embeds that hand out
 * a plain file: YourUpload serves a progressive MP4 (vidcache) that ExoPlayer can play directly.
 * Other embeds need per-host resolvers and are skipped rather than returned as dead links.
 */
internal class TioAnimeProvider(private val client: OkHttpClient) {
    private val slugs = ConcurrentHashMap<Int, String>()

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val slug = slug(media)
        val html = get("$BASE/anime/$slug", "$BASE/")
        val episodes = parseEpisodes(html)
        if (episodes.isEmpty()) error("TioAnime returned no episodes")
        return EpisodeAvailability(sub = episodes, dub = emptySet())
    }

    fun sources(media: Media, audio: String, episode: Int): SourcesResult =
        sourcesForSlug(slug(media), episode)

    /** Same resolution as [sources], but keyed by the site slug (used by the catalog source). */
    fun sourcesForSlug(slug: String, episode: Int): SourcesResult {
        val html = get("$BASE/ver/$slug-$episode", "$BASE/anime/$slug")
        val videos = parseVideos(html)
        if (videos.isEmpty()) error("TioAnime episode $episode has no players")

        // YourUpload is the only embed we can resolve without a per-host extractor.
        val ordered = videos.sortedBy { if (it.first.contains("yourupload", true)) 0 else 1 }
        for ((name, embed) in ordered) {
            val direct = resolve(embed) ?: continue
            val stream = StreamItem(
                url = direct,
                type = if (direct.contains(".m3u8", true)) "hls" else "mp4",
                quality = "TioAnime $name",
                audio = "sub",
                referer = origin(embed) + "/",
                isActive = true,
                width = null,
                height = null,
            )
            return SourcesResult(listOf(stream), emptyList(), null, null)
        }
        error("TioAnime episode $episode has no direct source")
    }

    private fun slug(media: Media): String {
        slugs[media.id]?.let { return it }
        val titles = listOfNotNull(media.title.romaji, media.title.english).distinct()
        for (title in titles) {
            val html = get("$BASE/directorio?q=${encode(title)}", "$BASE/")
            val candidates = parseDirectory(html)
            val best = candidates
                .map { (slug, name) -> slug to NativeProviderParsers.titleScore(title, name) }
                .maxByOrNull { it.second }
            if (best != null && best.second >= 0.45) {
                slugs[media.id] = best.first
                return best.first
            }
        }
        error("TioAnime match not found")
    }

    private fun resolve(embed: String): String? {
        val host = runCatching { URI(embed).host?.lowercase() }.getOrNull().orEmpty()
        if (!host.contains("yourupload")) return null
        val html = runCatching { get(embed, "https://www.yourupload.com/") }.getOrNull() ?: return null
        return DIRECT_FILE.find(html)?.value?.replace("\\/", "/")
    }

    private fun parseDirectory(html: String): List<Pair<String, String>> {
        val cards = Regex(
            "<a href=\"/anime/([a-z0-9-]+)\">(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val title = Regex("<h3[^>]*class=\"title\"[^>]*>(.*?)</h3>", RegexOption.IGNORE_CASE)
        return cards.findAll(html).mapNotNull { match ->
            val slug = match.groupValues[1]
            val name = title.find(match.groupValues[2])?.groupValues?.get(1)
                ?.let(NativeProviderParsers::stripTags)
                ?: return@mapNotNull null
            slug to name
        }.toList()
    }

    private fun parseEpisodes(html: String): Set<Int> {
        val raw = Regex("var\\s+episodes\\s*=\\s*\\[([^]]*)]")
            .find(html)?.groupValues?.get(1) ?: return emptySet()
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    private fun parseVideos(html: String): List<Pair<String, String>> {
        val raw = Regex("var\\s+videos\\s*=\\s*(\\[.*?])\\s*;", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        val item = Regex("\\[\"([^\"]+)\",\"((?:\\\\.|[^\"\\\\])*)\"")
        return item.findAll(raw).mapNotNull { match ->
            val name = match.groupValues[1]
            val url = match.groupValues[2]
                .replace("\\/", "/")
                .replace("\\u0026", "&")
            if (url.startsWith("http")) name to url else null
        }.toList()
    }

    private fun get(url: String, referer: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("TioAnime HTTP ${response.code}")
            return body
        }
    }

    private fun origin(url: String): String = runCatching {
        URI(url).let { "${it.scheme}://${it.authority}" }
    }.getOrDefault(BASE)

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    companion object {
        private const val BASE = "https://tioanime.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        private val DIRECT_FILE = Regex(
            "https?://[^\"'<> ]+\\.(?:mp4|m3u8)(?:[?#][^\"'<> ]*)?(?=[\"'<> ]|$)",
            RegexOption.IGNORE_CASE,
        )
    }
}
