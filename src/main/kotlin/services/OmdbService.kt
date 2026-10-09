package com.milinko.services

import com.milinko.util.TtlCache
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("OmdbService")

/**
 * Optional source of IMDb and Rotten Tomatoes ratings for the movie detail screen. TMDB returns no
 * Rotten Tomatoes score, and its "IMDb" rating is really TMDB's own.
 *
 * Optional on purpose: when [apiKey] is null (OMDB_API_KEY not set), [getRatings] always returns
 * null without any network call, and the movie detail simply shows no extra ratings.
 *
 * The free OMDb tier allows 1000 requests per day; ratings change rarely, so a long-lived cache
 * keeps the number of calls low.
 */
class OmdbService(private val apiKey: String?) {

    private val ratingsCache = TtlCache<String, OmdbRatings?>(ttlMillis = 24 * 60 * 60 * 1000)

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 8_000
            socketTimeoutMillis = 8_000
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    /**
     * IMDb and Rotten Tomatoes ratings for [imdbId] (from TMDB's `imdb_id`), or null if unknown,
     * not configured, or OMDb failed. Never throws.
     */
    suspend fun getRatings(imdbId: String?): OmdbRatings? {
        if (apiKey.isNullOrBlank() || imdbId.isNullOrBlank()) return null
        ratingsCache.get(imdbId)?.let { return it }

        val url = URLBuilder("https://www.omdbapi.com/").apply {
            parameters.append("i", imdbId)
            parameters.append("apikey", apiKey)
        }.build()

        val ratings: OmdbRatings? = try {
            val response: HttpResponse = client.get(url)
            if (!response.status.isSuccess()) {
                logger.warn("OMDb HTTP ${response.status.value} za $imdbId")
                null
            } else {
                val dto: OmdbResponseDto = response.body()
                if (dto.response == "False") {
                    // OMDb answers 200 OK with response="False" for an unknown id - not an error,
                    // just no data.
                    null
                } else {
                    val rottenTomatoes = dto.ratings
                        ?.firstOrNull { it.source == "Rotten Tomatoes" }
                        ?.value
                    val imdbRating = dto.imdbRating?.takeIf { it != "N/A" }?.toDoubleOrNull()
                    if (imdbRating == null && rottenTomatoes == null) null
                    else OmdbRatings(imdbRating = imdbRating, rottenTomatoesPercent = rottenTomatoes)
                }
            }
        } catch (e: Exception) {

            logger.warn("OMDb poziv nije uspeo za $imdbId: ${e.message}")
            null
        }

        // Null results are cached too, so a movie without OMDb data is not requested again on
        // every visit to its page.
        ratingsCache.put(imdbId, ratings)
        return ratings
    }
}

/** Extra ratings from OMDb; each one can be missing. */
data class OmdbRatings(
    val imdbRating: Double?,
    /** E.g. "87%" - OMDb returns a ready-made string, not a number. */
    val rottenTomatoesPercent: String?
)

@Serializable
private data class OmdbResponseDto(
    @SerialName("imdbRating") val imdbRating: String? = null,
    @SerialName("Ratings") val ratings: List<OmdbRatingEntryDto>? = null,
    @SerialName("Response") val response: String? = null
)

@Serializable
private data class OmdbRatingEntryDto(
    @SerialName("Source") val source: String? = null,
    @SerialName("Value") val value: String? = null
)
