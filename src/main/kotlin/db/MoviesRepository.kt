package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import com.milinko.services.TmdbService
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select

/**
 * Lokalni keš TMDB filmova (Faza 2, MOVIELENS_ROADMAP.md). `user_actions.movie_id` je FK na
 * `movies.tmdb_id`, pa film mora prvo postojati ovde pre nego što se doda u watchlistu/oceni.
 */
class MoviesRepository {

    /**
     * Osigurava da film sa datim tmdbId postoji u `movies` tabeli - ako ne postoji, povlači
     * detalje sa TMDB-a i upisuje ih. Baca [IllegalArgumentException] ako TMDB ne prepoznaje
     * taj tmdbId (film ne postoji).
     */
    suspend fun ensureCached(tmdbId: Int, tmdbService: TmdbService) {
        val alreadyExists = dbQuery {
            MoviesTable.select { MoviesTable.tmdbId eq tmdbId.toLong() }.empty().not()
        }
        if (alreadyExists) return

        val detail = tmdbService.getMovieDetails(tmdbId)
            ?: throw IllegalArgumentException("Film sa tmdbId=$tmdbId ne postoji na TMDB-u")

        dbQuery {
            // Ponovna provera unutar transakcije - blaga zaštita od retke trke (dva paralelna
            // zahteva za isti film istovremeno). Ako je u međuvremenu neko drugi već upisao red,
            // preskačemo insert umesto da pukne na PK constraint-u.
            val stillMissing = MoviesTable.select { MoviesTable.tmdbId eq tmdbId.toLong() }.empty()
            if (stillMissing) {
                MoviesTable.insert { row ->
                    row[MoviesTable.tmdbId] = tmdbId.toLong()
                    row[title] = detail.title
                    row[posterUrl] = detail.posterPath
                    row[releaseYear] = detail.releaseDate?.take(4)?.toIntOrNull()
                    row[popularityScore] = detail.popularity
                }
            }
        }
    }
}
