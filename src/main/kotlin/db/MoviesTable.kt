package com.milinko.db

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * Lokalni keš TMDB podataka o filmovima. Postoji u Supabase-u već pre nego što je ova Kotlin
 * definicija napisana; ovo je usklađivanje koda sa stvarnom šemom (videti MOVIELENS_ROADMAP.md).
 *
 * `user_actions.movie_id` je FK na `movies.tmdb_id`, što znači da film mora prvo postojati ovde
 * (upsert-on-first-interaction) pre nego što se doda u watchlistu ili oceni — puna implementacija
 * upsert logike dolazi u Fazi 1/2 zajedno sa /api/movies i /api/users/{id}/watchlist rutama.
 *
 * NAPOMENE o pojednostavljenjima u odnosu na stvarnu DB šemu:
 * - `genres` je u bazi Postgres ARRAY (text[]); Exposed 0.41.1 bez dodatnog modula nema prirodnu
 *   podršku za array kolone, pa nije mapirana ovde. Dodaje se kad se piše stvarna repository
 *   logika za keširanje filmova.
 * - `last_updated` je u bazi `timestamp with time zone`; mapirano ovde kao naivan `datetime()`
 *   (isti pristup kao za ostale timestamp kolone u projektu) jer trenutne zavisnosti
 *   (exposed-kotlin-datetime) ne podržavaju direktno timestamptz bez exposed-java-time modula.
 *   Funkcionalno bezopasno za jednoregionalnu aplikaciju, ali vredi revidirati ako se doda
 *   pravi timezone-aware zahtev.
 */
object MoviesTable : Table("movies") {
    val tmdbId = long("tmdb_id")
    val title = text("title")
    val posterUrl = text("poster_url").nullable()
    val releaseYear = integer("release_year").nullable()
    val popularityScore = double("popularity_score").nullable()
    val lastUpdated = datetime("last_updated").nullable().clientDefault {
        Clock.System.now().toLocalDateTime(TimeZone.UTC)
    }
    override val primaryKey = PrimaryKey(tmdbId, name = "movies_pkey")
}
