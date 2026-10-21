package com.miruronative.data.catalog

import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Shared HTTP plumbing for catalog sources.
 *
 * Catalog sources must be able to resolve streams with plain HTTP: the TV only has ~1.4 GB of RAM,
 * so spinning up a hidden WebView per episode is the last resort, not the default. Everything here
 * keeps the exact `User-Agent`/`Referer` pair the sites expect, because their CDNs reject requests
 * that look like they came from an app.
 */
internal object CatalogHttp {

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun get(
        url: String,
        referer: String? = null,
        origin: String? = null,
        xhr: Boolean = false,
    ): String =
        execute(
            Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "text/html,application/json,*/*")
                .apply {
                    referer?.let { header("Referer", it) }
                    origin?.let { header("Origin", it) }
                    if (xhr) header("X-Requested-With", "XMLHttpRequest")
                }
                .get()
                .build(),
        )

    fun postForm(
        url: String,
        fields: Map<String, String>,
        referer: String? = null,
        origin: String? = null,
        xhr: Boolean = true,
    ): String {
        val body = FormBody.Builder().apply {
            fields.forEach { (key, value) -> add(key, value) }
        }.build()
        return execute(
            Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "application/json, text/plain, */*")
                .apply {
                    referer?.let { header("Referer", it) }
                    origin?.let { header("Origin", it) }
                    if (xhr) header("X-Requested-With", "XMLHttpRequest")
                }
                .post(body)
                .build(),
        )
    }

    fun json(text: String): JSONObject = JSONObject(text)

    private fun execute(request: Request): String =
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code} ${request.url} :: ${body.take(200)}")
            }
            body
        }
}
