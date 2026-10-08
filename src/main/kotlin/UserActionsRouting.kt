package com.milinko

import com.milinko.db.FriendshipsRepository
import com.milinko.db.MoviesRepository
import com.milinko.db.TasteProfileRepository
import com.milinko.db.User
import com.milinko.db.UserActionMovieRow
import com.milinko.db.UserActionsRepository
import com.milinko.db.UserDao
import com.milinko.db.decodeGenres
import com.milinko.services.TmdbService
import kotlinx.coroutines.launch
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("UserActionsRouting")

/**
 * Watchlist i "odgledano"/ocena (Faza 2, MOVIELENS_ROADMAP.md). Sve rute su iza JWT auth-a i
 * eksplicitno proveravaju da path {userId} odgovara JWT subject-u - Android ugovor šalje userId
 * kao path parametar, pa bez ove provere bi teoretski neko mogao da menja tuđu listu.
 */

@Serializable
data class WatchlistItemDto(
    @SerialName("tmdb_id") val tmdbId: Long,
    val title: String,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("added_at") val addedAt: Long,
    // Nema priority koncepta u stvarnoj šemi - uvek 0 (Android ga i dalje samo uslovno prikazuje).
    val priority: Int = 0,
    // Za filtere/sortiranje Biblioteke (žanr, godina, TMDB ocena) - videti MoviesTable.voteAverage.
    val genres: List<GenreDto> = emptyList(),
    @SerialName("release_year") val releaseYear: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null
)

@Serializable
data class WatchlistResponseDto(val items: List<WatchlistItemDto>)

@Serializable
data class AddToWatchlistRequest(val tmdbId: Int)

@Serializable
data class HistoryItemDto(
    @SerialName("tmdb_id") val tmdbId: Long,
    val title: String,
    @SerialName("poster_path") val posterPath: String? = null,
    // 0.0 = odgledano bez ocene (rating je null u bazi). Android prikazuje "X/10" bez posebne
    // provere za "nema ocene" - poznato pojednostavljenje za MVP, videti MOVIELENS_ROADMAP.md.
    @SerialName("user_rating") val userRating: Double = 0.0,
    // Kratak tekstualni komentar uz ocenu (16. avgust 2026) - opcion, nezavisan od userRating-a.
    val review: String? = null,
    @SerialName("watched_at") val watchedAt: Long,
    // Nema duration kolone u stvarnoj šemi - uvek 0 (Android ekran ga ni ne prikazuje).
    val duration: Int = 0,
    val genres: List<GenreDto> = emptyList(),
    @SerialName("release_year") val releaseYear: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null
)

@Serializable
data class HistoryResponseDto(val items: List<HistoryItemDto>)

@Serializable
data class AddToHistoryRequest(val tmdbId: Int, val rating: Double? = null, val review: String? = null, val duration: Int? = null)

// `review = null` znači "ne diraj postojeći komentar" (npr. brzi PUT samo za ocenu iz Library ->
// Istorija taba, gde review polje nije ni prikazano) - videti napomenu u
// UserActionsRepository.markWatched o null-vs-prazan-string razlici.
// `rating` je nullable (izmena u odnosu na raniju verziju) - podržava "napiši komentar bez ocene"
// (film je odgledan, ali korisnik namerno ne daje zvezdice, samo ostavlja utisak u tekstu).
@Serializable
data class UpdateHistoryRequest(val rating: Double? = null, val review: String? = null)

// Taste profile (Faza 4) - prosečna ocena po žanru, izvedeno direktno iz ocena bez "crne kutije".
@Serializable
data class GenreAffinityDto(
    @SerialName("genre_id") val genreId: Int,
    val genre: String,
    @SerialName("average_rating") val averageRating: Double,
    @SerialName("movie_count") val movieCount: Int
)

@Serializable
data class TasteProfileResponseDto(val genres: List<GenreAffinityDto>)

// Profil korisnika (Faza 7 redizajna) - GET/PUT /api/users/{userId}. Ranije nije postojalo na
// backendu (Android je imao Retrofit ugovor spreman, ali je uvek dobijao 404 - videti
// ProfileViewModel/SessionIdentityHeader fallback). username je jedinstven (kao Instagram handle),
// bio je slobodan kratak opis - oboje se menjaju preko PUT /profile (UserDao.updateProfile).
@Serializable
data class UserStatsDto(
    @SerialName("movies_watched") val moviesWatched: Int = 0,
    @SerialName("movies_rated") val moviesRated: Int = 0,
    @SerialName("friend_count") val friendCount: Int = 0
)

@Serializable
data class UserProfileDto(
    @SerialName("user_id") val userId: String,
    val username: String,
    val email: String,
    val avatar: String? = null,
    val bio: String? = null,
    @SerialName("favorite_genres") val favoriteGenres: List<String> = emptyList(),
    val stats: UserStatsDto = UserStatsDto()
)

// Android šalje ceo UserProfile objekat u PUT telu (postojeći Retrofit ugovor), ali ovde nam
// trebaju samo username/bio/avatar - ignoreUnknownKeys=true (Application.kt) tiho ignoriše ostatak
// (user_id/email/favorite_genres/stats) koji klijent ionako ne treba da menja ovom rutom.
// avatar je opcion: null znači "ne diraj postojeću sliku" (korisnik menja samo username/bio bez
// biranja nove slike) - da PUT bez izabrane slike ne obriše postojeći avatar.
@Serializable
data class UpdateProfileRequestDto(
    val username: String,
    val bio: String? = null,
    val avatar: String? = null
)

private fun LocalDateTime?.toEpochMillisOrNow(): Long {
    val localDateTime = this ?: Clock.System.now().toLocalDateTime(TimeZone.UTC)
    return localDateTime.toInstant(TimeZone.UTC).toEpochMilliseconds()
}

private fun UserActionMovieRow.genreDtos(): List<GenreDto> =
    decodeGenres(genresJson).map { GenreDto(it.id, it.name) }

private fun UserActionMovieRow.toWatchlistDto() = WatchlistItemDto(
    tmdbId = tmdbId,
    title = title,
    posterPath = posterPath,
    addedAt = createdAt.toEpochMillisOrNow(),
    genres = genreDtos(),
    releaseYear = releaseYear,
    voteAverage = voteAverage
)

private fun UserActionMovieRow.toHistoryDto() = HistoryItemDto(
    tmdbId = tmdbId,
    title = title,
    posterPath = posterPath,
    userRating = rating?.toDouble() ?: 0.0,
    review = review,
    watchedAt = createdAt.toEpochMillisOrNow(),
    genres = genreDtos(),
    releaseYear = releaseYear,
    voteAverage = voteAverage
)

// Koliko filmova sa nepotpunim kešom (bez žanrova/TMDB ocene) se dopuni po jednom učitavanju liste.
private const val BACKFILL_LIMIT_PER_REQUEST = 10

/**
 * Filmovi dodati pre nego što su `genres`/`vote_average` kolone postojale nemaju te podatke, pa
 * filteri Biblioteke na Androidu za njih ne bi radili. Umesto ručne migracije, svako učitavanje
 * liste u pozadini dopuni do [BACKFILL_LIMIT_PER_REQUEST] takvih filmova (ensureCached) - odgovor
 * se ne čeka na to; sledeće otvaranje Biblioteke već dobija dopunjene podatke.
 */
private fun Application.backfillMovieCache(
    rows: List<UserActionMovieRow>,
    moviesRepository: MoviesRepository,
    tmdbService: TmdbService
) {
    val missing = rows.filter { it.genresJson == null || it.voteAverage == null }.take(BACKFILL_LIMIT_PER_REQUEST)
    if (missing.isEmpty()) return
    launch {
        missing.forEach { row ->
            try {
                moviesRepository.ensureCached(row.tmdbId.toInt(), tmdbService)
            } catch (e: Exception) {
                logger.warn("Dopuna keša filma ${row.tmdbId} nije uspela: ${e.message}")
            }
        }
    }
}

private const val REVIEW_MAX_LENGTH = 1000

/** Zaokružuje na najbliži ceo broj i ograničava na [1,10] (DB CHECK opseg). Null ostaje null. */
internal fun Double?.toRatingIntOrNull(): Int? {
    if (this == null) return null
    return Math.round(this).toInt().coerceIn(1, 10)
}

fun Application.configureUserActionsRouting(
    tmdbService: TmdbService,
    userActionsRepository: UserActionsRepository,
    moviesRepository: MoviesRepository,
    tasteProfileRepository: TasteProfileRepository,
    userDao: UserDao,
    friendshipsRepository: FriendshipsRepository
) {
    routing {
        authenticate("auth-jwt") {
            route("/api/users/{userId}") {

                fun ApplicationCall.ownerUserIdOrNull(): String? {
                    val pathUserId = parameters["userId"] ?: return null
                    val tokenUserId = principal<JWTPrincipal>()?.payload?.subject
                    return if (pathUserId == tokenUserId) pathUserId else null
                }

                // Sastavlja UserProfileDto iz UserDao.User + izvedenih statistika (watchlist/history
                // rute su iznad, taste profile ispod - sve nezavisni pozivi, nema smisla dodavati
                // async/paralelizaciju za samo self-profil poziv niskog obima).
                suspend fun buildUserProfileDto(userId: String, user: User): UserProfileDto {
                    val history = userActionsRepository.getHistory(userId)
                    val friendCount = friendshipsRepository.getFriends(userId).size
                    val topGenres = tasteProfileRepository.getTasteProfile(userId)
                        .sortedByDescending { it.averageRating }
                        .take(3)
                        .map { it.genreName }
                    return UserProfileDto(
                        userId = user.id,
                        username = user.username ?: "Korisnik",
                        email = user.email ?: "",
                        avatar = user.avatarUrl,
                        bio = user.bio,
                        favoriteGenres = topGenres,
                        stats = UserStatsDto(
                            moviesWatched = history.size,
                            moviesRated = history.count { it.rating != null },
                            friendCount = friendCount
                        )
                    )
                }

                get {
                    val userId = call.ownerUserIdOrNull()
                        ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovom profilu"))
                    val user = userDao.findById(userId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Korisnik nije pronađen"))
                    call.respond(buildUserProfileDto(userId, user))
                }

                put("/profile") {
                    val userId = call.ownerUserIdOrNull()
                        ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovom profilu"))
                    val req = call.receive<UpdateProfileRequestDto>()

                    try {
                        val updated = userDao.updateProfile(userId, req.username, req.bio, req.avatar)
                        call.respond(buildUserProfileDto(userId, updated))
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Nevalidan zahtev"))
                    }
                }

                route("/watchlist") {
                    get {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val rows = userActionsRepository.getWatchlist(userId)
                        call.application.backfillMovieCache(rows, moviesRepository, tmdbService)
                        call.respond(WatchlistResponseDto(rows.map { it.toWatchlistDto() }))
                    }

                    post {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val req = call.receive<AddToWatchlistRequest>()

                        try {
                            moviesRepository.ensureCached(req.tmdbId, tmdbService)
                        } catch (e: IllegalArgumentException) {
                            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Nevalidan film"))
                        }

                        userActionsRepository.addToWatchlist(userId, req.tmdbId.toLong())
                        val item = userActionsRepository.getWatchlistItem(userId, req.tmdbId.toLong())
                            ?: return@post call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Neočekivana greška pri čuvanju"))
                        call.respond(item.toWatchlistDto())
                    }

                    delete("/{tmdbId}") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val tmdbId = call.parameters["tmdbId"]?.toLongOrNull()
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan tmdbId"))
                        userActionsRepository.removeFromWatchlist(userId, tmdbId)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                route("/history") {
                    get {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val rows = userActionsRepository.getHistory(userId)
                        call.application.backfillMovieCache(rows, moviesRepository, tmdbService)
                        call.respond(HistoryResponseDto(rows.map { it.toHistoryDto() }))
                    }

                    post {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val req = call.receive<AddToHistoryRequest>()

                        try {
                            moviesRepository.ensureCached(req.tmdbId, tmdbService)
                        } catch (e: IllegalArgumentException) {
                            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Nevalidan film"))
                        }

                        userActionsRepository.markWatched(
                            userId,
                            req.tmdbId.toLong(),
                            req.rating.toRatingIntOrNull(),
                            req.review?.trim()?.take(REVIEW_MAX_LENGTH)
                        )
                        val item = userActionsRepository.getHistoryItem(userId, req.tmdbId.toLong())
                            ?: return@post call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Neočekivana greška pri čuvanju"))
                        call.respond(item.toHistoryDto())
                    }

                    put("/{tmdbId}") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val tmdbId = call.parameters["tmdbId"]?.toLongOrNull()
                            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan tmdbId"))
                        val req = call.receive<UpdateHistoryRequest>()

                        // Bugfix (E1.1, 2. septembar 2026, feedback testera - "Update history failed: 404"
                        // pri izmeni ocene) - RANIJE je PUT vraćao 404 ako red ne postoji, oslanjajući se
                        // na to da je klijent UVEK prvo odradio POST /history. U praksi se lokalni
                        // (Room/uiState) i stvarni backend zapis znaju raspetljati - npr. keš na Movie
                        // Detail ekranu misli da je film već označen odgledanim (iz nekog ranijeg,
                        // možda neuspešnog POST-a, ili posle brisanja na drugom ekranu), pa
                        // MovieDetailViewModel.rateMovie šalje PUT umesto POST - i taj PUT je pucao. Sad
                        // je PUT samoisceljujući: ako red ne postoji, ponaša se kao POST (uključujući
                        // ensureCached, isto što POST grana radi) umesto da vrati grešku - "update" i
                        // "create" postaju efektivno isti idempotentni upsert iz klijentove perspektive.
                        val existing = userActionsRepository.getHistoryItem(userId, tmdbId)
                        if (existing == null) {
                            try {
                                moviesRepository.ensureCached(tmdbId.toInt(), tmdbService)
                            } catch (e: IllegalArgumentException) {
                                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Nevalidan film"))
                            }
                        }

                        userActionsRepository.markWatched(
                            userId,
                            tmdbId,
                            req.rating.toRatingIntOrNull(),
                            req.review?.trim()?.take(REVIEW_MAX_LENGTH)
                        )
                        val updated = userActionsRepository.getHistoryItem(userId, tmdbId)
                            ?: return@put call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Neočekivana greška pri čuvanju"))
                        call.respond(updated.toHistoryDto())
                    }

                    // "Undo" za slučajan (lapsus) klik na "Označi kao odgledano" - briše i ocenu
                    delete("/{tmdbId}") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val tmdbId = call.parameters["tmdbId"]?.toLongOrNull()
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan tmdbId"))
                        userActionsRepository.removeFromHistory(userId, tmdbId)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                // Faza 4 - prosečna ocena po žanru, izvedeno iz WATCHED+rated filmova. Samo za
                // sebe za sada (ownerUserIdOrNull) - poređenje sa prijateljem dolazi kasnije.
                get("/taste-profile") {
                    val userId = call.ownerUserIdOrNull()
                        ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovom profilu"))
                    val profile = tasteProfileRepository.getTasteProfile(userId)
                    call.respond(
                        TasteProfileResponseDto(
                            profile.map { GenreAffinityDto(it.genreId, it.genreName, it.averageRating, it.movieCount) }
                        )
                    )
                }
            }
        }
    }
}
