package com.milinko.db

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * Public comments on movies. Anyone signed in can comment on any movie, independently of whether
 * they rated or watched it.
 *
 * Comments can be deleted but not edited. The surrogate `id` key is needed because one user can
 * leave several comments on the same movie, so (user_id, movie_id) cannot be the primary key.
 *
 * Note: the older `user_actions.review` column (a private note attached to a rating) still exists
 * in the database but is no longer written by the app.
 */
object CommentsTable : Table("movie_comments") {
    val id = integer("id").autoIncrement()
    val userId = varchar("user_id", 64) references UsersTable.id
    val movieId = long("movie_id") references MoviesTable.tmdbId
    val body = text("body")
    val createdAt = datetime("created_at").nullable().clientDefault {
        Clock.System.now().toLocalDateTime(TimeZone.UTC)
    }

    override val primaryKey = PrimaryKey(id, name = "movie_comments_pkey")
}
