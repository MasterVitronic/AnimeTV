package com.miruronative.data.catalog

/**
 * Central registry of browsable sources. Registering a new site here is the only wiring needed for
 * the bridge (and therefore the UI) to see it.
 */
object CatalogRegistry {
    data class Ref(val source: String, val key: String)

    private val sources = LinkedHashMap<String, CatalogSource>()
    private val refs = HashMap<Int, Ref>()
    private val ids = HashMap<String, Int>()
    private var nextId = 910_000_000

    init {
        register(HentailaSource())
        register(VeoHentaiSource())
    }

    fun register(source: CatalogSource) {
        sources[source.id] = source
    }

    fun get(id: String): CatalogSource? = sources[id]

    fun all(): List<CatalogSource> = sources.values.toList()

    /** Stable per-process numeric id, so the existing UI can keep using integer media ids. */
    fun idFor(source: String, key: String): Int = synchronized(this) {
        ids.getOrPut("$source|$key") {
            val id = nextId++
            refs[id] = Ref(source, key)
            id
        }
    }

    fun refFor(id: Int): Ref? = synchronized(this) { refs[id] }
}
