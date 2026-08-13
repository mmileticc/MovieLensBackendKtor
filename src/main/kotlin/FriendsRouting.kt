package com.milinko

import com.milinko.db.FriendshipStatus
import com.milinko.db.FriendshipsRepository
import com.milinko.db.User
import com.milinko.db.UserActionsRepository
import com.milinko.db.UserDao
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

/**
 * Prijateljstva (Faza 3, MOVIELENS_ROADMAP.md): pretraga korisnika, slanje/prihvatanje/odbijanje
 * zahteva, lista prijatelja i activity feed (samo "odgledano"/ocene - watchlist ostaje privatan,
 * dogovoreno u planu).
 */

@Serializable
data class UserSearchResultDto(
    val id: String,
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val status: String
)

@Serializable
data class UserSearchResponseDto(val users: List<UserSearchResultDto>)

@Serializable
data class FriendDto(
    @SerialName("friend_id") val friendId: String,
    val username: String,
    val avatar: String? = null,
    // Placeholder dok se ne uradi Faza 4 (profilisanje/match) - videti MOVIELENS_ROADMAP.md.
    @SerialName("match_score") val matchScore: Float = 0f,
    @SerialName("common_genres") val commonGenres: List<String> = emptyList(),
    // Android model (FriendInfo) očekuje ovaj ključ da postoji u JSON-u - Gson kod Kotlin data
    // klasa ne primenjuje default vrednosti za izostavljene ključeve (bypass-uje konstruktor).
    @SerialName("mutual_watchlist") val mutualWatchlist: List<String> = emptyList()
)

@Serializable
data class FriendsResponseDto(val friends: List<FriendDto>)

@Serializable
data class PendingRequestDto(val id: String, val username: String? = null, @SerialName("avatar_url") val avatarUrl: String? = null)

@Serializable
data class PendingRequestsResponseDto(val requests: List<PendingRequestDto>)

@Serializable
data class FriendActionRequest(val action: String)

@Serializable
data class ActivityMetadataDto(val rating: Double? = null, @SerialName("watched_at") val watchedAt: Long? = null)

@Serializable
data class FriendActivityDto(
    @SerialName("activity_id") val activityId: String,
    @SerialName("friend_id") val friendId: String,
    @SerialName("friend_name") val friendName: String,
    val action: String,
    @SerialName("tmdb_id") val tmdbId: Long,
    @SerialName("movie_title") val movieTitle: String,
    val metadata: ActivityMetadataDto = ActivityMetadataDto(),
    val timestamp: Long
)

@Serializable
data class FriendActivityResponseDto(val activities: List<FriendActivityDto>)

// Profil prijatelja - osnovni info + njegova WATCHED lista (nikad watchlist, taj ostaje privatan).
@Serializable
data class FriendProfileMovieDto(
    @SerialName("tmdb_id") val tmdbId: Long,
    val title: String,
    @SerialName("poster_path") val posterPath: String? = null,
    val rating: Int? = null,
    @SerialName("watched_at") val watchedAt: Long
)

@Serializable
data class FriendProfileResponseDto(
    val id: String,
    val username: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("watched_movies") val watchedMovies: List<FriendProfileMovieDto>
)

private fun LocalDateTime?.toEpochMillisOrNow(): Long {
    val localDateTime = this ?: Clock.System.now().toLocalDateTime(TimeZone.UTC)
    return localDateTime.toInstant(TimeZone.UTC).toEpochMilliseconds()
}

private fun User.toFriendDto() = FriendDto(
    friendId = id,
    username = username ?: "Korisnik",
    avatar = avatarUrl
)

private fun User.toPendingRequestDto() = PendingRequestDto(id = id, username = username, avatarUrl = avatarUrl)

fun Application.configureFriendsRouting(
    friendshipsRepository: FriendshipsRepository,
    userDao: UserDao,
    userActionsRepository: UserActionsRepository
) {
    routing {
        authenticate("auth-jwt") {

            // Pretraga korisnika za dodavanje prijatelja - flat ruta (nema {userId} path parametra,
            // "ko sam ja" se čita direktno iz JWT-a).
            get("/api/users/search") {
                val callerId = call.principal<JWTPrincipal>()?.payload?.subject
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val query = call.request.queryParameters["q"]?.trim().orEmpty()
                if (query.length < 2) {
                    return@get call.respond(UserSearchResponseDto(emptyList()))
                }

                val results = userDao.searchUsers(query, excludeUserId = callerId).map { user ->
                    val status = friendshipsRepository.getFriendshipStatus(callerId, user.id)
                    UserSearchResultDto(
                        id = user.id,
                        username = user.username,
                        avatarUrl = user.avatarUrl,
                        status = status.name
                    )
                }
                call.respond(UserSearchResponseDto(results))
            }

            route("/api/users/{userId}") {

                fun ApplicationCall.ownerUserIdOrNull(): String? {
                    val pathUserId = parameters["userId"] ?: return null
                    val tokenUserId = principal<JWTPrincipal>()?.payload?.subject
                    return if (pathUserId == tokenUserId) pathUserId else null
                }

                route("/friends") {
                    get {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val friends = friendshipsRepository.getFriends(userId).map { it.toFriendDto() }
                        call.respond(FriendsResponseDto(friends))
                    }

                    get("/pending") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val pending = friendshipsRepository.getPendingIncoming(userId).map { it.toPendingRequestDto() }
                        call.respond(PendingRequestsResponseDto(pending))
                    }

                    get("/activity") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val friendIds = friendshipsRepository.getFriendIds(userId)
                        val activity = userActionsRepository.getFriendsActivity(friendIds).map { row ->
                            FriendActivityDto(
                                activityId = "${row.friendId}-${row.tmdbId}",
                                friendId = row.friendId,
                                friendName = row.friendUsername ?: "Korisnik",
                                action = if (row.rating != null) "rated" else "watched",
                                tmdbId = row.tmdbId,
                                movieTitle = row.title,
                                metadata = ActivityMetadataDto(
                                    rating = row.rating?.toDouble(),
                                    watchedAt = row.createdAt.toEpochMillisOrNow()
                                ),
                                timestamp = row.createdAt.toEpochMillisOrNow()
                            )
                        }
                        call.respond(FriendActivityResponseDto(activity))
                    }

                    // Profil prijatelja - dozvoljeno za sebe ili za ACCEPTED prijatelja, inače 403.
                    // Nikad ne vraća watchlist (samo WATCHED - isto pravilo kao activity feed).
                    get("/{friendId}/profile") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                        val friendId = call.parameters["friendId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nedostaje friendId"))

                        if (friendId != userId) {
                            val status = friendshipsRepository.getFriendshipStatus(userId, friendId)
                            if (status != FriendshipStatus.ACCEPTED) {
                                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Možeš videti samo profile svojih prijatelja"))
                            }
                        }

                        val friendUser = userDao.findById(friendId)
                            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Korisnik nije pronađen"))

                        val watchedMovies = userActionsRepository.getHistory(friendId).map { row ->
                            FriendProfileMovieDto(
                                tmdbId = row.tmdbId,
                                title = row.title,
                                posterPath = row.posterPath,
                                rating = row.rating,
                                watchedAt = row.createdAt.toEpochMillisOrNow()
                            )
                        }

                        call.respond(
                            FriendProfileResponseDto(
                                id = friendUser.id,
                                username = friendUser.username ?: "Korisnik",
                                avatarUrl = friendUser.avatarUrl,
                                watchedMovies = watchedMovies
                            )
                        )
                    }

                    post("/{friendId}") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                        val friendId = call.parameters["friendId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nedostaje friendId"))
                        val req = call.receive<FriendActionRequest>()

                        when (req.action.lowercase()) {
                            "add" -> {
                                try {
                                    friendshipsRepository.sendRequest(userId, friendId)
                                } catch (e: IllegalArgumentException) {
                                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Nevalidan zahtev"))
                                }
                            }
                            "accept" -> {
                                val accepted = friendshipsRepository.acceptRequest(userId, friendId)
                                if (!accepted) {
                                    return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Nema zahteva za prihvatanje"))
                                }
                            }
                            "reject" -> friendshipsRepository.removeFriendship(userId, friendId)
                            else -> return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nepoznata akcija: ${req.action}"))
                        }
                        call.respond(HttpStatusCode.NoContent)
                    }

                    delete("/{friendId}") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                        val friendId = call.parameters["friendId"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nedostaje friendId"))
                        friendshipsRepository.removeFriendship(userId, friendId)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}
