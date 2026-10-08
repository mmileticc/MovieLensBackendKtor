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

/** Dekodira MoviesTable.genres (JSON string, videti napomenu na koloni) - prazna lista ako null/pokvareno. */
fun decodeGenres(genresJson: String?): List<TmdbGenre> {
    if (genresJson.isNullOrBlank()) return emptyList()
    return try {
        Json.decodeFromString(genresJson)
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Lokalni keš TMDB filmova (Faza 2, MOVIELENS_ROADMAP.md). `user_actions.movie_id` je FK na
 * `movies.tmdb_id`, pa film mora prvo postojati ovde pre nego što se doda u watchlistu/oceni.
 */
class MoviesRepository {

    /**
     * Osigurava da film sa datim tmdbId postoji u `movies` tabeli - ako ne postoji, povlači
     * detalje sa TMDB-a i upisuje ih. Ako VEĆ postoji ali mu fali `genres` (film keširan pre Faze
     * 4, ili ranije nikad nije bio ocenjen/gledan pa je genres ostao null), lenjo se dopunjava
     * samo ta kolona - vidi napomenu na MoviesTable.genres. Baca [IllegalArgumentException] ako
     * TMDB ne prepoznaje taj tmdbId (film ne postoji).
     */
    suspend fun ensureCached(tmdbId: Int, tmdbService: TmdbService) {
        val existingRow = dbQuery {
            MoviesTable.selectAll().where { MoviesTable.tmdbId eq tmdbId.toLong() }.firstOrNull()
        }
        // Već postoji I ima genres + vote_average popunjene - ništa se ne radi. Ako postoji ali
        // nešto od toga fali (keširano pre Faze 4 / pre vote_average kolone), nastavljamo dole da
        // samo dopunimo te kolone.
        if (existingRow != null && existingRow[MoviesTable.genres] != null && existingRow[MoviesTable.voteAverage] != null) return

        val detail = tmdbService.getMovieDetails(tmdbId)
            ?: throw IllegalArgumentException("Film sa tmdbId=$tmdbId ne postoji na TMDB-u")
        val genresJson = Json.encodeToString(detail.genres)
        val voteAverageValue = detail.voteAverage

        dbQuery {
            if (existingRow == null) {
                // Ponovna provera unutar transakcije - blaga zaštita od retke trke (dva paralelna
                // zahteva za isti film istovremeno).
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
                    // Neko drugi je upisao red dok smo čekali TMDB - samo dopuni genres ako fali.
                    MoviesTable.update({ (MoviesTable.tmdbId eq tmdbId.toLong()) and (MoviesTable.genres.isNull()) }) {
                        it[genres] = genresJson
                    }
                    MoviesTable.update({ (MoviesTable.tmdbId eq tmdbId.toLong()) and (MoviesTable.voteAverage.isNull()) }) {
                        it[voteAverage] = voteAverageValue
                    }
                }
            } else {
                // Red postoji, ali mu je genres i/ili vote_average falio - backfill oba (genresJson
                // je ionako svež sa TMDB-a, prepisivanje je bezopasno).
                MoviesTable.update({ MoviesTable.tmdbId eq tmdbId.toLong() }) {
                    it[genres] = genresJson
                    it[voteAverage] = voteAverageValue
                }
            }
        }
    }
}
