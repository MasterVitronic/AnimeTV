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
 * AnimeAV1 (animeav1.com) — Spanish-language catalog with separate SUB and DUB embed groups.
 *
 * Search lives in `/catalogo?search=`, the media page embeds an `episodes:[{id,number}]` array and
 * each episode page exposes `embeds:{SUB:[...],DUB:[...]}` plus a `downloads:{...}` block that
 * repeats the same language keys, so parsing must stop before it. Only hosts that hand out a plain
 * MP4 are resolved (MP4Upload and YourUpload, both verified with `206 Partial Content`); Zilla's
 * HLS playlist is skipped because its segments answer 403.
 */
internal class AnimeAv1Provider(private val client: OkHttpClient) {
    private val slugs = ConcurrentHashMap<Int, String>()

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val slug = slug(media)
        val html = get("$BASE/media/$slug", "$BASE/")
        val episodes = parseEpisodes(html)
        if (episodes.isEmpty()) error("AnimeAV1 returned no episodes")
        // Dub coverage is per-episode; probe the first one once instead of guessing.
        val hasDub = runCatching {
            get("$BASE/media/$slug/${episodes.min()}", "$BASE/media/$slug").contains("embeds:{DUB:")
        }.getOrDefault(false)
        return EpisodeAvailability(episodes, if (hasDub) episodes else emptySet())
    }

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val slug = slug(media)
        val html = get("$BASE/media/$slug/$episode", "$BASE/media/$slug")
        val groups = parseEmbeds(html)
        if (groups.isEmpty()) error("AnimeAV1 episode $episode has no players")

        val exact = audio.uppercase()
        val lang = when {
            groups.containsKey(exact) -> exact
            exact == "DUB" && groups.containsKey("SUB") -> "SUB"
            exact == "SUB" && groups.containsKey("DUB") -> "DUB"
            else -> groups.keys.first()
        }
        val usedAudio = if (lang == "DUB") "dub" else "sub"

        val embeds = groups[lang].orEmpty()
        val ordered = embeds.sortedBy { (server, _) ->
            if (server.contains("yourupload", true)) 0 else 1
        }
        val resolved = ordered.mapNotNull { (server, url) ->
            resolve(server, url)?.let { server to it }
        }
        if (resolved.isEmpty()) error("AnimeAV1 episode $episode has no direct source")

        val streams = resolved.mapIndexed { index, (server, direct) ->
            StreamItem(
                url = direct,
                type = "mp4",
                quality = "AnimeAV1 $server",
                audio = usedAudio,
                referer = origin(embeds.firstOrNull { it.first == server }?.second ?: direct) + "/",
                isActive = index == 0,
                width = null,
                height = null,
            )
        }
        return SourcesResult(streams.distinctBy(StreamItem::url), emptyList(), null, null)
    }

    private fun slug(media: Media): String {
        slugs[media.id]?.let { return it }
        val titles = listOfNotNull(media.title.romaji, media.title.english).distinct()
        for (title in titles) {
            val html = get("$BASE/catalogo?search=${encode(title)}", "$BASE/")
            val candidates = parseSearch(html)
            val best = candidates
                .map { (slug, name) -> slug to NativeProviderParsers.titleScore(title, name) }
                .maxByOrNull { it.second }
            if (best != null && best.second >= 0.45) {
                slugs[media.id] = best.first
                return best.first
            }
        }
        error("AnimeAV1 match not found")
    }

    private fun resolve(server: String, url: String): String? = when {
        // MP4Upload serves AV1 10-bit here, which the mt5867 cannot decode; only YourUpload is tried.
        server.contains("yourupload", true) -> resolveEmbed(url, "https://www.yourupload.com/")
        else -> null
    }

    private fun resolveEmbed(embed: String, referer: String): String? {
        val html = runCatching { get(embed, referer) }.getOrNull() ?: return null
        return DIRECT_FILE.find(html)?.value
    }

    private fun parseSearch(html: String): List<Pair<String, String>> {
        val cards = Regex(
            "<a[^>]+href=\"(/media/[a-z0-9-]+)\"[^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return cards.findAll(html).mapNotNull { match ->
            val slug = match.groupValues[1].removePrefix("/media/").trim('/')
            val name = NativeProviderParsers.stripTags(match.groupValues[2])
                .removePrefix("Ver ")
                .trim()
            if (slug.isBlank() || name.isBlank()) null else slug to name
        }.toList()
    }

    private fun parseEpisodes(html: String): Set<Int> {
        val raw = Regex("episodes:\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return emptySet()
        return Regex("number:(\\d+)").findAll(raw)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toSet()
    }

    private fun parseEmbeds(html: String): Map<String, List<Pair<String, String>>> {
        val start = html.indexOf("embeds:{")
        if (start < 0) return emptyMap()
        // The page repeats SUB/DUB under `downloads:{...}`. Stop at that block so download hosts
        // (Mega, 1Fichier, StreamTape) never overwrite the embed group with the same key.
        val from = start + "embeds:{".length
        val downloads = html.indexOf("downloads:{", from)
        val slice = html.substring(from, if (downloads > from) downloads else html.length)
        val group = Regex("([A-Z]+):\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
        val item = Regex("server:\"([^\"]+)\",url:\"([^\"]+)\"")
        val result = LinkedHashMap<String, List<Pair<String, String>>>()
        for (match in group.findAll(slice)) {
            val language = match.groupValues[1]
            val items = item.findAll(match.groupValues[2])
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()
            if (items.isNotEmpty()) result.putIfAbsent(language, items)
        }
        return result
    }

    private fun get(url: String, referer: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("AnimeAV1 HTTP ${response.code}")
            return body
        }
    }

    private fun origin(url: String): String = runCatching {
        URI(url).let { "${it.scheme}://${it.authority}" }
    }.getOrDefault(BASE)

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    companion object {
        private const val BASE = "https://animeav1.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        private val DIRECT_FILE = Regex(
            "https?://[^\"'<> ]+\\.mp4(?:[?#][^\"'<> ]*)?(?=[\"'<> ]|$)",
            RegexOption.IGNORE_CASE,
        )
    }
}
