package com.milinko.services

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.HttpHeaders.ContentEncoding
import io.netty.handler.codec.compression.StandardCompressionOptions.deflate
import io.netty.handler.codec.compression.StandardCompressionOptions.gzip
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class TmdbService(private val apiKey: String) {

    // Heuristika: TMDB v4 access token je JWT (počinje s "eyJ").
    // Ako je tako, koristimo Bearer auth umjesto v3 query parametara.
    private val useV4Bearer: Boolean = apiKey.trim().startsWith("eyJ")

    private val client = HttpClient(CIO) {
        install(ContentEncoding) {
            gzip()
            deflate()
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 12_000
            socketTimeoutMillis = 12_000
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true;
                    isLenient = true }
            )
        }
    }

    suspend fun searchMovies(title: String, year: Int? = null): List<TmdbMovieMatch> {
        val url = URLBuilder("https://api.themoviedb.org/3/search/movie").apply {
            parameters.append("query", title)
            year?.let { parameters.append("year", it.toString()) }
            parameters.append("include_adult", "false")
            parameters.append("language", "en-US")
            if (!useV4Bearer) {
                // v3 API key kroz query parametar
                parameters.append("api_key", apiKey)
            }
        }.build()

        val response: HttpResponse = client.get(url) {
            if (useV4Bearer) {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $apiKey")
                    append(HttpHeaders.Accept, ContentType.Application.Json)
                }
            }
        }
        if (!response.status.isSuccess()) {
            val bodyText = try { response.bodyAsText() } catch (_: Throwable) { "" }
            val hint = when (response.status) {
                HttpStatusCode.Unauthorized -> if (useV4Bearer)
                    "(401) Unauthorized from TMDB using v4 Bearer. Provjerite da je TMDB_API_KEY v4 access token (Bearer) ispravan i aktivan."
                else
                    "(401) Unauthorized from TMDB using v3 api_key. Ako ste unijeli v4 token, uklonite ga i koristite v3 API Key, ili koristite v4 Bearer način."
                else -> ""
            }
            throw IllegalStateException("TMDB search error: HTTP ${response.status.value}. $hint Body=${bodyText.take(300)}")
        }
        val body: TmdbSearchResponse = response.body()
        return body.results.map { r ->
            TmdbMovieMatch(
                id = r.id,
                title = r.title ?: r.originalTitle ?: "",
                originalTitle = r.originalTitle,
                releaseDate = r.releaseDate,
                overview = r.overview,
                posterPath = r.posterPath,
                popularity = r.popularity,
                voteAverage = r.voteAverage
            )
        }
    }
}

@Serializable
private data class TmdbSearchResponse(
    val page: Int? = null,
    val results: List<TmdbMovieDto> = emptyList()
)

@Serializable
private data class TmdbMovieDto(
    val id: Int,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val popularity: Double? = null,
    @SerialName("vote_average") val voteAverage: Double? = null
)

data class TmdbMovieMatch(
    val id: Int,
    val title: String,
    val originalTitle: String? = null,
    val releaseDate: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val popularity: Double? = null,
    val voteAverage: Double? = null
)
