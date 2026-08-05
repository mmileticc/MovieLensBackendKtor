package com.milinko.db

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * Watchlist + "odgledano"/ocena, objedinjeno u jednu tabelu (jedan red po korisniku+filmu+tipu
 * akcije). Postoji već u Supabase-u; ovo je usklađivanje koda sa stvarnom šemom.
 *
 * DOGOVORENA IZMENA (u odnosu na originalnu produkcionu šemu koju je korisnik podelio):
 * - `action_type` je originalno dozvoljavao samo 'WATCHLIST' i 'RATED', što je značilo da se film
 *   nije mogao označiti kao odgledan bez ocene. Po dogovoru, spojeno je u jedno 'WATCHED' stanje:
 *   isti red pokriva "odgledano bez ocene" (rating = null) i "odgledano i ocenjeno" (rating != null).
 *   Ocena se može dodati/izmeniti na istom redu u bilo kom trenutku.
 * - Skala ocene promenjena sa 1-5 na 1-10 (IMDb/TMDB stil).
 *
 * VAŽNO: pošto `user_actions` tabela već postoji u produkciji sa starim CHECK constraint-ima
 * ('WATCHLIST'/'RATED', rating 1-5), Exposed-ov createMissingTablesAndColumns NE menja postojeće
 * constraint-e (samo dodaje ono što nedostaje). Za postojeću bazu je potrebno jednom ručno
 * pokrenuti migraciju iz MOVIELENS_ROADMAP.md / poruke uz ovu izmenu. Check blok ispod je i dalje
 * koristan jer se primenjuje ako se tabela ikad kreira iznova (npr. nov Supabase projekat).
 *
 * `created_at` (timestamptz u bazi) mapiran kao naivan `datetime()` — ista napomena kao u
 * MoviesTable.kt.
 */
object UserActionsTable : Table("user_actions") {
    val userId = varchar("user_id", 64) references UsersTable.id
    val movieId = long("movie_id") references MoviesTable.tmdbId
    val actionType = varchar("action_type", 20)
    val rating = integer("rating").nullable()
    val createdAt = datetime("created_at").nullable().clientDefault {
        Clock.System.now().toLocalDateTime(TimeZone.UTC)
    }

    override val primaryKey = PrimaryKey(userId, movieId, actionType, name = "user_actions_pkey")

    init {
        check("user_actions_action_type_check") { actionType inList listOf("WATCHLIST", "WATCHED") }
        check("user_actions_rating_check") { (rating greaterEq 1) and (rating lessEq 10) }
    }
}
