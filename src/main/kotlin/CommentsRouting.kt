package com.milinko

import com.milinko.db.CommentRow
import com.milinko.db.CommentsRepository
import com.milinko.db.FriendshipsRepository
import com.milinko.db.MoviesRepository
import com.milinko.db.UserDao
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

/**
 * Javni komentari na film (E2.2 dorada, 3. septembar 2026) - videti CommentsTable.kt za puni
 * kontekst zamene starog `review` polja. Sve rute iza JWT auth-a (isti razlog kao
 * /api/movies/{tmdbId}/friends-watched u MovieRouting.kt - potreban je identitet pozivaoca da bi
 * se znalo koji komentari su "od prijatelja"/već lajkovani/sopstveni, čak i za obično čitanje).
 */

private const val COMMENT_MAX_LENGTH = 1000
private const val DEFAULT_PAGE_SIZE = 10
private const val MAX_PAGE_SIZE = 50

@Serializable
data class CommentDto(
    val id: Int,
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val body: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("liked_by_me") val likedByMe: Boolean = false,
    @SerialName("is_friend") val isFriend: Boolean = false,
    @SerialName("is_own") val isOwn: Boolean = false
)

@Serializable
data class CommentsResponseDto(val comments: List<CommentDto>, @SerialName("has_more") val hasMore: Boolean)

@Serializable
data class AddCommentRequest(val body: String)

@Serializable
data class LikeResponseDto(@SerialName("liked_by_me") val likedByMe: Boolean, @SerialName("like_count") val likeCount: Int)

private fun LocalDateTime?.toEpochMillisOrNow(): Long {
    val localDateTime = this ?: Clock.System.now().toLocalDateTime(TimeZone.UTC)
    return localDateTime.toInstant(TimeZone.UTC).toEpochMilliseconds()
}

private fun CommentRow.toDto() = CommentDto(
    id = id,
    userId = userId,
    username = username ?: "Korisnik",
    avatarUrl = avatarUrl,
    body = body,
    createdAt = createdAt.toEpochMillisOrNow(),
    likeCount = likeCount,
    likedByMe = likedByMe,
    isFriend = isFriend,
    isOwn = isOwn
)

fun Application.configureCommentsRouting(
    tmdbService: TmdbService,
    commentsRepository: CommentsRepository,
    moviesRepository: MoviesRepository,
    friendshipsRepository: FriendshipsRepository,
    userDao: UserDao
) {
    routing {
        authenticate("auth-jwt") {
            route("/api/movies/{tmdbId}/comments") {
                get {
                    val callerId = call.principal<JWTPrincipal>()?.payload?.subject
                        ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    val tmdbId = call.parameters["tmdbId"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan tmdbId"))
                    val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, MAX_PAGE_SIZE)
                        ?: DEFAULT_PAGE_SIZE

                    val friendIds = friendshipsRepository.getFriendIds(callerId)
                    val page = commentsRepository.getForMovie(tmdbId, callerId, friendIds, offset, limit)
                    call.respond(CommentsResponseDto(page.rows.map { it.toDto() }, page.hasMore))
                }

                post {
                    val callerId = call.principal<JWTPrincipal>()?.payload?.subject
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val tmdbId = call.parameters["tmdbId"]?.toLongOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan tmdbId"))
                    val req = call.receive<AddCommentRequest>()
                    val body = req.body.trim().take(COMMENT_MAX_LENGTH)
                    if (body.isEmpty()) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Komentar ne može biti prazan"))
                    }

                    try {
                        moviesRepository.ensureCached(tmdbId.toInt(), tmdbService)
                    } catch (e: IllegalArgumentException) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Nevalidan film"))
                    }

                    val commentId = commentsRepository.add(callerId, tmdbId, body)
                    val user = userDao.findById(callerId)
                    call.respond(
                        CommentDto(
                            id = commentId,
                            userId = callerId,
                            username = user?.username ?: "Korisnik",
                            avatarUrl = user?.avatarUrl,
                            body = body,
                            createdAt = System.currentTimeMillis(),
                            likeCount = 0,
                            likedByMe = false,
                            isFriend = false,
                            isOwn = true
                        )
                    )
                }
            }

            route("/api/comments/{id}") {
                delete {
                    val callerId = call.principal<JWTPrincipal>()?.payload?.subject
                        ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                    val commentId = call.parameters["id"]?.toIntOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan id"))

                    val deleted = commentsRepository.delete(commentId, callerId)
                    if (!deleted) {
                        return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Ne možeš obrisati ovaj komentar"))
                    }
                    call.respond(HttpStatusCode.NoContent)
                }

                post("/like") {
                    val callerId = call.principal<JWTPrincipal>()?.payload?.subject
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val commentId = call.parameters["id"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan id"))

                    try {
                        val result = commentsRepository.toggleLike(commentId, callerId)
                        call.respond(LikeResponseDto(result.liked, result.likeCount))
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Nevalidan zahtev"))
                    }
                }
            }
        }
    }
}
