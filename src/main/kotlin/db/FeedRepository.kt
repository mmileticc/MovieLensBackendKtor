package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

private const val WATCHED = "WATCHED"
private const val WATCHLIST = "WATCHLIST"

enum class FeedType { RATED, WATCHED, COMMENTED }

/** Who did something in the feed. */
data class FeedFriend(val id: String, val username: String?, val avatarUrl: String?)

/** One item of the friends feed. [createdAt] is UTC. */
data class FeedRow(
    val type: FeedType,
    val friend: FeedFriend,
    val tmdbId: Long,
    val title: String,
    val posterPath: String?,
    val rating: Int?,
    val text: String?,
    val createdAt: LocalDateTime,
    /** Stable id for the client (a comment id, or friend + movie for a watched entry). */
    val id: String,
)

/** A movie on my watchlist that friends also have on theirs. */
data class SharedWishRow(
    val tmdbId: Long,
    val title: String,
    val posterPath: String?,
    val friends: List<FeedFriend>,
    /** Cinema locations showing it now (0 = not in cinemas). */
    val cinemaCount: Int,
)

/**
 * The friends feed: what friends watched, rated and commented, newest first, page by page; and the
 * movies that I and some friends both want to watch.
 *
 * Watchlists stay private: [sharedWishes] only ever returns movies that are on MY watchlist too, so
 * it never reveals a movie that is only on a friend's list.
 */
class FeedRepository {

    /** Up to [limit] items older than [before] (UTC; null = newest), newest first. */
    suspend fun friendsFeed(friendIds: List<String>, before: LocalDateTime?, limit: Int): List<FeedRow> {
        if (friendIds.isEmpty()) return emptyList()
        return dbQuery {
            val watched = UserActionsTable
                .join(MoviesTable, JoinType.INNER, additionalConstraint = { UserActionsTable.movieId eq MoviesTable.tmdbId })
                .join(UsersTable, JoinType.INNER, additionalConstraint = { UserActionsTable.userId eq UsersTable.id })
                .selectAll().where {
                    (UserActionsTable.userId inList friendIds) and
                        (UserActionsTable.actionType eq WATCHED) and
                        UserActionsTable.createdAt.isNotNull() and
                        (before?.let { UserActionsTable.createdAt less it } ?: Op.TRUE)
                }
                .orderBy(UserActionsTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    val rating = row[UserActionsTable.rating]
                    FeedRow(
                        type = if (rating != null) FeedType.RATED else FeedType.WATCHED,
                        friend = row.feedFriend(),
                        tmdbId = row[MoviesTable.tmdbId],
                        title = row[MoviesTable.title],
                        posterPath = row[MoviesTable.posterUrl],
                        rating = rating,
                        text = row[UserActionsTable.review]?.trim()?.ifEmpty { null },
                        createdAt = row[UserActionsTable.createdAt]!!,
                        id = "w-${row[UserActionsTable.userId]}-${row[MoviesTable.tmdbId]}",
                    )
                }

            val comments = CommentsTable
                .join(MoviesTable, JoinType.INNER, additionalConstraint = { CommentsTable.movieId eq MoviesTable.tmdbId })
                .join(UsersTable, JoinType.INNER, additionalConstraint = { CommentsTable.userId eq UsersTable.id })
                .selectAll().where {
                    (CommentsTable.userId inList friendIds) and
                        CommentsTable.createdAt.isNotNull() and
                        (before?.let { CommentsTable.createdAt less it } ?: Op.TRUE)
                }
                .orderBy(CommentsTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    FeedRow(
                        type = FeedType.COMMENTED,
                        friend = row.feedFriend(),
                        tmdbId = row[MoviesTable.tmdbId],
                        title = row[MoviesTable.title],
                        posterPath = row[MoviesTable.posterUrl],
                        rating = null,
                        text = row[CommentsTable.body],
                        createdAt = row[CommentsTable.createdAt]!!,
                        id = "c-${row[CommentsTable.id]}",
                    )
                }

            // Each query returns the newest [limit]; the merged newest [limit] are the page.
            (watched + comments).sortedByDescending { it.createdAt }.take(limit)
        }
    }

    /**
     * Movies on [userId]'s watchlist that friends also want to watch, the ones in cinemas first,
     * then the ones most friends want; at most [limit].
     */
    suspend fun sharedWishes(userId: String, friendIds: List<String>, limit: Int = 5): List<SharedWishRow> {
        if (friendIds.isEmpty()) return emptyList()
        return dbQuery {
            val mine = UserActionsTable.select(UserActionsTable.movieId)
                .where { (UserActionsTable.userId eq userId) and (UserActionsTable.actionType eq WATCHLIST) }
                .map { it[UserActionsTable.movieId] }
            if (mine.isEmpty()) return@dbQuery emptyList()

            // Only friends' entries for movies that are on my list too - nothing else is read.
            val overlaps = UserActionsTable
                .join(MoviesTable, JoinType.INNER, additionalConstraint = { UserActionsTable.movieId eq MoviesTable.tmdbId })
                .join(UsersTable, JoinType.INNER, additionalConstraint = { UserActionsTable.userId eq UsersTable.id })
                .selectAll().where {
                    (UserActionsTable.userId inList friendIds) and
                        (UserActionsTable.actionType eq WATCHLIST) and
                        (UserActionsTable.movieId inList mine)
                }
                .map { row -> Triple(row[MoviesTable.tmdbId], row.feedFriend(), row[MoviesTable.title] to row[MoviesTable.posterUrl]) }
            if (overlaps.isEmpty()) return@dbQuery emptyList()

            val ids = overlaps.map { it.first.toInt() }.distinct()
            val cinemas = ScrapedMoviesTable.select(ScrapedMoviesTable.tmdbId, ScrapedMoviesTable.cinemaLocation)
                .where { (ScrapedMoviesTable.tmdbId inList ids) and (ScrapedMoviesTable.isUpcoming eq false) }
                .groupBy({ it[ScrapedMoviesTable.tmdbId] }, { it[ScrapedMoviesTable.cinemaLocation] })
                .mapValues { (_, locations) -> locations.distinct().size }

            overlaps.groupBy { it.first }.map { (tmdbId, rows) ->
                val (title, poster) = rows.first().third
                SharedWishRow(
                    tmdbId = tmdbId,
                    title = title,
                    posterPath = poster,
                    friends = rows.map { it.second }.distinctBy { it.id },
                    cinemaCount = cinemas[tmdbId.toInt()] ?: 0,
                )
            }
                .sortedWith(compareByDescending<SharedWishRow> { it.cinemaCount > 0 }.thenByDescending { it.friends.size })
                .take(limit)
        }
    }
}

private fun org.jetbrains.exposed.sql.ResultRow.feedFriend() = FeedFriend(
    id = this[UsersTable.id],
    username = this[UsersTable.username],
    avatarUrl = publicAvatarUrl(this[UsersTable.avatarUrl]),
)
