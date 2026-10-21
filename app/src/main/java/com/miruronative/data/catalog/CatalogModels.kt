package com.miruronative.data.catalog

/**
 * Normalized catalog item. Every source returns this shape, so the Home/Search UI never needs to
 * know which site an item came from.
 *
 * [key] is the source-local identifier (usually a slug). It is opaque to the UI.
 */
data class CatalogItem(
    val source: String,
    val key: String,
    val title: String,
    val poster: String? = null,
    val banner: String? = null,
    val description: String? = null,
    val episodes: Int? = null,
    val format: String? = "TV",
    val year: Int? = null,
)

data class CatalogDetail(
    val item: CatalogItem,
    val episodes: List<Int>,
)

data class CatalogPage(
    val items: List<CatalogItem>,
    val hasNext: Boolean = false,
)
