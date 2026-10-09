package com.milinko

import com.milinko.db.FriendshipStatus
import com.milinko.db.FriendshipsRepository
import com.milinko.db.SendRequestResult
import com.milinko.db.User
import com.milinko.db.UserActionsRepository
import com.milinko.db.UserDao
import com.milinko.db.TasteProfileRepository
import com.milinko.db.compareTasteProfiles
import com.milinko.services.PushService
import com.milinko.services.PushType
import com.milinko.services.TmdbService
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

// DTO field names match the Android models (Gson @SerializedName in UserModels.kt).

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
    // Computed by compareTasteProfiles (0 when there are no shared genres).
    @SerialName("match_score") val matchScore: Float = 0f,
    @SerialName("common_genres") val commonGenres: List<String> = emptyList(),
    // Always empty, but the key must be present: Gson ignores Kotlin default values for missing
    // keys (it bypasses the constructor), so the app would get null.
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
data class ActivityMetadataDto(
    val rating: Double? = null,
    val review: String? = null,
    @SerialName("watched_at") val watchedAt: Long? = null
)

@Serializable
data class FriendActivityDto(
    @SerialName("activity_id") val activityId: String,
    @SerialName("friend_id") val friendId: String,
    @SerialName("friend_name") val friendName: String,
    val action: String,
    @SerialName("tmdb_id") val tmdbId: Long,
    @SerialName("movie_title") val movieTitle: String,
    // Shown in the activity feed instead of a plain icon.
    @SerialName("poster_path") val posterPath: String? = null,
    val metadata: ActivityMetadataDto = ActivityMetadataDto(),
    val timestamp: Long
)

@Serializable
data class FriendActivityResponseDto(val activities: List<FriendActivityDto>)

// A movie from a friend's watched list (watchlists are never shown to anyone).
@Serializable
data class FriendProfileMovieDto(
    @SerialName("tmdb_id") val tmdbId: Long,
    val title: String,
    @SerialName("poster_path") val posterPath: String? = null,
    val rating: Int? = null,
    val review: String? = null,
    @SerialName("watched_at") val watchedAt: Long
)

/**
 * A user's profile as seen by the caller.
 *
 * For accepted friends (and oneself) it contains the watched list. For everyone else it is a
 * limited preview (picture, name, bio, shared genres) so the user can decide whether to send or
 * accept a request: `watchedMovies` stays empty and `email` is null, because nothing about what
 * someone watched - or their address - is revealed before the friendship is accepted.
 * `friendshipStatus` tells the app which version to show.
 */
@Serializable
data class FriendProfileResponseDto(
    val id: String,
    val username: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    // Short status line under the name, like on one's own profile.
    val bio: String? = null,
    @SerialName("watched_movies") val watchedMovies: List<FriendProfileMovieDto>,
    val email: String? = null,
    @SerialName("friendship_status") val friendshipStatus: String = "ACCEPTED",
    @SerialName("common_genres") val commonGenres: List<String> = emptyList()
)

// Movie Night: suggestions for watching together with a friend.
@Serializable
data class SuggestionScoresDto(
    @SerialName("your_match") val yourMatch: Float = 0f,
    @SerialName("their_match") val theirMatch: Float = 0f,
    @SerialName("combined_score") val combinedScore: Float = 0f
)

@Serializable
data class MovieSuggestionDto(
    @SerialName("tmdb_id") val tmdbId: Int,
    val title: String,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("match_reason") val matchReason: String,
    val scores: SuggestionScoresDto = SuggestionScoresDto(),
    // Basic data so the user can decide without opening the movie.
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList()
)

@Serializable
data class MovieNightResponseDto(
    val suggestions: List<MovieSuggestionDto>,
    // Whether TMDB has more pages for this genre mix after the current one; the app pages forward
    // and starts again from page 1 only when there is nothing new left.
    @SerialName("has_more") val hasMore: Boolean = false,
    // Movie Night header: friend's name, shared genres (also offered as a filter) and the overall
    // taste match. The same for all suggestions, so it is here and not in every suggestion.
    @SerialName("friend_username") val friendUsername: String? = null,
    @SerialName("shared_genres") val sharedGenres: List<GenreDto> = emptyList(),
    @SerialName("match_score") val matchScore: Float = 0f
)

private fun LocalDateTime?.toEpochMillisOrNow(): Long {
    val localDateTime = this ?: Clock.System.now().toLocalDateTime(TimeZone.UTC)
    return localDateTime.toInstant(TimeZone.UTC).toEpochMilliseconds()
}

private fun User.toFriendDto(matchScore: Float = 0f, commonGenres: List<String> = emptyList()) = FriendDto(
    friendId = id,
    username = username ?: "Korisnik",
    avatar = avatarUrl,
    matchScore = matchScore,
    commonGenres = commonGenres
)

private fun User.toPendingRequestDto() = PendingRequestDto(id = id, username = username, avatarUrl = avatarUrl)

/**
 * Friends: user search, friend requests (send/accept/reject/cancel, with push notifications),
 * the friends list with taste match, pending requests, the activity feed (watched/rated movies only;
 * watchlists stay private), friend profiles and Movie Night suggestions.
 */
fun Application.configureFriendsRouting(
    friendshipsRepository: FriendshipsRepository,
    userDao: UserDao,
    userActionsRepository: UserActionsRepository,
    tasteProfileRepository: TasteProfileRepository,
    tmdbService: TmdbService,
    pushService: PushService
) {
    suspend fun displayName(userId: String) = userDao.findById(userId)?.username ?: "Korisnik"

    routing {
        authenticate("auth-jwt") {

            // User search for adding friends. No {userId} in the path - the caller comes from the JWT.
            get("/api/users/search") {
                val callerId = call.principal<JWTPrincipal>()?.payload?.subject
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val query = call.request.queryParameters["q"]?.trim().orEmpty()
                if (query.length < 2) {
                    return@get call.respond(UserSearchResponseDto(emptyList()))
                }

                val users = userDao.searchUsers(query, excludeUserId = callerId)
                val statuses = friendshipsRepository.getFriendshipStatuses(callerId, users.map { it.id })
                val results = users.map { user ->
                    UserSearchResultDto(
                        id = user.id,
                        username = user.username,
                        avatarUrl = user.avatarUrl,
                        status = (statuses[user.id] ?: FriendshipStatus.NONE).name
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
                        // Taste profiles of all friends and the user in one query, then compare the user with each.
                        val friendUsers = friendshipsRepository.getFriends(userId)
                        val profiles = tasteProfileRepository.getTasteProfiles(friendUsers.map { it.id } + userId)
                        val selfProfile = profiles[userId].orEmpty()
                        val friends = friendUsers.map { friend ->
                            val match = compareTasteProfiles(selfProfile, profiles[friend.id].orEmpty())
                            friend.toFriendDto(match.matchScore, match.commonGenres)
                        }
                        call.respond(FriendsResponseDto(friends))
                    }

                    get("/pending") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val pending = friendshipsRepository.getPendingIncoming(userId).map { it.toPendingRequestDto() }
                        call.respond(PendingRequestsResponseDto(pending))
                    }

                    // Outgoing (sent) requests, listed like friends and incoming requests.
                    get("/pending/sent") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val sent = friendshipsRepository.getPendingOutgoing(userId).map { it.toPendingRequestDto() }
                        call.respond(PendingRequestsResponseDto(sent))
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
                                posterPath = row.posterPath,
                                metadata = ActivityMetadataDto(
                                    rating = row.rating?.toDouble(),
                                    review = row.review,
                                    watchedAt = row.createdAt.toEpochMillisOrNow()
                                ),
                                timestamp = row.createdAt.toEpochMillisOrNow()
                            )
                        }
                        call.respond(FriendActivityResponseDto(activity))
                    }

                    // Full profile (with the watched list, never the watchlist) for oneself or an
                    // accepted friend; a limited preview for everyone else (see FriendProfileResponseDto).
                    get("/{friendId}/profile") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                        val friendId = call.parameters["friendId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nedostaje friendId"))

                        val friendUser = userDao.findById(friendId)
                            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Korisnik nije pronađen"))

                        val status = if (friendId == userId) FriendshipStatus.ACCEPTED else friendshipsRepository.getFriendshipStatus(userId, friendId)

                        if (status != FriendshipStatus.ACCEPTED) {
                            // Limited preview: only the shared genre interests, nothing about
                            // watched movies.
                            val profiles = tasteProfileRepository.getTasteProfiles(listOf(userId, friendId))
                            val selfProfile = profiles[userId].orEmpty()
                            val friendProfile = profiles[friendId].orEmpty()
                            val match = compareTasteProfiles(selfProfile, friendProfile)
                            return@get call.respond(
                                FriendProfileResponseDto(
                                    id = friendUser.id,
                                    username = friendUser.username ?: "Korisnik",
                                    avatarUrl = friendUser.avatarUrl,
                                    bio = friendUser.bio,
                                    watchedMovies = emptyList(),
                                    // Only accepted friends see the email - otherwise anyone who knows
                                    // a user id (e.g. from a comment) could get the address.
                                    email = null,
                                    friendshipStatus = status.name,
                                    commonGenres = match.commonGenres
                                )
                            )
                        }

                        val watchedMovies = userActionsRepository.getHistory(friendId).map { row ->
                            FriendProfileMovieDto(
                                tmdbId = row.tmdbId,
                                title = row.title,
                                posterPath = row.posterPath,
                                rating = row.rating,
                                review = row.review,
                                watchedAt = row.createdAt.toEpochMillisOrNow()
                            )
                        }

                        call.respond(
                            FriendProfileResponseDto(
                                id = friendUser.id,
                                username = friendUser.username ?: "Korisnik",
                                avatarUrl = friendUser.avatarUrl,
                                bio = friendUser.bio,
                                watchedMovies = watchedMovies,
                                email = friendUser.email,
                                friendshipStatus = status.name
                            )
                        )
                    }

                    // Movie Night suggestions, for accepted friends only: TMDB discover by the shared
                    // favourite genres (compareTasteProfiles), excluding movies either of them has
                    // already watched.
                    get("/{friendId}/movienight-suggestions") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                        val friendId = call.parameters["friendId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nedostaje friendId"))
                        // ?page= lets the app's "Refresh suggestions" button get the next TMDB page
                        // for the same genres instead of always the first results.
                        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1

                        val status = friendshipsRepository.getFriendshipStatus(userId, friendId)
                        if (status != FriendshipStatus.ACCEPTED) {
                            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Predlozi su dostupni samo za prijatelje"))
                        }

                        val profiles = tasteProfileRepository.getTasteProfiles(listOf(userId, friendId))
                        val selfProfile = profiles[userId].orEmpty()
                        val friendProfile = profiles[friendId].orEmpty()
                        val match = compareTasteProfiles(selfProfile, friendProfile)
                        val friendUsername = userDao.findById(friendId)?.username

                        if (match.sharedGenreIds.isEmpty()) {
                            // No shared genres (not enough common ratings): suggest nothing rather
                            // than make up random movies.
                            return@get call.respond(MovieNightResponseDto(emptyList(), friendUsername = friendUsername))
                        }

                        val topGenreIds = match.sharedGenreIds.take(3)
                        val selfByGenreId = selfProfile.associateBy { it.genreId }
                        val friendByGenreId = friendProfile.associateBy { it.genreId }
                        val sharedGenres = topGenreIds.map { GenreDto(it, selfByGenreId.getValue(it).genreName) }
                        val primaryGenreId = topGenreIds.first()
                        val yourMatch = (selfByGenreId.getValue(primaryGenreId).averageRating * 10.0).toFloat().coerceIn(0f, 100f)
                        val theirMatch = (friendByGenreId.getValue(primaryGenreId).averageRating * 10.0).toFloat().coerceIn(0f, 100f)

                        val alreadySeen = (userActionsRepository.getHistory(userId) + userActionsRepository.getHistory(friendId))
                            .map { it.tmdbId }
                            .toSet()

                        val discoverPage = tmdbService.getMoviesByGenresWithTotalPages(topGenreIds, page)
                        val suggestions = discoverPage.movies
                            .filter { it.id != null && it.id.toLong() !in alreadySeen }
                            .take(10)
                            .map { movie ->
                                // The reason per movie: which of the shared favourite genres it has.
                                val movieSharedGenres = sharedGenres.filter { it.id in movie.genreIds }
                                    .ifEmpty { sharedGenres.take(1) }
                                MovieSuggestionDto(
                                    tmdbId = movie.id!!,
                                    title = movie.title,
                                    posterPath = movie.posterPath,
                                    matchReason = if (call.prefersEnglish()) {
                                        "You both like ${movieSharedGenres.joinToString(" and ") { it.name }}"
                                    } else {
                                        "Oboje volite ${movieSharedGenres.joinToString(" i ") { it.name }}"
                                    },
                                    scores = SuggestionScoresDto(
                                        yourMatch = yourMatch,
                                        theirMatch = theirMatch,
                                        combinedScore = match.matchScore
                                    ),
                                    releaseDate = movie.releaseDate,
                                    voteAverage = movie.voteAverage,
                                    genreIds = movie.genreIds
                                )
                            }

                        call.respond(
                            MovieNightResponseDto(
                                suggestions = suggestions,
                                hasMore = page < discoverPage.totalPages,
                                friendUsername = friendUsername,
                                sharedGenres = sharedGenres,
                                matchScore = match.matchScore
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
                                val result = try {
                                    friendshipsRepository.sendRequest(userId, friendId)
                                } catch (e: IllegalArgumentException) {
                                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Nevalidan zahtev"))
                                }
                                val name = displayName(userId)
                                when (result) {
                                    SendRequestResult.SENT -> pushService.notifyAsync(
                                        friendId, PushType.FRIEND_REQUEST, "$name ti je poslao zahtev za prijateljstvo",
                                        mapOf("actorId" to userId, "actorName" to name)
                                    )
                                    // Mutual requests: the friendship was accepted right away.
                                    SendRequestResult.ACCEPTED_EXISTING -> pushService.notifyAsync(
                                        friendId, PushType.FRIEND_ACCEPTED, "$name je prihvatio tvoj zahtev za prijateljstvo",
                                        mapOf("actorId" to userId, "actorName" to name)
                                    )
                                    SendRequestResult.ALREADY_EXISTS -> Unit
                                }
                            }
                            "accept" -> {
                                val accepted = friendshipsRepository.acceptRequest(userId, friendId)
                                if (!accepted) {
                                    return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Nema zahteva za prihvatanje"))
                                }
                                val name = displayName(userId)
                                pushService.notifyAsync(
                                    friendId, PushType.FRIEND_ACCEPTED, "$name je prihvatio tvoj zahtev za prijateljstvo",
                                    mapOf("actorId" to userId, "actorName" to name)
                                )
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
