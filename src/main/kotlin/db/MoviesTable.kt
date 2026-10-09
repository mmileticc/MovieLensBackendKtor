package com.milinko.db

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * Local cache of TMDB movie data.
 *
 * `user_actions.movie_id` and `movie_comments.movie_id` are foreign keys to `movies.tmdb_id`, so a
 * movie must be cached here before it can be added to a list, rated or commented on
 * (see [MoviesRepository.ensureCached]).
 *
 * - `genres` is a JSON string (`[{"id":28,"name":"Action"}, ...]`, see `TmdbGenre`) rather than a
 *   Postgres array, because Exposed has no built-in array column type. Genres drive the taste
 *   profile and recommendations.
 * - `last_updated` is `timestamptz` in the database but mapped as a naive UTC `datetime()`, like
 *   every other timestamp in this project.
 */
object MoviesTable : Table("movies") {
    val tmdbId = long("tmdb_id")
    val title = text("title")
    val posterUrl = text("poster_url").nullable()
    val releaseYear = integer("release_year").nullable()
    val popularityScore = double("popularity_score").nullable()
    // JSON list of TmdbGenre. Null for rows cached before genres were stored; filled in lazily by
    // MoviesRepository.ensureCached the next time someone interacts with the movie.
    val genres = text("genres").nullable()
    // TMDB average rating, used to sort the Library. Filled in lazily like `genres`.
    val voteAverage = double("vote_average").nullable()
    val lastUpdated = datetime("last_updated").nullable().clientDefault {
        Clock.System.now().toLocalDateTime(TimeZone.UTC)
    }
    override val primaryKey = PrimaryKey(tmdbId, name = "movies_pkey")
}
