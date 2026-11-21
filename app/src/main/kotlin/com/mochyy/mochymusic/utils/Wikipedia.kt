// Wikipedia.kt

package com.mochyy.mochymusic.utils

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.text.Normalizer
import java.util.Locale

object Wikipedia {
    private const val UserAgent = "Mochy Music (https://github.com/buiduchuy2010qn-prog/Mochy-Music)"
    private val VietnameseDiacriticsRegex = Regex("[ăĂđĐơƠưƯĩĨũŨ\\u1EA0-\\u1EF9]")

    private val client by lazy {
        HttpClient(OkHttp) {
            engine {
                preconfigured = AppProxy.sharedClient
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                    }
                )
            }
            defaultRequest {
                header(HttpHeaders.UserAgent, UserAgent)
                header(HttpHeaders.Accept, "application/json")
            }
            expectSuccess = true
        }
    }

    @Serializable
    private data class WikiSummary(
        val extract: String? = null,
        val type: String? = null,
        val title: String? = null,
    )

    @Serializable
    private data class MediaWikiSearchResponse(
        val query: MediaWikiSearchQuery? = null,
    )

    @Serializable
    private data class MediaWikiSearchQuery(
        val search: List<MediaWikiSearchHit> = emptyList(),
    )

    @Serializable
    private data class MediaWikiSearchHit(
        val title: String? = null,
    )

    private fun containsVietnameseDiacritics(text: String): Boolean {
        if (text.isBlank()) return false
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        return VietnameseDiacriticsRegex.containsMatchIn(nfc)
    }

    private fun languagesFor(vararg texts: String?): List<String> {
        val preferVi = Locale.getDefault().language.equals("vi", ignoreCase = true) ||
            texts.any { !it.isNullOrBlank() && containsVietnameseDiacritics(it) }
        return if (preferVi) listOf("vi", "en") else listOf("en")
    }

    private fun isDisambiguation(summary: WikiSummary): Boolean {
        if (summary.type.equals("disambiguation", ignoreCase = true)) return true
        val extract = summary.extract?.trim().orEmpty()
        if (extract.isEmpty()) return true
        return extract.contains("may refer to", ignoreCase = true) ||
            extract.contains("có thể đề cập đến", ignoreCase = true) ||
            extract.contains("trang định hướng", ignoreCase = true)
    }

    private suspend fun fetchPageSummary(lang: String, title: String): WikiSummary? = runCatching {
        val summary = client.get(
            "https://$lang.wikipedia.org/api/rest_v1/page/summary/${title.trim().replace(" ", "_").encodeURLParameter(spaceToPlus = false)}"
        ).body<WikiSummary>()
        summary.takeUnless(::isDisambiguation)
    }.onFailure {
        if (it is ResponseException && it.response.status == HttpStatusCode.NotFound) {
            Timber.d("No $lang Wikipedia summary found for: $title")
        } else {
            Timber.w("Failed to fetch $lang Wikipedia summary for: $title (${it.message})")
        }
    }.getOrNull()

    private suspend fun searchWikiTitles(lang: String, searchQuery: String): List<String> = runCatching {
        client.get("https://$lang.wikipedia.org/w/api.php") {
            parameter("action", "query")
            parameter("list", "search")
            parameter("srsearch", searchQuery)
            parameter("srlimit", 5)
            parameter("format", "json")
        }.body<MediaWikiSearchResponse>().query?.search.orEmpty()
            .mapNotNull { it.title?.trim()?.takeIf { t -> t.isNotEmpty() } }
    }.onFailure {
        Timber.w("Failed to search $lang Wikipedia for: $searchQuery (${it.message})")
    }.getOrDefault(emptyList())

    private fun isAlbumSummary(hitTitle: String, extract: String): Boolean =
        hitTitle.contains("album", ignoreCase = true) ||
            extract.contains("album", ignoreCase = true) ||
            extract.contains("EP", ignoreCase = true) ||
            extract.contains("đĩa mở rộng", ignoreCase = true) ||
            extract.contains("đĩa nhạc", ignoreCase = true)

    suspend fun fetchAlbumInfo(albumTitle: String, artistName: String?): String? {
        val cleanTitle = albumTitle.trim()
        if (cleanTitle.isEmpty()) return null
        val cleanArtist = artistName?.trim()?.takeIf { it.isNotEmpty() }

        for (lang in languagesFor(cleanTitle, cleanArtist)) {
            if (cleanArtist != null) {
                val preciseQueries = if (lang == "vi") {
                    listOf(
                        "$cleanTitle (album của $cleanArtist)",
                        "$cleanTitle ($cleanArtist album)",
                        "$cleanTitle ($cleanArtist)"
                    )
                } else {
                    listOf(
                        "$cleanTitle ($cleanArtist album)",
                        "$cleanTitle ($cleanArtist)"
                    )
                }
                for (query in preciseQueries) {
                    val summary = fetchPageSummary(lang, query)
                    if (summary?.extract != null) {
                        return summary.extract
                    }
                }
            }

            val genericQueries = listOf(
                "$cleanTitle (album)",
                cleanTitle
            )
            for (query in genericQueries) {
                val summary = fetchPageSummary(lang, query)
                val extract = summary?.extract ?: continue
                if (cleanArtist == null || extract.contains(cleanArtist, ignoreCase = true)) {
                    return extract
                }
            }

            val searchQuery = if (cleanArtist != null) "$cleanTitle $cleanArtist album" else "$cleanTitle album"
            val candidates = searchWikiTitles(lang, searchQuery)
                .filterNot { hit ->
                    cleanArtist != null &&
                        !cleanTitle.equals(cleanArtist, ignoreCase = true) &&
                        hit.equals(cleanArtist, ignoreCase = true)
                }
                .sortedByDescending { hit ->
                    val hasAlbumTag = hit.contains("album", ignoreCase = true)
                    val hasTitle = hit.contains(cleanTitle, ignoreCase = true)
                    (if (hasAlbumTag) 2 else 0) + (if (hasTitle) 1 else 0)
                }
            for (hit in candidates.take(3)) {
                val summary = fetchPageSummary(lang, hit) ?: continue
                val extract = summary.extract ?: continue
                val titleMatched = hit.contains(cleanTitle, ignoreCase = true) ||
                    extract.contains(cleanTitle, ignoreCase = true)
                val artistMatched = cleanArtist == null || extract.contains(cleanArtist, ignoreCase = true)
                if (titleMatched && artistMatched && isAlbumSummary(hit, extract)) {
                    return extract
                }
            }
        }

        return null
    }

    suspend fun fetchPlaylistInfo(playlistTitle: String): String? {
        val cleanTitle = playlistTitle.trim()
        if (cleanTitle.isEmpty()) return null

        for (lang in languagesFor(cleanTitle)) {
            fetchPageSummary(lang, cleanTitle)?.extract?.let { return it }
            for (hit in searchWikiTitles(lang, cleanTitle).take(2)) {
                fetchPageSummary(lang, hit)?.extract?.let { return it }
            }
        }
        return null
    }
}
