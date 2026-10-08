package com.milinko

import com.milinko.db.FriendshipsRepository
import com.milinko.db.GenreAffinity
import com.milinko.db.ScrapedMoviesRepository
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

/**
 * Javne rute za pretragu/detalje/trending filmova (Faza 1 iz MOVIELENS_ROADMAP.md).
 * Namerno BEZ authenticate("auth-jwt") — Android app dozvoljava pregled filmova bez prijave,
 * prijava je potrebna samo za skeniranje slike i lične akcije (watchlist/ocene, Faza 2+).
 *
 * DTO-ovi ispod su namerno oblikovani (nazivi JSON polja preko @SerialName) da tačno prate
 * ono što Android MovieModels.kt (Gson @SerializedName) već očekuje — kod na klijentu se ne dira.
 */

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

// YouTube trejler/teaser - `key` je YouTube video ID (Android ga pušta u ugrađenom plejeru).
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
    @SerialName("is_upcoming") val isUpcoming: Boolean = false
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
    // Dopunske ocene sa OMDb-a (3. septembar 2026) - null ako OMDB_API_KEY nije podešen na
    // serveru, film nema imdb_id, ili OMDb nema podatke za dati film (videti OmdbService).
    @SerialName("imdb_rating") val imdbRating: Double? = null,
    @SerialName("rotten_tomatoes") val rottenTomatoes: String? = null,
    // "Istraži više" sekcija na Movie Detail-u: link ka IMDb-u, trejleri i galerija backdrop-ova.
    @SerialName("imdb_id") val imdbId: String? = null,
    val videos: List<MovieVideoDto> = emptyList(),
    val backdrops: List<String> = emptyList()
)

/** Haversine formula - distanca u km između dve geo-tačke (korisnik i bioskop). */
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

// Manje od ovoliko ocenjenih filmova = profil je previše šumovit da bi mu se verovalo za
// personalizaciju /featured (videti komentar na /featured ruti). Ispod praga se vraćaju generički
// popularni filmovi, isto kao za korisnika bez ijedne ocene.
private const val MIN_RATINGS_FOR_PERSONALIZATION = 3

// Ciljni broj filmova po "stranici" predloga - isto koliko TMDB inače vraća po stranici (20), da
// beskonačan skrol na Home-u nastavi da radi neprimetno kao i pre ove izmene.
private const val FEATURED_PAGE_SIZE = 20

// Svaki žanr dobija bar ovoliko mesta u predlogu, čak i onaj sa najslabijim afinitetom od top 3 -
// sprečava da treći žanr praktično nestane iz predloga zbog zaokruživanja.
private const val MIN_SLOTS_PER_GENRE = 2

// Koliko TMDB detalj poziva /now-in-cinemas sme da radi istovremeno (pri praznom kešu).
private const val NOW_IN_CINEMAS_TMDB_PARALLELISM = 8

// Koliko dugo se gotov /now-in-cinemas odgovor služi iz memorije (ključ je vreme scrape-a).
private const val NOW_IN_CINEMAS_CACHE_TTL_MS = 30 * 60 * 1000L

/**
 * Umesto jednog "OR" TMDB discover upita preko sva 3 top žanra (sortiranog isključivo po globalnoj
 * TMDB popularnosti - žanr koji korisnik "malo" voli i žanr koji "mnogo" voli su ranije dobijali
 * podjednak broj mesta), svaki žanr se pretražuje POSEBNO. Broj filmova uzet iz svakog žanra je
 * srazmeran njegovoj prosečnoj oceni (jači afinitet -> više mesta), uz `MIN_SLOTS_PER_GENRE` da i
 * treći/najslabiji žanr ostane vidljivo zastupljen. Isti film može da se pojavi u rezultatima više
 * žanrova (npr. film koji je i Akcija i Triler) - `seen` sprečava duplikate u finalnoj listi.
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
        // `seen` se puni tek posle take(): film koji ne uđe u kvotu ovog žanra ostaje dostupan
        // sledećem žanru.
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

// "Prijatelji su gledali ovaj film" bedž na Movie Detail-u - namerno posebna, JWT-zaštićena ruta
// izvan /api/movies bloka ispod (koji ostaje javan/bez logina), jer zavisi od toga ko pita.
@Serializable
data class FriendWatchedDto(
    @SerialName("friend_id") val friendId: String,
    @SerialName("friend_name") val friendName: String,
    val rating: Int? = null,
    // Kratak tekstualni komentar prijatelja uz ocenu (16. avgust 2026, korisnička žalba: komentari
    // se nisu prikazivali NIGDE, ni sopstvenom korisniku ni prijateljima) - isto polje kao
    // HistoryItemDto.review.
    val review: String? = null
)

@Serializable
data class FriendsWatchedResponseDto(val friends: List<FriendWatchedDto>)

// "U bioskopima sada" traka na Home-u - iskorišćava postojeći skraping repertoara
// (ScrapedMoviesRepository, do sad korišćen samo interno za /chat/vision cross-check), grupisano
// po tmdbId sa listom bioskopa gde se film trenutno prikazuje.
@Serializable
data class NowInCinemasMovieDto(
    val id: Int,
    val title: String,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val cinemas: List<String> = emptyList(),
    // Polja za "U bioskopima" ekran (filteri po žanru i Sada/Uskoro, prikaz sa opisom) - popunjena
    // samo u /now-in-cinemas; semantic-search ih ostavlja podrazumevanim.
    val genres: List<GenreDto> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
    val runtime: Int? = null,
    // true samo ako film NIGDE još ne igra (svi skrapovani redovi su "uskoro").
    @SerialName("is_upcoming") val isUpcoming: Boolean = false
)

@Serializable
data class NowInCinemasResponseDto(val results: List<NowInCinemasMovieDto>)

// E2.3 (2. septembar 2026) - stranica glumca. `character` je ovde (za razliku od CastMemberDto
// gore koji opisuje glumca IZ UGLA jednog filma) uloga koju je glumac igrao u SVAKOM filmu iz
// njegove filmografije - otud posebna, "obrnuta" DTO forma sa listom filmova umesto liste glumaca.
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

fun Application.configureMovieRouting(
    tmdbService: TmdbService,
    friendshipsRepository: FriendshipsRepository,
    userActionsRepository: UserActionsRepository,
    tasteProfileRepository: TasteProfileRepository,
    scrapedMoviesRepository: ScrapedMoviesRepository,
    geminiCinemaSemanticIndex: CinemaSemanticIndex,
    localCinemaSemanticIndex: CinemaSemanticIndex,
    omdbService: OmdbService
) {
    // Gotov /now-in-cinemas odgovor - bez ovoga je svaki zahtev (Home ga zove pri svakom pokretanju
    // aplikacije) posle isteka TMDB keša pravio ~100 TMDB detalj poziva. Ključ je vreme poslednjeg
    // scrape-a: novi upis repertoara automatski daje novi ključ, pa keš nikad ne kasni za bazom.
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

                // Personalizacija ISKLJUČIVO za korisnika iz JWT-a (opciona autentikacija: bez tokena ruta
                // radi i za neulogovane i vraća popularne filmove). Ranije je userId stizao kao javni query
                // parametar - svako ko zna nečiji ID mogao je da dobije njegove predloge (iz kojih su
                // izbačeni već gledani filmovi, tj. delimično otkrivaju istoriju gledanja). Query parametar
                // `userId` koji stari/postojeći Android klijent i dalje šalje se ignoriše - AuthInterceptor
                // ionako dodaje Bearer token uz svaki zahtev ulogovanog korisnika.
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

                // Ceo repertoar (ranije samo prvih 20 - Home traka sad sama uzima 20, a "U bioskopima"
                // ekran prikazuje sve). TMDB detalji su keširani 1h, ali pri praznom kešu ovo je po jedan
                // poziv po filmu - Semaphore ograničava paralelizam zbog TMDB rate limita.
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
                            // Prvo ono što sada igra, pa po broju bioskopa (šire prikazivano = relevantnije).
                            compareBy<NowInCinemasMovieDto> { it.isUpcoming }
                                .thenByDescending { it.cinemas.size }
                        )
                        val response = NowInCinemasResponseDto(results)
                        // Ne keširamo odgovor kojem fali većina filmova (npr. TMDB je pao usred zahteva) -
                        // sledeći zahtev treba da pokuša ponovo, a ne da 30 minuta vraća krnju listu.
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
                        var showtimes = showtimeRows.map { row ->
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
                                isUpcoming = row.isUpcoming
                            )
                        }
                        if (lat != null && lon != null) {
                            showtimes = showtimes.sortedBy { it.distanceKm ?: Double.MAX_VALUE }
                        }

                        // Dopunske ocene (IMDb/Rotten Tomatoes) - odvojen poziv od TMDB detalja, ne
                        // sme oboriti ostatak odgovora ako OMDb padne (videti OmdbService.getRatings).
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

            // E2.3 (2. septembar 2026) - stranica glumca. Javna ruta, isti obrazac kao
            // /api/movies/{tmdbId} iznad (bez authenticate - pregled glumca ne zahteva prijavu).
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
