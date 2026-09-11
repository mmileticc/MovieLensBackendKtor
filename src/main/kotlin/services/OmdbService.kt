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

/**
 * OMDb (omdbapi.com) - dopunski, opcioni izvor IMDb i Rotten Tomatoes ocena za Movie Detail
 * ekran (dorada, 3. septembar 2026 - TMDB ocena je već prikazana, ovo je dopuna, ne zamena).
 * TMDB ne vraća RT ocenu uopšte, a njihova IMDb ocena je zapravo TMDB-ova sopstvena, ne prava
 * IMDb ocena - otud posebna, dodatna integracija.
 *
 * NAMERNO opciono: [apiKey] može biti null (npr. OMDB_API_KEY nije postavljen na Render-u) - u
 * tom slučaju [getRatings] uvek vraća null bez ijednog mrežnog poziva, umesto da baci grešku i
 * obori ceo /api/movies/{tmdbId} odgovor. Movie Detail i dalje radi normalno, samo bez ove dve
 * dodatne ocene (isti princip kao showtimes koji nedostaju kad skreping ne pokrije dati film).
 *
 * Besplatan OMDb tier je ograničen na 1000 poziva/dan po ključu - TtlCache sa dugim TTL-om (RT/
 * IMDb ocene filma se menjaju retko, za razliku od TMDB popularity/vote_count) drži broj poziva
 * nisko čak i uz veći saobraćaj.
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
     * [imdbId] dolazi iz TMDB /movie/{id} odgovora (polje imdb_id, već uključeno u osnovni
     * odgovor bez posebnog append_to_response) - videti TmdbMovieDetail.imdbId.
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
                println("[WARN] OMDb HTTP ${response.status.value} za $imdbId")
                null
            } else {
                val dto: OmdbResponseDto = response.body()
                if (dto.response == "False") {
                    // OMDb vraća 200 OK sa response="False" i Error poljem za nepoznat imdbId,
                    // umesto HTTP status koda - ovo NIJE mrežna greška, samo nema podataka.
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

            println("[WARN] OMDb poziv nije uspeo za $imdbId: ${e.message}")
            null
        }

        // Kešira se i null rezultat (film bez RT/IMDb podataka na OMDb-u ostaje bez njih), da
        // se isti tmdbId/imdbId ne gađa iznova pri svakom otvaranju iste stranice filma.
        ratingsCache.put(imdbId, ratings)
        return ratings
    }
}

data class OmdbRatings(
    val imdbRating: Double?,
    /** Npr. "87%" - OMDb ovo vraća kao gotov string, ne kao broj. */
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
