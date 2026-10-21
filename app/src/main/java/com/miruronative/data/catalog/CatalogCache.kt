package com.miruronative.data.catalog

import com.amarullz.androidtv.animetvjmto.AnimeApi
import java.io.File

/**
 * Tiny on-disk store for catalog metadata that is expensive to rebuild (e.g. AnimeFLV's series
 * index). It lives under the app's OkHttp cache dir, which is already cleaned by the system when
 * storage is low, and never holds credentials.
 */
internal object CatalogCache {
    private fun dir(): File? {
        val base = AnimeApi.okCacheDir ?: return null
        val dir = File(base, "catalog")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun read(name: String): String? = try {
        dir()?.resolve(name)?.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    fun write(name: String, text: String) {
        try {
            dir()?.resolve(name)?.writeText(text)
        } catch (e: Exception) {
            // A cache miss is always recoverable: the next call rebuilds it.
        }
    }

    fun ageMs(name: String): Long = try {
        val file = dir()?.resolve(name)?.takeIf { it.isFile } ?: return Long.MAX_VALUE
        System.currentTimeMillis() - file.lastModified()
    } catch (e: Exception) {
        Long.MAX_VALUE
    }
}
