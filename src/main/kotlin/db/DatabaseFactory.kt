package com.milinko.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init() {

        val host = System.getenv("DB_HOST")
            ?: throw IllegalStateException("DB_HOST nije postavljen u Environment Variables.")
        val port = System.getenv("DB_PORT") ?: "6543"
        val dbName = System.getenv("DB_NAME") ?: "postgres"
        val user = System.getenv("DB_USER") ?: "postgres"
        val password = System.getenv("DB_PASSWORD")
            ?: throw IllegalStateException("DB_PASSWORD nije postavljen u Environment Variables.")

        // Za Supabase pooler.supabase.com - trebaju dodatni parametri
        val isPooler = host.contains("pooler.supabase.com")
        val jdbcUrl = if (isPooler) {
            // Connection pooler (port 6543) - zahteva extra parametri za SNI
            "jdbc:postgresql://$host:$port/$dbName?sslmode=require&prepareThreshold=0&application_name=movielens&options=-c%20search_path%3Dpublic"
        } else {
            // Direct connection (port 5432)
            "jdbc:postgresql://$host:$port/$dbName?sslmode=require&prepareThreshold=0"
        }

        println("[DATABASE] Povezujem: $host:$port (${if (isPooler) "Pooler" else "Direct"})")

        Database.connect(
            url = jdbcUrl,
            driver = "org.postgresql.Driver",
            user = user,
            password = password
        )
        println("[DATABASE] Uspešno uspostavljena veza sa Supabase PostgreSQL bazom!")

        // Šema se generiše direktno iz Exposed tabela — jedan izvor istine u kodu, bez ručnog
        // SQL-a koji vremenom ode iz sinhronizacije sa kodom. createMissingTablesAndColumns je
        // idempotentan: kreira ono što nedostaje (tabele/kolone), ne dira postojeće. NAPOMENA:
        // ne menja CHECK constraint-e na već postojećim tabelama — za to videti jednokratnu
        // migraciju u MOVIELENS_ROADMAP.md (user_actions: WATCHLIST/WATCHED + rating 1-10).
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                UsersTable,
                ScrapedMoviesTable,
                MoviesTable,
                UserActionsTable,
                FriendshipsTable,
                NotificationsTable,
                // Faza D dorada #2 (23. avgust 2026) - trajni keš embedding vektora, videti
                // MovieEmbeddingsTable.kt za obrazloženje.
                MovieEmbeddingsTable,
                // E2.2 dorada (3. septembar 2026) - javni komentari na film, videti
                // CommentsTable.kt/CommentLikesTable.kt za obrazloženje.
                CommentsTable,
                CommentLikesTable
            )
        }
        println("[DATABASE] Šema baze proverena/ažurirana (users, scraped_movies, movies, user_actions, friendships, notifications, movie_embeddings, movie_comments, comment_likes).")
    }

    // Pomoćna funkcija koja obezbeđuje da se SQL upiti izvršavaju u pozadinskom threadu (IO)
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}