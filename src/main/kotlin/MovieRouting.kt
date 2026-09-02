package com.milinko

import com.milinko.db.FriendshipsRepository
import com.milinko.db.GenreAffinity
import com.milinko.db.ScrapedMoviesRepository
import com.milinko.db.TasteProfileRepository
import com.milinko.db.UserActionsRepository
import com.milinko.services.CinemaSemanticIndex
import com.milinko.services.GeminiOverloadedException
import com.milinko.services.TmdbMovieMatch
import com.milinko.services.TmdbService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val overview: String? = null
)

@Serializable
data class MovieListResponseDto(val results: List<MovieSearchResultDto>)

@Serializable
data class GenreDto(val id: Int, val name: String)

@Serializable
data class CastMemberDto(
    val id: Int,
    val name: String,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

// Bioskopski termin za dati film (Movie Detail - "Bioskopski termini" sekcija). distanceKm je
// null ako klijent nije poslao lat/lon (korisnik nije dao dozvolu za lokaciju) - u tom slučaju
// lista nije sortirana po udaljenosti, samo u redosledu iz baze.
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
    val showtimes: List<CinemaShowtimeDto> = emptyList()
)

/** Haversine formula - distanca u km između dve geo-tačke (korisnik i bioskop). */
private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
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

/**
 * Umesto jednog "OR" TMDB discover upita preko sva 3 top žanra (sortiranog isključivo po globalnoj
 * TMDB popularnosti - žanr koji korisnik "malo" voli i žanr koji "mnogo" voli su ranije dobijali
 * podjednak broj mesta), svaki žanr se pretražuje POSEBNO. Broj filmova uzet iz svakog žanra je
 * srazmeran njegovoj prosečnoj oceni (jači afinitet -> više mesta), uz `MIN_SLOTS_PER_GENRE` da i
 * treći/najslabiji žanr ostane vidljivo zastupljen. Isti film može da se pojavi u rezultatima više
 * žanrova (npr. film koji je i Akcija i Triler) - `seen` sprečava duplikate u finalnoj listi.
 */
private suspend fun fetchBlendedByGenreAffinity(
    tmdbService: TmdbService,
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
        val fromGenre = tmdbService.getMoviesByGenres(listOf(genre.genreId), page)
            .filter { it.id != null && it.id.toLong() !in excludeTmdbIds && seen.add(it.id.toLong()) }
            .take(slots)
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
    overview = overview
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
    val cinemas: List<String> = emptyList()
)

@Serializable
data class NowInCinemasResponseDto(val results: List<NowInCinemasMovieDto>)

fun Application.configureMovieRouting(
    tmdbService: TmdbService,
    friendshipsRepository: FriendshipsRepository,
    userActionsRepository: UserActionsRepository,
    tasteProfileRepository: TasteProfileRepository,
    scrapedMoviesRepository: ScrapedMoviesRepository,
    geminiCinemaSemanticIndex: CinemaSemanticIndex,
    localCinemaSemanticIndex: CinemaSemanticIndex
) {
    routing {
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
                    println("[ERROR] /api/movies/search: ${e.message}")
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "TMDB pretraga nije uspela"))
                }
            }

            get("/trending") {
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                try {
                    val results = tmdbService.getPopularMovies(page)
                    call.respond(MovieListResponseDto(results.map { it.toDto() }))
                } catch (e: Exception) {
                    println("[ERROR] /api/movies/trending: ${e.message}")
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "TMDB trending nije uspeo"))
                }
            }

            get("/featured") {
                // Faza 4 (MOVIELENS_ROADMAP.md): ako je userId prosleđen i korisnik ima dovoljno
                // ocenjenih filmova da postoji taste profile, vraćamo TMDB discover po njegova 3
                // najomiljenija žanra umesto generičkih popularnih filmova. Bez userId-a, praznog
                // profila (nov korisnik), premalo ocena, ili ako discover ne vrati ništa - fallback
                // na popularne, isto ponašanje kao ranije (nikad prazan ekran).
                //
                // Dorada kvaliteta predloga (16. avgust 2026, korisnička žalba "nekad gađa dobro, a
                // nekad baš brlja"): (1) dodat MIN_RATINGS_FOR_PERSONALIZATION prag - sa 1-2 ocene
                // profil je previše šumovit da bi mu se verovalo, bolje generički popularni dok
                // korisnik ne oceni bar par filmova; (2) ranije se sva 3 top žanra tražila JEDNIM
                // "OR" upitom sortiranim isključivo po globalnoj TMDB popularnosti - žanr koji
                // korisnik "malo" voli i žanr koji "mnogo" voli dobijali su podjednak broj mesta u
                // predlogu. Sad se svaki žanr traži POSEBNO i meša se srazmerno afinitetu (videti
                // fetchBlendedByGenreAffinity ispod).
                val userId = call.request.queryParameters["userId"]?.trim()?.takeIf { it.isNotBlank() }
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
                                // Filmovi koje je korisnik već odgledao/ocenio su upravo ono što je
                                // gurnulo ovaj žanr na vrh profila - bez ovog filtera bi se stalno
                                // vraćali kao "predlog" isti filmovi koje je korisnik već video.
                                val alreadyWatched = history.map { it.tmdbId }.toSet()
                                fetchBlendedByGenreAffinity(tmdbService, topGenres, page, alreadyWatched)
                                    .takeIf { it.isNotEmpty() }
                            }
                        }
                    }
                    val results = personalized ?: tmdbService.getPopularMovies(page)
                    call.respond(MovieListResponseDto(results.map { it.toDto() }))
                } catch (e: Exception) {
                    println("[ERROR] /api/movies/featured: ${e.message}")
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "TMDB featured nije uspeo"))
                }
            }

            get("/now-in-cinemas") {
                try {
                    val scraped = scrapedMoviesRepository.getAllScrapedMovies()
                    // Isti film se u repertoaru pojavljuje jednom po bioskopu/sali - grupišemo po
                    // tmdbId (samo redovi koji su uspešno povezani sa TMDB-om tokom skrapovanja).
                    val byTmdbId = scraped.filter { it.tmdbId != null }.groupBy { it.tmdbId!! }

                    val results = coroutineScope {
                        byTmdbId.entries.take(20).map { (tmdbId, rows) ->
                            async {
                                val detail = try {
                                    tmdbService.getMovieDetails(tmdbId)
                                } catch (e: Exception) {
                                    null
                                } ?: return@async null
                                NowInCinemasMovieDto(
                                    id = detail.id,
                                    title = detail.title,
                                    posterPath = detail.posterPath,
                                    voteAverage = detail.voteAverage ?: 0.0,
                                    cinemas = rows.map { it.cinemaName }.distinct().sorted()
                                )
                            }
                        }.awaitAll().filterNotNull()
                    }
                    call.respond(NowInCinemasResponseDto(results))
                } catch (e: Exception) {
                    println("[ERROR] /api/movies/now-in-cinemas: ${e.message}")
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "Repertoar trenutno nije dostupan"))
                }
            }

            // Faza D (17. avgust 2026, predlog mentora Marka) - semantička pretraga, namerno
            // svedena na filmove trenutno na repertoaru (videti CinemaSemanticIndex - obrazloženje
            // obima). Ista NowInCinemasResponseDto forma kao /now-in-cinemas gore - Android klijent
            // ne treba nov model, samo drugačiji izvor podataka.
            //
            // Faza D dorada (poređenje Gemini vs. lokalni MiniLM) - opcioni `engine` query parametar
            // bira koji od dva paralelna indeksa se koristi. Podrazumevano "gemini" - postojeći
            // Android klijent (pre ove dorade) ne šalje ovaj parametar, pa mora nastaviti da radi
            // identično kao pre.
            get("/now-in-cinemas/semantic-search") {
                val query = call.request.queryParameters["q"]?.trim().orEmpty()
                if (query.isEmpty()) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Query parametar 'q' je obavezan"))
                }
                val engine = call.request.queryParameters["engine"]?.trim()?.lowercase().orEmpty()
                val index = if (engine == "local") localCinemaSemanticIndex else geminiCinemaSemanticIndex
                if (!index.isReady()) {
                    // Indeks se puni asinhrono pri startu servera (uz dnevno skrejpovanje) - ako
                    // upit stigne pre nego što se prvi refresh završi, vraćamo praznu listu umesto
                    // greške (klijent to prikazuje kao "nema rezultata", ne kao pad mreže).
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
                    // Bugfix (2. septembar 2026) - videti napomenu uz isti catch u Routing.kt
                    // (/chat/vision) - isti problem, isti fix, samo za semantičku pretragu.
                    println("[ERROR] Gemini overloaded (semantic-search): ${e.message}")
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Gemini je trenutno preopterećen. Pokušaj ponovo za par trenutaka."))
                } catch (e: Exception) {
                    println("[ERROR] /api/movies/now-in-cinemas/semantic-search: ${e.message}")
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "Semantička pretraga nije uspela"))
                }
            }

            get("/{tmdbId}") {
                val tmdbId = call.parameters["tmdbId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan tmdbId"))
                // Opciono - Android šalje samo ako je korisnik dao dozvolu za lokaciju. Kad su
                // prisutni, bioskopi se sortiraju po udaljenosti; inače ostaju u redosledu iz baze.
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
                            showtimes = showtimes
                        )
                    )
                } catch (e: Exception) {
                    println("[ERROR] /api/movies/$tmdbId: ${e.message}")
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "TMDB detalji nisu uspeli"))
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
