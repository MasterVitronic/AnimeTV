package com.miruronative.data.catalog

import com.miruronative.data.model.SourcesResult
import com.miruronative.data.remote.TioAnimeProvider
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * TioAnime (tioanime.com) — full Spanish (latino) catalog, independent from AniList.
 *
 * This is the AniList-free fallback while AniList keeps its API disabled:
 * - Home: "Últimos Animes" cards; page 2+ uses `/directorio?p=N`.
 * - Search: `/directorio?q=...&p=N`.
 * - Detail: `/anime/<slug>` carries `anime_info`, the synopsis and the `episodes` array.
 * - Playback: `/ver/<slug>-N` exposes a `videos` array; [TioAnimeProvider] already resolves
 *   YourUpload to a progressive MP4 (H.264 8-bit, 720p) that the TV can decode.
 */
internal class TioAnimeSource : CatalogSource {
    override val id = "tioanime"
    override val label = "TioAnime"

    private val playback = TioAnimeProvider(CatalogHttp.client)

    override suspend fun home(page: Int): CatalogPage = withContext(Dispatchers.IO) {
        if (page <= 1) {
            parseCards(doc("$BASE/"), hasNext = true)
        } else {
            val d = doc("$BASE/directorio?p=$page")
            parseCards(d, hasNext = hasNextPage(d, page))
        }
    }

    override suspend fun search(query: String, page: Int): CatalogPage = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val d = doc("$BASE/directorio?q=$q&p=${page.coerceAtLeast(1)}")
        parseCards(d, hasNext = hasNextPage(d, page))
    }

    override suspend fun detail(key: String): CatalogDetail = withContext(Dispatchers.IO) {
        val d = doc("$BASE/anime/$key")
        val title = d.selectFirst("h1.title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: INFO_TITLE.find(d.html())?.groupValues?.get(1)
            ?: humanize(key)
        val poster = d.selectFirst("aside .thumb img")?.absUrl("src")
            ?.takeIf { it.isNotBlank() }
        val description = d.selectFirst("p.sinopsis, div.sinopsis")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
        val year = d.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
        val format = (d.selectFirst("span.anime-type-peli") ?: d.selectFirst("span.anime-type"))
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
        val episodes = parseEpisodes(d.html()).ifEmpty { listOf(1) }
        CatalogDetail(
            item = CatalogItem(
                source = id,
                key = key,
                title = title,
                poster = poster,
                description = description,
                episodes = episodes.size,
                format = format ?: "TV",
                year = year,
            ),
            episodes = episodes,
        )
    }

    override suspend fun sources(key: String, episode: Int, audio: String): SourcesResult =
        withContext(Dispatchers.IO) {
            playback.sourcesForSlug(key, episode)
        }

    private fun parseCards(d: Document, hasNext: Boolean): CatalogPage {
        val seen = LinkedHashSet<String>()
        val items = mutableListOf<CatalogItem>()
        for (a in d.select("article.anime a[href]")) {
            val href = a.attr("href")
            if (!href.startsWith("/anime/")) continue
            val slug = SERIES.find(href)?.groupValues?.get(1) ?: continue
            if (!seen.add(slug)) continue
            val title = a.selectFirst("h3.title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: a.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: humanize(slug)
            val poster = a.selectFirst("img")?.absUrl("src")?.takeIf { it.isNotBlank() }
            items += CatalogItem(id, slug, title, poster)
        }
        return CatalogPage(items, hasNext && items.isNotEmpty())
    }

    private fun parseEpisodes(html: String): List<Int> {
        val raw = Regex("var\\s+episodes\\s*=\\s*\\[([^]]*)]").find(html)
            ?.groupValues?.get(1) ?: return emptyList()
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            .toSortedSet().toList()
    }

    private fun hasNextPage(d: Document, page: Int): Boolean =
        d.select("a.page-link").any { it.attr("href").contains("p=${page + 1}") }

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
        throw last ?: IllegalStateException("TioAnime: no se pudo cargar $url")
    }

    private fun humanize(slug: String): String =
        slug.replace('-', ' ').replaceFirstChar { it.uppercase() }

    companion object {
        private const val BASE = "https://tioanime.com"
        private val SERIES = Regex("^/anime/([a-z0-9-]+)/?$", RegexOption.IGNORE_CASE)
        private val INFO_TITLE = Regex("var\\s+anime_info\\s*=\\s*\\[[^,]+,[^,]+,\"([^\"]+)\"")
    }
}
