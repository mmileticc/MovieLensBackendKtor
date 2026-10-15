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
import com.google.firebase.auth.FirebaseAuth
import com.milinko.db.AccountDeletion

private val logger = LoggerFactory.getLogger("UserActionsRouting")

// DTO field names match the Android models (Gson @SerializedName).

@Serializable
data class WatchlistItemDto(
    @SerialName("tmdb_id") val tmdbId: Long,
    val title: String,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("added_at") val addedAt: Long,
    // No priority in the schema - always 0.
    val priority: Int = 0,
    // For the Library filters and sorting (genre, year, TMDB rating).
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
    // 0.0 = watched without a rating (rating is null in the database).
    @SerialName("user_rating") val userRating: Double = 0.0,
    // Optional note attached to the rating.
    val review: String? = null,
    @SerialName("watched_at") val watchedAt: Long,
    // No duration column in the schema - always 0.
    val duration: Int = 0,
    val genres: List<GenreDto> = emptyList(),
    @SerialName("release_year") val releaseYear: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null
)

@Serializable
data class HistoryResponseDto(val items: List<HistoryItemDto>)

@Serializable
data class AddToHistoryRequest(val tmdbId: Int, val rating: Double? = null, val review: String? = null, val duration: Int? = null)

// `review = null` means "keep the existing note" (e.g. a quick rating change from the Library,
// where the note is not shown); `rating = null` means "keep the existing rating". See
// UserActionsRepository.markWatched.
@Serializable
data class UpdateHistoryRequest(val rating: Double? = null, val review: String? = null)

// Taste profile: average rating per genre, derived directly from the user's ratings.
@Serializable
data class GenreAffinityDto(
    @SerialName("genre_id") val genreId: Int,
    val genre: String,
    @SerialName("average_rating") val averageRating: Double,
    @SerialName("movie_count") val movieCount: Int
)

@Serializable
data class TasteProfileResponseDto(val genres: List<GenreAffinityDto>)

// Own profile (GET/PUT /api/users/{userId}): username (unique, like an Instagram handle), bio,
// avatar, top genres and statistics.
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

// The app sends its whole UserProfile object in the PUT body; only username/bio/avatar are read
// (ignoreUnknownKeys drops the rest). `avatar` changes the picture only when it is a new data URI
// (see UserDao.updateProfile).
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

// How many movies with an incomplete cache (no genres/TMDB rating) are backfilled per list load.
private const val BACKFILL_LIMIT_PER_REQUEST = 10

/**
 * Movies cached before `genres`/`vote_average` were stored lack that data, so the Library filters
 * would not work for them. Instead of a manual migration, every list load backfills up to
 * [BACKFILL_LIMIT_PER_REQUEST] such movies in the background; the response does not wait, and the
 * next time the Library opens the data is complete.
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

/** Rounds to the nearest integer and clamps to 1-10 (the database CHECK range). Null stays null. */
internal fun Double?.toRatingIntOrNull(): Int? {
    if (this == null) return null
    return Math.round(this).toInt().coerceIn(1, 10)
}

/**
 * The signed-in user's own data: profile, watchlist, watched/rated movies and taste profile.
 *
 * Every route checks that the `{userId}` path segment equals the JWT subject, so nobody can read or
 * change another user's lists.
 */
fun Application.configureUserActionsRouting(
    tmdbService: TmdbService,
    userActionsRepository: UserActionsRepository,
    moviesRepository: MoviesRepository,
    tasteProfileRepository: TasteProfileRepository,
    userDao: UserDao,
    friendshipsRepository: FriendshipsRepository,
    accountDeletion: AccountDeletion = AccountDeletion()
) {
    routing {
        authenticate("auth-jwt") {
            route("/api/users/{userId}") {

                fun ApplicationCall.ownerUserIdOrNull(): String? {
                    val pathUserId = parameters["userId"] ?: return null
                    val tokenUserId = principal<JWTPrincipal>()?.payload?.subject
                    return if (pathUserId == tokenUserId) pathUserId else null
                }

                // Profile plus derived statistics (watched/rated counts, friends, top genres).
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

                // Deletes the account with all its data, then the Firebase sign-in (so signing in
                // again starts a fresh account). Asked for from the app's profile page.
                delete {
                    val userId = call.ownerUserIdOrNull()
                        ?: return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                    val firebaseUid = accountDeletion.deleteAccount(userId)
                        ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Korisnik nije pronađen"))
                    try {
                        FirebaseAuth.getInstance().deleteUser(firebaseUid)
                    } catch (e: Exception) {
                        // The data is gone already; a leftover Firebase sign-in only means a new,
                        // empty account if the person signs in again.
                        logger.warn("Account $userId deleted, but not its Firebase user: ${e.message}")
                    }
                    logger.info("Account deleted: $userId")
                    call.respond(HttpStatusCode.NoContent)
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

                        // PUT works as an idempotent upsert: if the row does not exist it behaves like
                        // POST. The app's local state can disagree with the server (e.g. an earlier
                        // failed POST, or a removal on another screen), and the app then sends PUT for
                        // a movie that is not marked as watched on the server.
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

                    // Undo for an accidental "mark as watched" - also removes the rating.
                    delete("/{tmdbId}") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val tmdbId = call.parameters["tmdbId"]?.toLongOrNull()
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan tmdbId"))
                        userActionsRepository.removeFromHistory(userId, tmdbId)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                // Average rating per genre from watched and rated movies (own profile only).
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
