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
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("TmdbService")

/**
 * TMDB client: search, details, people, genres, popular and discover lists. Every call is cached
 * in memory (see the caches below), so the app never talks to TMDB directly and repeated screens
 * do not cost TMDB requests. Texts are requested in English (`language=en-US`).
 */
class TmdbService(private val apiKey: String) {

    // A TMDB v4 access token is a JWT (starts with "eyJ"); it is sent as a Bearer header.
    // Otherwise the key is a v3 API key sent as the api_key query parameter.
    private val useV4Bearer: Boolean = apiKey.trim().startsWith("eyJ")

    // In-memory caches, so identical requests (typing in search, returning to a screen) do not hit
    // TMDB again within the TTL.
    private val searchCache = TtlCache<String, List<TmdbMovieMatch>>(ttlMillis = 10 * 60 * 1000)
    private val detailCache = TtlCache<Int, TmdbMovieDetail>(ttlMillis = 60 * 60 * 1000)
    // Biographies and filmographies change even less often than movie details.
    private val personCache = TtlCache<Int, TmdbPersonDetail>(ttlMillis = 60 * 60 * 1000)
    private val popularCache = TtlCache<Int, List<TmdbMovieMatch>>(ttlMillis = 2 * 60 * 60 * 1000)
    // TMDB's genre list practically never changes - one key, long TTL.
    private val genreListCache = TtlCache<String, List<TmdbGenre>>(ttlMillis = 24 * 60 * 60 * 1000)
    // Caches whole pages including total_pages (see getMoviesByGenresWithTotalPages).
    private val discoverCache = TtlCache<String, GenreDiscoverPage>(ttlMillis = 60 * 60 * 1000)

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
     * The first HTTPS calls after a server start (DNS + TLS handshake) can exceed the timeout.
     * Instead of showing the user an error, a timed-out request is retried once.
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
                logger.warn("TMDB poziv timeout (pokušaj ${attempt + 1}/$attempts), ponavljam odmah: ${e.message}")
                delay(300)
            }
        }
        throw lastError ?: IllegalStateException("Nepoznata greška pri TMDB pozivu")
    }

    /** TMDB movie search by title (optionally narrowed by release year). */
    suspend fun searchMovies(title: String, year: Int? = null): List<TmdbMovieMatch> {
        val cacheKey = "${title.trim().lowercase()}|${year ?: ""}"
        searchCache.get(cacheKey)?.let { return it }

        val url = URLBuilder("https://api.themoviedb.org/3/search/movie").apply {
            parameters.append("query", title)
            year?.let { parameters.append("year", it.toString()) }
            parameters.append("include_adult", "false")
            parameters.append("language", "en-US")
            if (!useV4Bearer) {
                // v3 API key as a query parameter
                parameters.append("api_key", apiKey)
            }
        }.build()

        val response: HttpResponse = getWithRetry(url)
        if (!response.status.isSuccess()) {
            val bodyText = try { response.bodyAsText() } catch (_: Throwable) { "" }
            throw IllegalStateException("TMDB search error: HTTP ${response.status.value}. ${unauthorizedHint(response.status)} Body=${bodyText.take(300)}")
        }
        val body: TmdbSearchResponse = response.body()
        logger.debug("Broj rezultata u JSON-u: ${body.results.size}")
        if (body.results.isNotEmpty()) {
            logger.debug("Prvi rezultat ID: ${body.results.first().id}")
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
                voteAverage = r.voteAverage,
                genreIds = r.genreIds
            )
        }
        searchCache.put(cacheKey, result)
        return result
    }

    /**
     * Movie details by TMDB id (genres, cast, runtime, trailers, backdrops, IMDb id), or null if
     * TMDB answers 404.
     */
    suspend fun getMovieDetails(tmdbId: Int): TmdbMovieDetail? {
        detailCache.get(tmdbId)?.let { return it }

        val url = URLBuilder("https://api.themoviedb.org/3/movie/$tmdbId").apply {
            // Videos and images in the SAME request (trailers and gallery on the detail screen),
            // still one cached TMDB request per movie.
            parameters.append("append_to_response", "credits,videos,images")
            // Without this, images only returns pictures in the request language; backdrops are
            // usually language-less (iso_639_1 = null), so the gallery would be almost empty.
            parameters.append("include_image_language", "en,null")
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
            cast = dto.credits?.cast.orEmpty().map { TmdbCastMember(it.id, it.name, it.character, it.profilePath) },
            // imdb_id is part of the basic /movie/{id} response; used for the extra IMDb/Rotten
            // Tomatoes ratings from OmdbService.
            imdbId = dto.imdbId?.takeIf { it.isNotBlank() },
            // Only YouTube trailers/teasers (the only thing the app's player can play), official
            // trailers first.
            videos = dto.videos?.results.orEmpty()
                .filter { it.site == "YouTube" && !it.key.isNullOrBlank() && it.type in TRAILER_TYPES }
                .sortedWith(
                    compareByDescending<TmdbVideoDto> { it.type == "Trailer" }
                        .thenByDescending { it.official == true }
                )
                .take(MAX_VIDEOS)
                .map { TmdbVideo(key = it.key!!, name = it.name.orEmpty(), type = it.type.orEmpty()) },
            backdrops = dto.images?.backdrops.orEmpty()
                .sortedByDescending { it.voteAverage ?: 0.0 }
                .mapNotNull { it.filePath?.takeIf { p -> p.isNotBlank() } }
                .distinct()
                .take(MAX_BACKDROPS)
        )
        detailCache.put(tmdbId, detail)
        return detail
    }

    /**
     * Person details by TMDB id (biography, photo, filmography), or null if unknown.
     * `append_to_response=movie_credits` returns the filmography in the same request.
     * Credits are sorted by popularity, so the best-known roles come first.
     */
    suspend fun getPersonDetails(personId: Int): TmdbPersonDetail? {
        personCache.get(personId)?.let { return it }

        val url = URLBuilder("https://api.themoviedb.org/3/person/$personId").apply {
            parameters.append("append_to_response", "movie_credits")
            parameters.append("language", "en-US")
            if (!useV4Bearer) parameters.append("api_key", apiKey)
        }.build()

        val response: HttpResponse = getWithRetry(url)
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) {
            val bodyText = try { response.bodyAsText() } catch (_: Throwable) { "" }
            throw IllegalStateException("TMDB person error: HTTP ${response.status.value}. ${unauthorizedHint(response.status)} Body=${bodyText.take(300)}")
        }

        val dto: TmdbPersonDetailDto = response.body()
        val detail = TmdbPersonDetail(
            id = dto.id,
            name = dto.name ?: "",
            biography = dto.biography?.takeIf { it.isNotBlank() },
            birthday = dto.birthday,
            deathday = dto.deathday,
            placeOfBirth = dto.placeOfBirth,
            profilePath = dto.profilePath,
            knownForDepartment = dto.knownForDepartment,
            movieCredits = dto.movieCredits?.cast.orEmpty()
                .distinctBy { it.id }
                .sortedByDescending { it.popularity ?: 0.0 }
                .map {
                    TmdbPersonMovieCredit(
                        tmdbId = it.id,
                        title = it.title ?: it.originalTitle ?: "",
                        character = it.character?.takeIf { c -> c.isNotBlank() },
                        posterPath = it.posterPath,
                        releaseDate = it.releaseDate,
                        voteAverage = it.voteAverage
                    )
                }
        )
        personCache.put(personId, detail)
        return detail
    }

    /** All TMDB movie genres (id -> name), for the genre filters in the app. */
    suspend fun getGenres(): List<TmdbGenre> {
        genreListCache.get("movie")?.let { return it }

        val url = URLBuilder("https://api.themoviedb.org/3/genre/movie/list").apply {
            parameters.append("language", "en-US")
            if (!useV4Bearer) parameters.append("api_key", apiKey)
        }.build()

        val response: HttpResponse = getWithRetry(url)
        if (!response.status.isSuccess()) {
            val bodyText = try { response.bodyAsText() } catch (_: Throwable) { "" }
            throw IllegalStateException("TMDB genre list error: HTTP ${response.status.value}. ${unauthorizedHint(response.status)} Body=${bodyText.take(300)}")
        }
        val body: TmdbGenreListDto = response.body()
        val result = body.genres.map { TmdbGenre(it.id, it.name) }
        genreListCache.put("movie", result)
        return result
    }

    /** Popular movies (used by /api/movies/trending and as the non-personalized /featured). */
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
                voteAverage = r.voteAverage,
                genreIds = r.genreIds
            )
        }
        popularCache.put(page, result)
        return result
    }

    /**
     * TMDB "discover" by genres, used by the personalized /featured and by Movie Night.
     *
     * Genre ids are joined with "|", which TMDB treats as OR (any of the genres). A comma means AND
     * (all genres at once), which made suggestions for several shared genres far too narrow.
     */
    suspend fun getMoviesByGenres(genreIds: List<Int>, page: Int = 1): List<TmdbMovieMatch> =
        getMoviesByGenresWithTotalPages(genreIds, page).movies

    /**
     * Same as [getMoviesByGenres], plus TMDB's `total_pages` for the query. Movie Night uses it to
     * page through all available results: a broad genre mix has hundreds of pages, a narrow one
     * only a few.
     */
    suspend fun getMoviesByGenresWithTotalPages(genreIds: List<Int>, page: Int = 1): GenreDiscoverPage {
        if (genreIds.isEmpty()) return GenreDiscoverPage(emptyList(), 0)
        val cacheKey = "${genreIds.sorted().joinToString(",")}|$page"
        discoverCache.get(cacheKey)?.let { return it }

        val url = URLBuilder("https://api.themoviedb.org/3/discover/movie").apply {
            parameters.append("with_genres", genreIds.joinToString("|"))
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
        val movies = body.results.map { r ->
            TmdbMovieMatch(
                id = r.id,
                title = r.title ?: r.originalTitle ?: "",
                originalTitle = r.originalTitle,
                releaseDate = r.releaseDate,
                overview = r.overview,
                posterPath = r.posterPath,
                popularity = r.popularity,
                voteAverage = r.voteAverage,
                genreIds = r.genreIds
            )
        }
        val result = GenreDiscoverPage(movies, (body.totalPages ?: 1).coerceAtLeast(1))
        discoverCache.put(cacheKey, result)
        return result
    }
}

/** One TMDB discover page plus the total number of pages for the query. */
data class GenreDiscoverPage(val movies: List<TmdbMovieMatch>, val totalPages: Int)

@Serializable
private data class TmdbSearchResponse(
    val page: Int? = null,
    val results: List<TmdbMovieDto> = emptyList(),
    @SerialName("total_pages") val totalPages: Int? = null
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
    @SerialName("vote_average") val voteAverage: Double? = null,
    // genre_ids is part of every TMDB list response; the app uses it for genre filters.
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList()
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
    val credits: TmdbCreditsDto? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val videos: TmdbVideosDto? = null,
    val images: TmdbImagesDto? = null
)

@Serializable
private data class TmdbVideosDto(val results: List<TmdbVideoDto> = emptyList())

@Serializable
private data class TmdbVideoDto(
    val key: String? = null,
    val name: String? = null,
    val site: String? = null,
    val type: String? = null,
    val official: Boolean? = null
)

@Serializable
private data class TmdbImagesDto(val backdrops: List<TmdbImageDto> = emptyList())

@Serializable
private data class TmdbImageDto(
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null
)

@Serializable
private data class TmdbGenreListDto(val genres: List<TmdbGenreDto> = emptyList())

private val TRAILER_TYPES = setOf("Trailer", "Teaser")
private const val MAX_VIDEOS = 5
private const val MAX_BACKDROPS = 12

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

/** Movie details as used by the routes (mapped from TMDB's response). */
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
    val cast: List<TmdbCastMember>,
    val imdbId: String? = null,
    val videos: List<TmdbVideo> = emptyList(),
    val backdrops: List<String> = emptyList()
)

/** A YouTube trailer or teaser of a movie; `key` is the YouTube video id. */
data class TmdbVideo(val key: String, val name: String, val type: String)

// Serializable because genres are stored as a JSON string in movies.genres
// (see MoviesRepository.ensureCached).
@Serializable
data class TmdbGenre(val id: Int, val name: String)

/** One actor of a movie's cast. */
data class TmdbCastMember(
    val id: Int,
    val name: String,
    val character: String?,
    val profilePath: String?
)

@Serializable
private data class TmdbPersonDetailDto(
    val id: Int,
    val name: String? = null,
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
    @SerialName("movie_credits") val movieCredits: TmdbPersonMovieCreditsDto? = null
)

@Serializable
private data class TmdbPersonMovieCreditsDto(val cast: List<TmdbPersonCastCreditDto> = emptyList())

@Serializable
private data class TmdbPersonCastCreditDto(
    val id: Int,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    val character: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val popularity: Double? = null
)

/** A person (actor or crew member) with their filmography. */
data class TmdbPersonDetail(
    val id: Int,
    val name: String,
    val biography: String?,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val profilePath: String?,
    val knownForDepartment: String?,
    val movieCredits: List<TmdbPersonMovieCredit>
)

/** One movie from a person's filmography. */
data class TmdbPersonMovieCredit(
    val tmdbId: Int,
    val title: String,
    val character: String?,
    val posterPath: String?,
    val releaseDate: String?,
    val voteAverage: Double?
)

/** A movie from a TMDB list response (search, popular, discover). */
data class TmdbMovieMatch(
    val id: Int?,
    val title: String,
    val originalTitle: String? = null,
    val releaseDate: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val popularity: Double? = null,
    val voteAverage: Double? = null,
    val genreIds: List<Int> = emptyList()
)


/**
 * Resolves the TMDB id of every scraped movie by searching TMDB for its original title (falling
 * back to the local title). Movies without a match keep `tmdbId = null`.
 */
suspend fun List<ScrapedMovie>.enrichWithTmdbIds(
    tmdbService: TmdbService
): List<ScrapedMovie> {
    val titleCache = mutableMapOf<String, Int?>()

    return this.map { movie ->
        val searchKey = (movie.originalTitle ?: movie.title).trim().lowercase()

        // The same title often appears in several cinemas - search TMDB only once per title.
        if (titleCache.containsKey(searchKey)) {
            return@map movie.copy(tmdbId = titleCache[searchKey])
        }

        val searchResults = try {
            tmdbService.searchMovies(movie.originalTitle ?: movie.title)
        } catch (e: Exception) {
            logger.error("Enrichment failed for ${movie.title}: ${e.message}")
            emptyList<TmdbMovieMatch>()
        }

        val foundId = searchResults.firstOrNull()?.id
        titleCache[searchKey] = foundId

        // Pause between searches to stay well below TMDB's rate limit.
        delay(300)

        movie.copy(tmdbId = foundId)
    }
}