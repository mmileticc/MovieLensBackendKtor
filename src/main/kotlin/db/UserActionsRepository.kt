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
import org.jetbrains.exposed.sql.update

private const val ACTION_WATCHLIST = "WATCHLIST"
private const val ACTION_WATCHED = "WATCHED"

/** A watchlist or watched row joined with the cached movie data. */
data class UserActionMovieRow(
    val tmdbId: Long,
    val title: String,
    val posterPath: String?,
    val rating: Int?,
    val review: String?,
    val createdAt: LocalDateTime?,
    // Movie data from MoviesTable for the Library filters and sorting (genre, year, TMDB rating).
    // Null for movies whose cache has not been backfilled yet (see MoviesRepository.ensureCached).
    val genresJson: String? = null,
    val releaseYear: Int? = null,
    val voteAverage: Double? = null
)

/** One entry of the friend activity feed (a friend watched and possibly rated a movie). */
data class FriendActivityRow(
    val friendId: String,
    val friendUsername: String?,
    val tmdbId: Long,
    val title: String,
    val posterPath: String?,
    val rating: Int?,
    val review: String?,
    val createdAt: LocalDateTime?
)

/** A friend who watched a given movie, with their rating and note if any. */
data class FriendWatchedRow(
    val friendId: String,
    val friendUsername: String?,
    val rating: Int?,
    val review: String?
)

/**
 * Watchlist and watched/rated movies. Both live in `user_actions`, separated by `action_type`
 * (see [UserActionsTable]).
 */
class UserActionsRepository {

    // Joined with MoviesTable so every row already carries title and poster for the app.
    private val joined = UserActionsTable.join(
        MoviesTable,
        JoinType.INNER,
        additionalConstraint = { UserActionsTable.movieId eq MoviesTable.tmdbId }
    )

    private fun rowMapper(row: org.jetbrains.exposed.sql.ResultRow) = UserActionMovieRow(
        tmdbId = row[MoviesTable.tmdbId],
        title = row[MoviesTable.title],
        posterPath = row[MoviesTable.posterUrl],
        rating = row[UserActionsTable.rating],
        review = row[UserActionsTable.review],
        createdAt = row[UserActionsTable.createdAt],
        genresJson = row[MoviesTable.genres],
        releaseYear = row[MoviesTable.releaseYear],
        voteAverage = row[MoviesTable.voteAverage]
    )

    /** The user's watchlist. */
    suspend fun getWatchlist(userId: String): List<UserActionMovieRow> = dbQuery {
        joined.selectAll().where {
            (UserActionsTable.userId eq userId) and (UserActionsTable.actionType eq ACTION_WATCHLIST)
        }.map(::rowMapper)
    }

    /** (userId, movie) pairs for every user who has one of the given movies on their watchlist. */
    suspend fun getWatchlistEntriesForMovies(tmdbIds: Collection<Long>): List<Pair<String, UserActionMovieRow>> = dbQuery {
        if (tmdbIds.isEmpty()) return@dbQuery emptyList()
        joined.selectAll().where {
            (UserActionsTable.actionType eq ACTION_WATCHLIST) and (UserActionsTable.movieId inList tmdbIds)
        }.map { it[UserActionsTable.userId] to rowMapper(it) }
    }

    /** The user's watched movies (with ratings). */
    suspend fun getHistory(userId: String): List<UserActionMovieRow> = dbQuery {
        joined.selectAll().where {
            (UserActionsTable.userId eq userId) and (UserActionsTable.actionType eq ACTION_WATCHED)
        }.map(::rowMapper)
    }

    /** One watchlist entry, or null if the movie is not on the user's watchlist. */
    suspend fun getWatchlistItem(userId: String, tmdbId: Long): UserActionMovieRow? = dbQuery {
        joined.selectAll().where {
            (UserActionsTable.userId eq userId) and
                (UserActionsTable.movieId eq tmdbId) and
                (UserActionsTable.actionType eq ACTION_WATCHLIST)
        }.map(::rowMapper).firstOrNull()
    }

    /** One watched entry, or null if the user has not marked the movie as watched. */
    suspend fun getHistoryItem(userId: String, tmdbId: Long): UserActionMovieRow? = dbQuery {
        joined.selectAll().where {
            (UserActionsTable.userId eq userId) and
                (UserActionsTable.movieId eq tmdbId) and
                (UserActionsTable.actionType eq ACTION_WATCHED)
        }.map(::rowMapper).firstOrNull()
    }

    /** Adds the movie to the watchlist; does nothing if it is already there. */
    suspend fun addToWatchlist(userId: String, tmdbId: Long): Unit = dbQuery {
        val exists = UserActionsTable.selectAll().where {
            (UserActionsTable.userId eq userId) and
                (UserActionsTable.movieId eq tmdbId) and
                (UserActionsTable.actionType eq ACTION_WATCHLIST)
        }.empty().not()

        if (!exists) {
            UserActionsTable.insert { row ->
                row[UserActionsTable.userId] = userId
                row[UserActionsTable.movieId] = tmdbId
                row[UserActionsTable.actionType] = ACTION_WATCHLIST
            }
        }
    }

    /** Removes the movie from the watchlist. */
    suspend fun removeFromWatchlist(userId: String, tmdbId: Long): Unit = dbQuery {
        UserActionsTable.deleteWhere {
            (UserActionsTable.userId eq userId) and
                (UserActionsTable.movieId eq tmdbId) and
                (UserActionsTable.actionType eq ACTION_WATCHLIST)
        }
    }

    /**
     * Marks the movie as watched, or updates the existing WATCHED row. Used both for the first
     * "watched" and for later rating changes.
     *
     * When the row already exists, `null` means "leave unchanged" for both [rating] and [review],
     * so a request that only changes one of them never erases the other. An empty [review] ("")
     * clears the note on purpose. There is currently no way to remove only the rating; that would
     * need a separate parameter rather than overloading `null`.
     */
    suspend fun markWatched(userId: String, tmdbId: Long, rating: Int?, review: String? = null): Unit = dbQuery {
        val exists = UserActionsTable.selectAll().where {
            (UserActionsTable.userId eq userId) and
                (UserActionsTable.movieId eq tmdbId) and
                (UserActionsTable.actionType eq ACTION_WATCHED)
        }.empty().not()

        if (exists) {
            UserActionsTable.update({
                (UserActionsTable.userId eq userId) and
                    (UserActionsTable.movieId eq tmdbId) and
                    (UserActionsTable.actionType eq ACTION_WATCHED)
            }) { row ->
                if (rating != null) {
                    row[UserActionsTable.rating] = rating
                }
                if (review != null) {
                    row[UserActionsTable.review] = review.ifBlank { null }
                }
            }
        } else {
            UserActionsTable.insert { row ->
                row[UserActionsTable.userId] = userId
                row[UserActionsTable.movieId] = tmdbId
                row[UserActionsTable.actionType] = ACTION_WATCHED
                row[UserActionsTable.rating] = rating
                row[UserActionsTable.review] = review?.ifBlank { null }
            }
        }
    }

    /** Removes "watched" (and its rating, which lives in the same row) - used for undo. */
    suspend fun removeFromHistory(userId: String, tmdbId: Long): Unit = dbQuery {
        UserActionsTable.deleteWhere {
            (UserActionsTable.userId eq userId) and
                (UserActionsTable.movieId eq tmdbId) and
                (UserActionsTable.actionType eq ACTION_WATCHED)
        }
    }

    /**
     * Friend activity feed: only watched/rated movies, never watchlists. What someone plans to
     * watch stays private; only what they actually watched is shared (like Letterboxd).
     * Returns an empty list without querying when [friendIds] is empty.
     */
    suspend fun getFriendsActivity(friendIds: List<String>, limit: Int = 50): List<FriendActivityRow> {
        if (friendIds.isEmpty()) return emptyList()
        return dbQuery {
            UserActionsTable
                .join(MoviesTable, JoinType.INNER, additionalConstraint = { UserActionsTable.movieId eq MoviesTable.tmdbId })
                .join(UsersTable, JoinType.INNER, additionalConstraint = { UserActionsTable.userId eq UsersTable.id })
                .selectAll().where {
                    (UserActionsTable.userId inList friendIds) and (UserActionsTable.actionType eq ACTION_WATCHED)
                }
                .orderBy(UserActionsTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    FriendActivityRow(
                        friendId = row[UserActionsTable.userId],
                        friendUsername = row[UsersTable.username],
                        tmdbId = row[MoviesTable.tmdbId],
                        title = row[MoviesTable.title],
                        posterPath = row[MoviesTable.posterUrl],
                        rating = row[UserActionsTable.rating],
                        review = row[UserActionsTable.review],
                        createdAt = row[UserActionsTable.createdAt]
                    )
                }
        }
    }

    /**
     * Which of the given friends watched this movie, with their rating if any (the "friends
     * watched" badge on the movie detail screen). No join with MoviesTable: the app already has
     * the movie's title and poster.
     */
    suspend fun getFriendsWhoWatched(friendIds: List<String>, tmdbId: Long): List<FriendWatchedRow> {
        if (friendIds.isEmpty()) return emptyList()
        return dbQuery {
            UserActionsTable
                .join(UsersTable, JoinType.INNER, additionalConstraint = { UserActionsTable.userId eq UsersTable.id })
                .selectAll().where {
                    (UserActionsTable.userId inList friendIds) and
                        (UserActionsTable.movieId eq tmdbId) and
                        (UserActionsTable.actionType eq ACTION_WATCHED)
                }
                .map { row ->
                    FriendWatchedRow(
                        friendId = row[UserActionsTable.userId],
                        friendUsername = row[UsersTable.username],
                        rating = row[UserActionsTable.rating],
                        review = row[UserActionsTable.review]
                    )
                }
        }
    }
}
