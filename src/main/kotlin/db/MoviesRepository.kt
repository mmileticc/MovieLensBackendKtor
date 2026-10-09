package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import com.milinko.services.TmdbGenre
import com.milinko.services.TmdbService
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

/** Decodes [MoviesTable.genres] (a JSON string); returns an empty list if it is missing or malformed. */
fun decodeGenres(genresJson: String?): List<TmdbGenre> {
    if (genresJson.isNullOrBlank()) return emptyList()
    return try {
        Json.decodeFromString(genresJson)
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Local cache of TMDB movies. A movie must be cached here before it can be added to a list, rated
 * or commented on, because those tables reference `movies.tmdb_id`.
 */
class MoviesRepository {

    /** Title from the local cache, or null if the movie has not been cached yet. */
    suspend fun getTitle(tmdbId: Long): String? = dbQuery {
        MoviesTable.selectAll().where { MoviesTable.tmdbId eq tmdbId }.firstOrNull()?.get(MoviesTable.title)
    }

    /**
     * Makes sure the movie exists in the `movies` table, fetching it from TMDB if needed.
     *
     * If the row exists but `genres` or `vote_average` is missing (rows cached before those columns
     * were filled), only those columns are backfilled.
     *
     * @throws IllegalArgumentException if TMDB does not know this id.
     */
    suspend fun ensureCached(tmdbId: Int, tmdbService: TmdbService) {
        val existingRow = dbQuery {
            MoviesTable.selectAll().where { MoviesTable.tmdbId eq tmdbId.toLong() }.firstOrNull()
        }
        // Nothing to do if the row exists and is complete.
        if (existingRow != null && existingRow[MoviesTable.genres] != null && existingRow[MoviesTable.voteAverage] != null) return

        val detail = tmdbService.getMovieDetails(tmdbId)
            ?: throw IllegalArgumentException("Film sa tmdbId=$tmdbId ne postoji na TMDB-u")
        val genresJson = Json.encodeToString(detail.genres)
        val voteAverageValue = detail.voteAverage

        dbQuery {
            if (existingRow == null) {
                // Check again inside the transaction: two concurrent requests may cache the same movie.
                val stillMissing = MoviesTable.selectAll().where { MoviesTable.tmdbId eq tmdbId.toLong() }.empty()
                if (stillMissing) {
                    MoviesTable.insert { row ->
                        row[MoviesTable.tmdbId] = tmdbId.toLong()
                        row[title] = detail.title
                        row[posterUrl] = detail.posterPath
                        row[releaseYear] = detail.releaseDate?.take(4)?.toIntOrNull()
                        row[popularityScore] = detail.popularity
                        row[genres] = genresJson
                        row[voteAverage] = voteAverageValue
                    }
                } else {
                    // Another request inserted the row while we waited for TMDB - only fill the gaps.
                    MoviesTable.update({ (MoviesTable.tmdbId eq tmdbId.toLong()) and (MoviesTable.genres.isNull()) }) {
                        it[genres] = genresJson
                    }
                    MoviesTable.update({ (MoviesTable.tmdbId eq tmdbId.toLong()) and (MoviesTable.voteAverage.isNull()) }) {
                        it[voteAverage] = voteAverageValue
                    }
                }
            } else {
                // The row exists but genres and/or vote_average are missing - backfill both.
                MoviesTable.update({ MoviesTable.tmdbId eq tmdbId.toLong() }) {
                    it[genres] = genresJson
                    it[voteAverage] = voteAverageValue
                }
            }
        }
    }
}
