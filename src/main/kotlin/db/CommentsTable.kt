package com.milinko.db

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * Javni komentari na film (E2.2 dorada, 3. septembar 2026, korisnički zahtev - "kao na normalnim
 * društvenim mrežama, više komentara po filmu, svi javno dostupni"). ZAMENJUJE stari
 * `UserActionsTable.review` koncept (jedan tekstualni utisak vezan za sopstvenu ocenu, vidljiv
 * samo prijateljima) - ovde je komentar potpuno nezavisan od ocene/watched statusa: bilo ko
 * komentariše bilo koji film, bez obzira da li ga je označio kao odgledan. `review` kolona na
 * `user_actions` ostaje netaknuta u bazi (mrtva, ne piše se više iz UI-ja) - nema migracije starih
 * podataka, videti napomenu u MOVIELENS_DESIGN_PLAN.md.
 *
 * Nova tabela (ne postoji u produkcionoj Supabase šemi pre ove izmene) - `createMissingTablesAndColumns`
 * (DatabaseFactory.kt) je kreira pri sledećem pokretanju servera, isti mehanizam kao za
 * `movie_embeddings` (Faza D).
 *
 * Nema `edited`/update mehanizam namerno - korisnik je eksplicitno tražio "briše, ne edituje".
 * `id` je surrogate autoincrement ključ (za razliku od većine ostalih tabela u projektu koje
 * koriste kompozitni PK) jer komentar mora imati stabilan identitet nezavisan od (user, movie) para
 * - isti korisnik može ostaviti VIŠE komentara na isti film, pa (user_id, movie_id) ne može biti
 * PK ovde (za razliku od `user_actions`).
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
