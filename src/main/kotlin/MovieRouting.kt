package com.milinko

import com.milinko.db.FriendshipsRepository
import com.milinko.db.GenreAffinity
import com.milinko.db.ScrapedMoviesRepository
import com.milinko.db.ShowtimesRepository
import com.milinko.db.TasteProfileRepository
import com.milinko.db.UserActionsRepository
import com.milinko.services.CinemaSemanticIndex
import com.milinko.services.GeminiOverloadedException
import com.milinko.services.OmdbService
import com.milinko.services.TmdbMovieMatch
import com.milinko.services.TmdbService
import com.milinko.util.TtlCache
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("MovieRouting")

// DTO field names (@SerialName) match what the Android app expects (Gson @SerializedName in
// MovieModels.kt).

@Serializable
data class MovieSearchResultDto(
    val id: Int,
    val title: String,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val overview: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList()
)

@Serializable
data class MovieListResponseDto(val results: List<MovieSearchResultDto>)

@Serializable
data class GenreDto(val id: Int, val name: String)

@Serializable
data class GenreListResponseDto(val genres: List<GenreDto>)

/** A YouTube trailer or teaser; `key` is the YouTube video id (played in the app's built-in player). */
@Serializable
data class MovieVideoDto(val key: String, val name: String, val type: String)

@Serializable
data class CastMemberDto(
    val id: Int,
    val name: String,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

@Serializable
data class CinemaShowtimeDto(
    @SerialName("cinema_name") val cinemaName: String,
    @SerialName("cinema_location") val cinemaLocation: String,
    @SerialName("booking_url") val bookingUrl: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("distance_km") val distanceKm: Double? = null,
    @SerialName("is_upcoming") val isUpcoming: Boolean = false,
    /** Screenings of the next days at this location, earliest first (empty when unknown). */
    val times: List<ShowtimeSlotDto> = emptyList()
)

/** One screening: local start time in Belgrade ("2026-10-15T19:00") and the ticket page of exactly it. */
@Serializable
data class ShowtimeSlotDto(
    @SerialName("starts_at") val startsAt: String,
    val hall: String? = null,
    val format: String? = null,
    @SerialName("booking_url") val bookingUrl: String? = null,
    @SerialName("price_rsd") val priceRsd: Int? = null
)

@Serializable
data class MovieDetailDto(
    val id: Int,
    val title: String,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val runtime: Int = 0,
    val genres: List<GenreDto> = emptyList(),
    val cast: List<CastMemberDto> = emptyList(),
    val showtimes: List<CinemaShowtimeDto> = emptyList(),
    // Extra ratings from OMDb; null if OMDB_API_KEY is not set, the movie has no IMDb id, or OMDb
    // has no data for it.
    @SerialName("imdb_rating") val imdbRating: Double? = null,
    @SerialName("rotten_tomatoes") val rottenTomatoes: String? = null,
    // "Explore more" section of the detail screen: IMDb link, trailers and backdrop gallery.
    @SerialName("imdb_id") val imdbId: String? = null,
    val videos: List<MovieVideoDto> = emptyList(),
    val backdrops: List<String> = emptyList()
)

/** Haversine distance in km between two points (the user and a cinema). */
internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusKm = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return earthRadiusKm * c
}

// With fewer rated movies the taste profile is too noisy to personalize /featured; such users get
// the generic popular movies, like users without any ratings.
private const val MIN_RATINGS_FOR_PERSONALIZATION = 3

// Movies per page of suggestions - the same as a TMDB page (20), so infinite scrolling on Home
// keeps working.
private const val FEATURED_PAGE_SIZE = 20

// Every genre gets at least this many slots, so the third (weakest) of the top genres does not
// disappear from the suggestions because of rounding.
private const val MIN_SLOTS_PER_GENRE = 2

// How many TMDB detail calls /now-in-cinemas may run at once (when the cache is empty).
private const val NOW_IN_CINEMAS_TMDB_PARALLELISM = 8

// How long a finished /now-in-cinemas response is served from memory (keyed by scrape time).
private const val NOW_IN_CINEMAS_CACHE_TTL_MS = 30 * 60 * 1000L

/**
 * Personalized suggestions from the user's top genres.
 *
 * Each genre is queried separately (instead of one OR query sorted only by global popularity) and
 * gets a number of slots proportional to the user's average rating for it, with at least
 * [MIN_SLOTS_PER_GENRE]. A movie can belong to several genres; `seen` prevents duplicates.
 * Movies the user has already watched ([excludeTmdbIds]) are skipped.
 */
internal suspend fun fetchBlendedByGenreAffinity(
    fetchGenrePage: suspend (genreId: Int, page: Int) -> List<TmdbMovieMatch>,
    genres: List<GenreAffinity>,
    page: Int,
    excludeTmdbIds: Set<Long>
): List<TmdbMovieMatch> {
    if (genres.isEmpty()) return emptyList()

    val totalWeight = genres.sumOf { it.averageRating }
    val remainingSlots = (FEATURED_PAGE_SIZE - MIN_SLOTS_PER_GENRE * genres.size).coerceAtLeast(0)
    val slotsByGenre = genres.map { genre ->
        val proportional = if (totalWeight > 0) ((genre.averageRating / totalWeight) * remainingSlots).toInt() else 0
        MIN_SLOTS_PER_GENRE + proportional
    }

    val seen = mutableSetOf<Long>()
    val result = mutableListOf<TmdbMovieMatch>()
    for ((index, genre) in genres.withIndex()) {
        val slots = slotsByGenre[index]
        // `seen` is filled only after take(): a movie that does not fit this genre's quota stays
        // available to the next genre.
        val fromGenre = fetchGenrePage(genre.genreId, page)
            .filter { it.id != null && it.id.toLong() !in excludeTmdbIds && it.id.toLong() !in seen }
            .take(slots)
        fromGenre.forEach { seen.add(it.id!!.toLong()) }
        result += fromGenre
    }
    return result
}

private fun TmdbMovieMatch.toDto() = MovieSearchResultDto(
    id = id ?: 0,
    title = title,
    releaseDate = releaseDate,
    posterPath = posterPath,
    voteAverage = voteAverage ?: 0.0,
    overview = overview,
    genreIds = genreIds
)

/**
 * A friend who watched the movie ("friends watched" badge on the detail screen). Served by a
 * separate JWT-protected route outside the public /api/movies block, because the answer depends on
 * who is asking.
 */
@Serializable
data class FriendWatchedDto(
    @SerialName("friend_id") val friendId: String,
    @SerialName("friend_name") val friendName: String,
    val rating: Int? = null,
    // The friend's optional note on their rating (same field as HistoryItemDto.review).
    val review: String? = null
)

@Serializable
data class FriendsWatchedResponseDto(val friends: List<FriendWatchedDto>)

/**
 * A movie in the current cinema programme with the cinemas showing it (Home strip, "In cinemas"
 * screen and semantic search results).
 */
@Serializable
data class NowInCinemasMovieDto(
    val id: Int,
    val title: String,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val cinemas: List<String> = emptyList(),
    // Only filled by /now-in-cinemas (genre filter, Now/Coming soon, description);
    // semantic search leaves the defaults.
    val genres: List<GenreDto> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
    val runtime: Int? = null,
    // True only if the movie is not playing anywhere yet (all scraped rows are "coming soon").
    @SerialName("is_upcoming") val isUpcoming: Boolean = false
)

@Serializable
data class NowInCinemasResponseDto(val results: List<NowInCinemasMovieDto>)

/**
 * One movie of a person's filmography. Unlike [CastMemberDto] (an actor seen from one movie),
 * `character` here is the role the person played in this movie.
 */
@Serializable
data class PersonMovieCreditDto(
    val id: Int,
    val title: String,
    val character: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null
)

@Serializable
data class PersonDetailDto(
    val id: Int,
    val name: String,
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
    @SerialName("movie_credits") val movieCredits: List<PersonMovieCreditDto> = emptyList()
)

/**
 * Public movie routes (no sign-in needed, because browsing works without an account): search,
 * trending, personalized featured (optional sign-in), genres, cinema programme, semantic search,
 * movie details and person details. Also the JWT-protected "friends watched" route.
 */
fun Application.configureMovieRouting(
    tmdbService: TmdbService,
    friendshipsRepository: FriendshipsRepository,
    userActionsRepository: UserActionsRepository,
    tasteProfileRepository: TasteProfileRepository,
    scrapedMoviesRepository: ScrapedMoviesRepository,
    geminiCinemaSemanticIndex: CinemaSemanticIndex,
    localCinemaSemanticIndex: CinemaSemanticIndex,
    omdbService: OmdbService,
    showtimesRepository: ShowtimesRepository = ShowtimesRepository()
) {
    // Finished /now-in-cinemas response. Without it, every request (Home calls it on every app
    // start) would make ~100 TMDB detail calls once the TMDB cache expires. The key is the time of
    // the last scrape, so new programme data automatically gets a new key.
    val nowInCinemasCache = TtlCache<String, NowInCinemasResponseDto>(ttlMillis = NOW_IN_CINEMAS_CACHE_TTL_MS, maxSize = 4)

    routing {
        rateLimit(RATE_LIMIT_PUBLIC) {
            route("/api/movies") {
                get("/search") {
                    val query = call.request.queryParameters["query"]?.trim().orEmpty()
                    if (query.isEmpty()) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Query parametar je obavezan"))
                    }
                    try {
                        val results = tmdbService.searchMovies(query)
                        call.respond(MovieListResponseDto(results.map { it.toDto() }))
                    } catch (e: Exception) {
                        call.respondUpstreamError("/api/movies/search", e, "TMDB pretraga nije uspela")
                    }
                }

                get("/trending") {
                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                    try {
                        val results = tmdbService.getPopularMovies(page)
                        call.respond(MovieListResponseDto(results.map { it.toDto() }))
                    } catch (e: Exception) {
                        call.respondUpstreamError("/api/movies/trending", e, "TMDB trending nije uspeo")
                    }
                }

            // Personalized only for the user from the JWT (optional auth: without a token the route
            // returns popular movies). The `userId` query parameter older app versions still send
            // is ignored - otherwise anyone knowing someone's id could read their suggestions, which
            // partly reveal their watch history.
                authenticate("auth-jwt", optional = true) {
                    get("/featured") {
                        val userId = call.principal<JWTPrincipal>()?.payload?.subject?.takeIf { it.isNotBlank() }
                        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                        try {
                            val personalized = userId?.let { uid ->
                                val history = userActionsRepository.getHistory(uid)
                                val totalRated = history.count { it.rating != null }
                                if (totalRated < MIN_RATINGS_FOR_PERSONALIZATION) {
                                    null
                                } else {
                                    val topGenres = tasteProfileRepository.getTasteProfile(uid).take(3)
                                    if (topGenres.isEmpty()) {
                                        null
                                    } else {

                                        val alreadyWatched = history.map { it.tmdbId }.toSet()
                                        fetchBlendedByGenreAffinity(
                                            { genreId, p -> tmdbService.getMoviesByGenres(listOf(genreId), p) },
                                            topGenres, page, alreadyWatched
                                        )
                                            .takeIf { it.isNotEmpty() }
                                    }
                                }
                            }
                            val results = personalized ?: tmdbService.getPopularMovies(page)
                            call.respond(MovieListResponseDto(results.map { it.toDto() }))
                        } catch (e: Exception) {
                            call.respondUpstreamError("/api/movies/featured", e, "TMDB featured nije uspeo")
                        }
                    }
                }

                get("/genres") {
                    try {
                        val genres = tmdbService.getGenres()
                        call.respond(GenreListResponseDto(genres.map { GenreDto(it.id, it.name) }))
                    } catch (e: Exception) {
                        call.respondUpstreamError("/api/movies/genres", e, "TMDB žanrovi nisu dostupni")
                    }
                }

            // The whole programme (the Home strip takes the first 20 itself). TMDB details are
            // cached for an hour, but with an empty cache this is one call per movie, so a
            // semaphore limits parallelism because of TMDB's rate limit.
                get("/now-in-cinemas") {
                    try {
                        val cacheKey = scrapedMoviesRepository.getLatestScrapeTimestamp()?.toString() ?: "empty"
                        nowInCinemasCache.get(cacheKey)?.let { return@get call.respond(it) }

                        val scraped = scrapedMoviesRepository.getAllScrapedMovies()

                        val byTmdbId = scraped.filter { it.tmdbId != null }.groupBy { it.tmdbId!! }
                        val tmdbLimiter = Semaphore(NOW_IN_CINEMAS_TMDB_PARALLELISM)

                        val results = coroutineScope {
                            byTmdbId.entries.map { (tmdbId, rows) ->
                                async {
                                    val detail = try {
                                        tmdbLimiter.withPermit { tmdbService.getMovieDetails(tmdbId) }
                                    } catch (e: Exception) {
                                        null
                                    } ?: return@async null
                                    NowInCinemasMovieDto(
                                        id = detail.id,
                                        title = detail.title,
                                        posterPath = detail.posterPath,
                                        voteAverage = detail.voteAverage ?: 0.0,
                                        cinemas = rows.map { it.cinemaName }.distinct().sorted(),
                                        genres = detail.genres.map { GenreDto(it.id, it.name) },
                                        releaseDate = detail.releaseDate,
                                        overview = detail.overview,
                                        runtime = detail.runtime,
                                        isUpcoming = rows.all { it.isUpcoming }
                                    )
                                }
                            }.awaitAll().filterNotNull()
                        }.sortedWith(
                        // Playing now first, then by number of cinemas (shown more widely = more relevant).
                            compareBy<NowInCinemasMovieDto> { it.isUpcoming }
                                .thenByDescending { it.cinemas.size }
                        )
                        val response = NowInCinemasResponseDto(results)
                    // Do not cache a response missing most movies (e.g. TMDB failed mid-request);
                    // the next request should try again instead of serving a short list for 30 minutes.
                        if (results.size >= byTmdbId.size / 2) nowInCinemasCache.put(cacheKey, response)
                        call.respond(response)
                    } catch (e: Exception) {
                        call.respondUpstreamError("/api/movies/now-in-cinemas", e, "Repertoar trenutno nije dostupan")
                    }
                }


                rateLimit(RATE_LIMIT_SEMANTIC) {
                    get("/now-in-cinemas/semantic-search") {
                        val query = call.request.queryParameters["q"]?.trim().orEmpty()
                        if (query.isEmpty()) {
                            return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Query parametar 'q' je obavezan"))
                        }
                        val engine = call.request.queryParameters["engine"]?.trim()?.lowercase().orEmpty()
                        val index = if (engine == "local") localCinemaSemanticIndex else geminiCinemaSemanticIndex
                        if (!index.isReady()) {
                            return@get call.respond(NowInCinemasResponseDto(emptyList()))
                        }
                        // Only the Gemini engine costs quota (every query is embedded by Gemini).
                        if (index === geminiCinemaSemanticIndex) {
                            val quota = geminiQuota.tryAcquire(GeminiFeature.SEMANTIC, call.clientIp())
                            if (quota != GeminiQuotaResult.OK) {
                                logger.warn("Semantic search refused: $quota")
                                return@get call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(quota.message()))
                            }
                        }
                        try {
                            val matches = index.searchByQuery(query)
                            call.respond(
                                NowInCinemasResponseDto(
                                    matches.map { entry ->
                                        NowInCinemasMovieDto(
                                            id = entry.tmdbId,
                                            title = entry.title,
                                            posterPath = entry.posterPath,
                                            voteAverage = entry.voteAverage,
                                            cinemas = entry.cinemas
                                        )
                                    }
                                )
                            )
                        } catch (e: GeminiOverloadedException) {

                            logger.error("Gemini overloaded (semantic-search): ${e.message}")
                            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Gemini je trenutno preopterećen. Pokušaj ponovo za par trenutaka."))
                        } catch (e: Exception) {
                            call.respondUpstreamError("/api/movies/now-in-cinemas/semantic-search", e, "Semantička pretraga nije uspela")
                        }
                    }
                }

                get("/{tmdbId}") {
                    val tmdbId = call.parameters["tmdbId"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan tmdbId"))

                    val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
                    val lon = call.request.queryParameters["lon"]?.toDoubleOrNull()
                    try {
                        val detail = tmdbService.getMovieDetails(tmdbId)
                            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Film nije pronađen"))

                        val showtimeRows = scrapedMoviesRepository.getShowtimesForMovie(tmdbId)
                        // Times are optional: without them the cinemas are still listed as before.
                        val slotsByLocation = try {
                            showtimesRepository.upcomingForMovie(tmdbId).groupBy { it.cinemaLocation }
                        } catch (e: Exception) {
                            logger.warn("Termini nisu učitani za tmdbId=$tmdbId: ${e.message}")
                            emptyMap()
                        }
                        // One card per location: versions of the same movie (e.g. 2D and 3D, subtitled
                        // and dubbed) are separate programme rows with the same TMDB id.
                        var showtimes = showtimeRows.groupBy { it.cinemaName to it.cinemaLocation }.map { (_, rows) ->
                            val row = rows.firstOrNull { !it.isUpcoming } ?: rows.first()
                            val distanceKm = if (lat != null && lon != null && row.latitude != null && row.longitude != null) {
                                haversineKm(lat, lon, row.latitude, row.longitude)
                            } else {
                                null
                            }
                            CinemaShowtimeDto(
                                cinemaName = row.cinemaName,
                                cinemaLocation = row.cinemaLocation,
                                bookingUrl = row.bookingUrl,
                                latitude = row.latitude,
                                longitude = row.longitude,
                                distanceKm = distanceKm,
                                isUpcoming = rows.all { it.isUpcoming },
                                times = slotsByLocation[row.cinemaLocation].orEmpty().map { slot ->
                                    ShowtimeSlotDto(
                                        startsAt = slot.startsAt.toString(),
                                        hall = slot.hall,
                                        format = slot.format,
                                        bookingUrl = slot.bookingUrl,
                                        priceRsd = slot.priceRsd
                                    )
                                }
                            )
                        }
                        // Nearest first when the location is known; otherwise the earliest screening first.
                        showtimes = if (lat != null && lon != null) {
                            showtimes.sortedBy { it.distanceKm ?: Double.MAX_VALUE }
                        } else {
                            showtimes.sortedBy { it.times.firstOrNull()?.startsAt ?: "~" }
                        }

                    // Extra ratings (IMDb/Rotten Tomatoes) come from a separate call that must not
                    // break the rest of the response if OMDb fails.
                        val omdbRatings = try {
                            omdbService.getRatings(detail.imdbId)
                        } catch (e: Exception) {
                            logger.warn("OMDb obogaćivanje nije uspelo za tmdbId=$tmdbId: ${e.message}")
                            null
                        }

                        call.respond(
                            MovieDetailDto(
                                id = detail.id,
                                title = detail.title,
                                releaseDate = detail.releaseDate,
                                posterPath = detail.posterPath,
                                backdropPath = detail.backdropPath,
                                overview = detail.overview,
                                voteAverage = detail.voteAverage ?: 0.0,
                                runtime = detail.runtime ?: 0,
                                genres = detail.genres.map { GenreDto(it.id, it.name) },
                                cast = detail.cast.map { CastMemberDto(it.id, it.name, it.character, it.profilePath) },
                                showtimes = showtimes,
                                imdbRating = omdbRatings?.imdbRating,
                                rottenTomatoes = omdbRatings?.rottenTomatoesPercent,
                                imdbId = detail.imdbId,
                                videos = detail.videos.map { MovieVideoDto(it.key, it.name, it.type) },
                                backdrops = detail.backdrops
                            )
                        )
                    } catch (e: Exception) {
                        call.respondUpstreamError("/api/movies/$tmdbId", e, "TMDB detalji nisu uspeli")
                    }
                }
            }

        // Person page (actor/crew). Public, like /api/movies/{tmdbId}.
            route("/api/people") {
                get("/{id}") {
                    val personId = call.parameters["id"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan id"))
                    try {
                        val person = tmdbService.getPersonDetails(personId)
                            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Glumac nije pronađen"))

                        call.respond(
                            PersonDetailDto(
                                id = person.id,
                                name = person.name,
                                biography = person.biography,
                                birthday = person.birthday,
                                deathday = person.deathday,
                                placeOfBirth = person.placeOfBirth,
                                profilePath = person.profilePath,
                                knownForDepartment = person.knownForDepartment,
                                movieCredits = person.movieCredits.map {
                                    PersonMovieCreditDto(
                                        id = it.tmdbId,
                                        title = it.title,
                                        character = it.character,
                                        posterPath = it.posterPath,
                                        releaseDate = it.releaseDate,
                                        voteAverage = it.voteAverage
                                    )
                                }
                            )
                        )
                    } catch (e: Exception) {
                        call.respondUpstreamError("/api/people/$personId", e, "TMDB podaci o glumcu nisu uspeli")
                    }
                }
            }
        }

        authenticate("auth-jwt") {
            get("/api/movies/{tmdbId}/friends-watched") {
                val callerId = call.principal<JWTPrincipal>()?.payload?.subject
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val tmdbId = call.parameters["tmdbId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan tmdbId"))

                val friendIds = friendshipsRepository.getFriendIds(callerId)
                val watched = userActionsRepository.getFriendsWhoWatched(friendIds, tmdbId)
                call.respond(
                    FriendsWatchedResponseDto(
                        watched.map { FriendWatchedDto(it.friendId, it.friendUsername ?: "Korisnik", it.rating, it.review) }
                    )
                )
            }
        }
    }
}
