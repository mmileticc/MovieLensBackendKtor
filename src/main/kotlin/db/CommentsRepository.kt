package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select

/** A comment joined with its author, plus like/friend/own flags relative to the requesting user. */
data class CommentRow(
    val id: Int,
    val userId: String,
    val username: String?,
    val avatarUrl: String?,
    val movieId: Long,
    val body: String,
    val createdAt: LocalDateTime?,
    val likeCount: Int,
    val likedByMe: Boolean,
    val isFriend: Boolean,
    val isOwn: Boolean
)

/** One page of comments and whether more exist. */
data class CommentsPage(val rows: List<CommentRow>, val hasMore: Boolean)

/** Result of a like toggle; owner and movie are returned so the route can notify the author. */
data class LikeToggleResult(val liked: Boolean, val likeCount: Int, val commentOwnerId: String, val movieId: Long)

/** Reads and writes movie comments and their likes ([CommentsTable], [CommentLikesTable]). */
class CommentsRepository {

    /** Adds a new comment and returns its generated id. */
    suspend fun add(userId: String, movieId: Long, body: String): Int = dbQuery {
        CommentsTable.insert { row ->
            row[CommentsTable.userId] = userId
            row[CommentsTable.movieId] = movieId
            row[CommentsTable.body] = body
        } get CommentsTable.id
    }

    /**
     * Deletes the comment only if [userId] is its author (returns false otherwise, so the route can
     * answer 403 without revealing whether the comment exists). Likes are deleted first because
     * they reference the comment.
     */
    suspend fun delete(commentId: Int, userId: String): Boolean = dbQuery {
        val owns = CommentsTable.selectAll().where {
            (CommentsTable.id eq commentId) and (CommentsTable.userId eq userId)
        }.empty().not()
        if (!owns) return@dbQuery false

        CommentLikesTable.deleteWhere { CommentLikesTable.commentId eq commentId }
        CommentsTable.deleteWhere { (CommentsTable.id eq commentId) and (CommentsTable.userId eq userId) }
        true
    }

    /**
     * A page of the movie's comments: friends' comments first, then everyone else, newest first
     * within each group. [requestingUserId] is null for guests (no own/liked markers).
     *
     * Sorting and paging happen in memory after loading all comments of the movie. That is fine for
     * tens or hundreds of comments per movie; with much larger volumes this should become a SQL
     * ORDER BY with a CASE expression.
     */
    suspend fun getForMovie(
        movieId: Long,
        requestingUserId: String?,
        friendIds: List<String>,
        offset: Int,
        limit: Int
    ): CommentsPage {
        val friendSet = friendIds.toSet()

        val allRows = dbQuery {
            CommentsTable
                .join(UsersTable, JoinType.INNER, additionalConstraint = { CommentsTable.userId eq UsersTable.id })
                .selectAll().where { CommentsTable.movieId eq movieId }
                .orderBy(CommentsTable.createdAt, SortOrder.DESC)
                .map { row ->
                    CommentRow(
                        id = row[CommentsTable.id],
                        userId = row[CommentsTable.userId],
                        username = row[UsersTable.username],
                        avatarUrl = publicAvatarUrl(row[UsersTable.avatarUrl]),
                        movieId = row[CommentsTable.movieId],
                        body = row[CommentsTable.body],
                        createdAt = row[CommentsTable.createdAt],
                        likeCount = 0,
                        likedByMe = false,
                        isFriend = row[CommentsTable.userId] in friendSet,
                        isOwn = requestingUserId != null && row[CommentsTable.userId] == requestingUserId
                    )
                }
        }

        // sortedByDescending is stable: within each group the createdAt DESC order from SQL is kept.
        val sorted = allRows.sortedByDescending { it.isFriend }
        val page = sorted.drop(offset).take(limit)
        if (page.isEmpty()) return CommentsPage(emptyList(), hasMore = false)

        val commentIds = page.map { it.id }
        val likeUserIdsByComment = dbQuery {
            CommentLikesTable.selectAll().where { CommentLikesTable.commentId inList commentIds }
                .map { it[CommentLikesTable.commentId] to it[CommentLikesTable.userId] }
        }.groupBy({ it.first }, { it.second })

        val enriched = page.map { row ->
            val likedBy = likeUserIdsByComment[row.id].orEmpty()
            row.copy(likeCount = likedBy.size, likedByMe = requestingUserId != null && requestingUserId in likedBy)
        }
        val hasMore = sorted.size > offset + page.size
        return CommentsPage(enriched, hasMore)
    }

    /**
     * Likes the comment, or removes the like if it already exists.
     *
     * @throws IllegalArgumentException if the comment does not exist or the user tries to like
     *   their own comment.
     */
    suspend fun toggleLike(commentId: Int, userId: String): LikeToggleResult = dbQuery {
        val commentRow = CommentsTable.selectAll().where { CommentsTable.id eq commentId }.firstOrNull()
            ?: throw IllegalArgumentException("Komentar nije pronađen")
        val ownerId = commentRow[CommentsTable.userId]
        if (ownerId == userId) {
            throw IllegalArgumentException("Ne možeš lajkovati sopstveni komentar")
        }

        val alreadyLiked = CommentLikesTable.selectAll().where {
            (CommentLikesTable.commentId eq commentId) and (CommentLikesTable.userId eq userId)
        }.empty().not()

        if (alreadyLiked) {
            CommentLikesTable.deleteWhere {
                (CommentLikesTable.commentId eq commentId) and (CommentLikesTable.userId eq userId)
            }
        } else {
            CommentLikesTable.insert { row ->
                row[CommentLikesTable.commentId] = commentId
                row[CommentLikesTable.userId] = userId
            }
        }

        val likeCount = CommentLikesTable.selectAll().where { CommentLikesTable.commentId eq commentId }.count().toInt()
        LikeToggleResult(liked = !alreadyLiked, likeCount = likeCount, commentOwnerId = ownerId, movieId = commentRow[CommentsTable.movieId])
    }
}
