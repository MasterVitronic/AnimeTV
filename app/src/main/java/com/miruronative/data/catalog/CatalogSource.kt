package com.miruronative.data.catalog

import com.miruronative.data.model.SourcesResult

/**
 * A browsable source. Adding a new site means implementing this interface and registering it in
 * [CatalogRegistry]; neither the bridge nor the UI need to change.
 *
 * Contract:
 * - [home] and [search] return normalized [CatalogItem]s (carousel/grid cards).
 * - [detail] returns the episode list for a [CatalogItem.key].
 * - [sources] resolves a playable stream for one episode. Implementations that need a real player
 *   (JS/token) should delegate to a WebView resolver.
 */
interface CatalogSource {
    val id: String
    val label: String

    suspend fun home(page: Int): CatalogPage
    suspend fun search(query: String, page: Int): CatalogPage
    suspend fun detail(key: String): CatalogDetail
    suspend fun sources(key: String, episode: Int, audio: String): SourcesResult
}
