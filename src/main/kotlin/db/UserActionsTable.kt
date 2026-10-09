package com.milinko.db

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * A user's watchlist and watched movies in one table: one row per (user, movie, action type).
 *
 * - `WATCHLIST`: the movie is saved to watch later.
 * - `WATCHED`: the movie was watched. `rating` (1-10) is optional, so the same row covers both
 *   "watched" and "watched and rated", and the rating can be added or changed at any time.
 *
 * The CHECK constraints below only apply when the table is created from scratch;
 * `createMissingTablesAndColumns` never alters constraints on an existing table.
 * `created_at` is `timestamptz` in the database, mapped as a naive UTC `datetime()`.
 */
object UserActionsTable : Table("user_actions") {
    val userId = varchar("user_id", 64) references UsersTable.id
    val movieId = long("movie_id") references MoviesTable.tmdbId
    val actionType = varchar("action_type", 20)
    val rating = integer("rating").nullable()
    // Optional short note attached to a WATCHED row. Still returned by the API, but the app now
    // uses public comments (CommentsTable) instead and no longer writes it.
    val review = text("review").nullable()
    val createdAt = datetime("created_at").nullable().clientDefault {
        Clock.System.now().toLocalDateTime(TimeZone.UTC)
    }

    override val primaryKey = PrimaryKey(userId, movieId, actionType, name = "user_actions_pkey")

    init {
        check("user_actions_action_type_check") { actionType inList listOf("WATCHLIST", "WATCHED") }
        check("user_actions_rating_check") { (rating greaterEq 1) and (rating lessEq 10) }
    }
}
