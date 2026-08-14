package com.milinko.services

import com.milinko.scraping.model.ScrapedMovie
import com.milinko.util.TtlCache
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.HttpHeaders.ContentEncoding
import io.netty.handler.codec.compression.StandardCompressionOptions.deflate
import io.netty.handler.codec.compression.StandardCompressionOptions.gzip
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class TmdbService(private val apiKey: String) {

    // Heuristika: TMDB v4 access token je JWT (počinje s "eyJ").
    // Ako je tako, koristimo Bearer auth umjesto v3 query parametara.
    private val useV4Bearer: Boolean = apiKey.trim().startsWith("eyJ")

    // In-memory keš (bez spoljne zavisnosti) da ne gađamo TMDB iznova za identične upite
    // dok korisnik kuca u search bar-u ili se vraća na iste ekrane. Videti MOVIELENS_ROADMAP.md
    // sekciju 2 za odluku "proxy uživo bez baze, sa malim in-memory kešom".
    private val searchCache = TtlCache<String, List<TmdbMovieMatch>>(ttlMillis = 10 * 60 * 1000)
    private val detailCache = TtlCache<Int, TmdbMovieDetail>(ttlMillis = 60 * 60 * 1000)
    private val popularCache = TtlCache<Int, List<TmdbMovieMatch>>(ttlMillis = 2 * 60 * 60 * 1000)
    private val discoverCache = TtlCache<String, List<TmdbMovieMatch>>(ttlMillis = 60 * 60 * 1000)

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

    private fun authHeaders(builder: HttpRequestBuilder) {
        if (useV4Bearer) {
            builder.headers {
                append(HttpHeaders.Authorization, "Bearer $apiKey")
                append(HttpHeaders.Accept, ContentType.Application.Json)
            }
        }
    }

    private fun unauthorizedHint(status: HttpStatusCode): String = when (status) {
        HttpStatusCode.Unauthorized -> if (useV4Bearer)
            "(401) Unauthorized from TMDB using v4 Bearer. Provjerite da je TMDB_API_KEY v4 access token (Bearer) ispravan i aktivan."
        else
            "(401) Unauthorized from TMDB using v3 api_key. Ako ste unijeli v4 token, uklonite ga i koristite v3 API Key, ili koristite v4 Bearer način."
        else -> ""
    }

    /**
     * "Hladan start" (prvi HTTPS pozivi ka TMDB posle boot-a servera - DNS + TLS handshake)
     * ume da bude sporiji od connect/request timeout-a, pa prvih par poziva zna da pukne dok se
     * konekcija ne "zagreje" (posle toga sve radi normalno). Umesto da to vidi korisnik kao
     * grešku, tiho pokušamo još jednom pre nego što propagiramo izuzetak dalje.
     */
    private suspend fun getWithRetry(url: Url, attempts: Int = 2): HttpResponse {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                return client.get(url) { authHeaders(this) }
            } catch (e: Exception) {
                lastError = e
                val isTransient = e is HttpRequestTimeoutException ||
                    e is ConnectTimeoutException ||
                    e is SocketTimeoutException ||
                    e.message?.contains("timeout", ignoreCase = true) == true
                if (!isTransient || attempt == attempts - 1) throw e
                println("[WARN] TMDB poziv timeout (pokušaj ${attempt + 1}/$attempts), ponavljam odmah: ${e.message}")
                delay(300)
            }
        }
        throw lastError ?: IllegalStateException("Nepoznata greška pri TMDB pozivu")
    }

    suspend fun searchMovies(title: String, year: Int? = null): List<TmdbMovieMatch> {
        val cacheKey = "${title.trim().lowercase()}|${year ?: ""}"
        searchCache.get(cacheKey)?.let { return it }

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

        val response: HttpResponse = getWithRetry(url)
        if (!response.status.isSuccess()) {
            val bodyText = try { response.bodyAsText() } catch (_: Throwable) { "" }
            throw IllegalStateException("TMDB search error: HTTP ${response.status.value}. ${unauthorizedHint(response.status)} Body=${bodyText.take(300)}")
        }
        val body: TmdbSearchResponse = response.body()
        println("[DEBUG] Broj rezultata u JSON-u: ${body.results.size}")
        if (body.results.isNotEmpty()) {
            println("[DEBUG] Prvi rezultat ID: ${body.results.first().id}")
        }
        val result = body.results.map { r ->
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
        searchCache.put(cacheKey, result)
        return result
    }

    /**
     * Detalji filma po TMDB ID-ju (žanrovi, cast, runtime, backdrop). Vraća null ako TMDB
     * odgovori 404 (film ne postoji). `showtimes` (bioskopski termini) namerno nisu ovde —
     * naš ScrapedMovie model nema satnicu/city/cinema_id koji Android Showtime model očekuje,
     * pa se ne izmišljaju podaci; videti napomenu u MOVIELENS_ROADMAP.md.
     */
    suspend fun getMovieDetails(tmdbId: Int): TmdbMovieDetail? {
        detailCache.get(tmdbId)?.let { return it }

        val url = URLBuilder("https://api.themoviedb.org/3/movie/$tmdbId").apply {
            parameters.append("append_to_response", "credits")
            parameters.append("language", "en-US")
            if (!useV4Bearer) parameters.append("api_key", apiKey)
        }.build()

        val response: HttpResponse = getWithRetry(url)
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) {
            val bodyText = try { response.bodyAsText() } catch (_: Throwable) { "" }
            throw IllegalStateException("TMDB detail error: HTTP ${response.status.value}. ${unauthorizedHint(response.status)} Body=${bodyText.take(300)}")
        }

        val dto: TmdbMovieDetailDto = response.body()
        val detail = TmdbMovieDetail(
            id = dto.id,
            title = dto.title ?: "",
            releaseDate = dto.releaseDate,
            posterPath = dto.posterPath,
            backdropPath = dto.backdropPath,
            overview = dto.overview,
            voteAverage = dto.voteAverage,
            popularity = dto.popularity,
            runtime = dto.runtime,
            genres = dto.genres.map { TmdbGenre(it.id, it.name) },
            cast = dto.credits?.cast.orEmpty().map { TmdbCastMember(it.id, it.name, it.character, it.profilePath) }
        )
        detailCache.put(tmdbId, detail)
        return detail
    }

    /** Popularni filmovi (koristi se za /api/movies/trending i /featured). */
    suspend fun getPopularMovies(page: Int = 1): List<TmdbMovieMatch> {
        popularCache.get(page)?.let { return it }

        val url = URLBuilder("https://api.themoviedb.org/3/movie/popular").apply {
            parameters.append("language", "en-US")
            parameters.append("page", page.toString())
            if (!useV4Bearer) parameters.append("api_key", apiKey)
        }.build()

        val response: HttpResponse = getWithRetry(url)
        if (!response.status.isSuccess()) {
            val bodyText = try { response.bodyAsText() } catch (_: Throwable) { "" }
            throw IllegalStateException("TMDB popular error: HTTP ${response.status.value}. ${unauthorizedHint(response.status)} Body=${bodyText.take(300)}")
        }
        val body: TmdbSearchResponse = response.body()
        val result = body.results.map { r ->
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
        popularCache.put(page, result)
        return result
    }

    /**
     * TMDB "discover" po žanrovima - koristi se za personalizovan /api/movies/featured (Faza 4,
     * MOVIELENS_ROADMAP.md). `genreIds` se šalje TMDB-u zarezom-razdvojeno, što TMDB tumači kao
     * OR (bilo koji od navedenih žanrova) - namerno, ne AND (pipe "|"), jer bismo inače lako dobili
     * prazne rezultate za korisnika sa raznovrsnim ukusom.
     */
    suspend fun getMoviesByGenres(genreIds: List<Int>, page: Int = 1): List<TmdbMovieMatch> {
        if (genreIds.isEmpty()) return emptyList()
        val cacheKey = "${genreIds.sorted().joinToString(",")}|$page"
        discoverCache.get(cacheKey)?.let { return it }

        val url = URLBuilder("https://api.themoviedb.org/3/discover/movie").apply {
            parameters.append("with_genres", genreIds.joinToString(","))
            parameters.append("sort_by", "popularity.desc")
            parameters.append("language", "en-US")
            parameters.append("page", page.toString())
            parameters.append("include_adult", "false")
            if (!useV4Bearer) parameters.append("api_key", apiKey)
        }.build()

        val response: HttpResponse = getWithRetry(url)
        if (!response.status.isSuccess()) {
            val bodyText = try { response.bodyAsText() } catch (_: Throwable) { "" }
            throw IllegalStateException("TMDB discover error: HTTP ${response.status.value}. ${unauthorizedHint(response.status)} Body=${bodyText.take(300)}")
        }
        val body: TmdbSearchResponse = response.body()
        val result = body.results.map { r ->
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
        discoverCache.put(cacheKey, result)
        return result
    }
}

@Serializable
private data class TmdbSearchResponse(
    val page: Int? = null,
    val results: List<TmdbMovieDto> = emptyList()
)

@Serializable
private data class TmdbMovieDto(
    val id: Int? = null,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val popularity: Double? = null,
    @SerialName("vote_average") val voteAverage: Double? = null
)

@Serializable
private data class TmdbMovieDetailDto(
    val id: Int,
    val title: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val popularity: Double? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenreDto> = emptyList(),
    val credits: TmdbCreditsDto? = null
)

@Serializable
private data class TmdbGenreDto(val id: Int, val name: String)

@Serializable
private data class TmdbCreditsDto(val cast: List<TmdbCastDto> = emptyList())

@Serializable
private data class TmdbCastDto(
    val id: Int,
    val name: String,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

data class TmdbMovieDetail(
    val id: Int,
    val title: String,
    val releaseDate: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val overview: String?,
    val voteAverage: Double?,
    val popularity: Double? = null,
    val runtime: Int?,
    val genres: List<TmdbGenre>,
    val cast: List<TmdbCastMember>
)

// @Serializable (ne samo data class) - genres se čuvaju kao JSON string u movies.genres koloni
// (Faza 4, MOVIELENS_ROADMAP.md - taste profile po žanru), videti MoviesRepository.ensureCached.
@Serializable
data class TmdbGenre(val id: Int, val name: String)

data class TmdbCastMember(
    val id: Int,
    val name: String,
    val character: String?,
    val profilePath: String?
)

data class TmdbMovieMatch(
    val id: Int?,
    val title: String,
    val originalTitle: String? = null,
    val releaseDate: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val popularity: Double? = null,
    val voteAverage: Double? = null
)


/**
 * Proširenje koje obogaćuje listu ScrapedMovie objekata sa TMDB ID-jevima.
 * Vraća Mapu gde je ključ originalni ScrapedMovie, a vrednost pronađeni TMDB ID.
 */
suspend fun List<ScrapedMovie>.enrichWithTmdbIds(
    tmdbService: TmdbService
): List<ScrapedMovie> { // Vrati listu, ne mapu
    val titleCache = mutableMapOf<String, Int?>()

    return this.map { movie ->
        val searchKey = (movie.originalTitle ?: movie.title).trim().lowercase()

        // Ako imamo u kešu, odmah iskoristi
        if (titleCache.containsKey(searchKey)) {
            return@map movie.copy(tmdbId = titleCache[searchKey])
        }

        // Ako nema u kešu, pozovi TMDB
        val searchResults = try {
            tmdbService.searchMovies(movie.originalTitle ?: movie.title)
        } catch (e: Exception) {
            println("[ERROR] Enrichment failed for ${movie.title}: ${e.message}")
            emptyList<TmdbMovieMatch>()
        }

        val foundId = searchResults.firstOrNull()?.id
        titleCache[searchKey] = foundId

        // PAUZA: Obavezno delay
        delay(300)

        // Vrati novi objekat sa popunjenim ID-jem
        movie.copy(tmdbId = foundId)
    }
}