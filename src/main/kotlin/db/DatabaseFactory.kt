package com.milinko.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
            "jdbc:postgresql://$host:$port/$dbName?sslmode=require&prepareThreshold=0&reWriteBatchedInserts=true&application_name=movielens&options=-c%20search_path%3Dpublic"
        } else {
            // Direct connection (port 5432)
            "jdbc:postgresql://$host:$port/$dbName?sslmode=require&prepareThreshold=0&reWriteBatchedInserts=true"
        }

        println("[DATABASE] Povezujem: $host:$port (${if (isPooler) "Pooler" else "Direct"})")

        // Pool konekcija - bez njega Exposed otvara novu SSL konekciju ka Supabase-u za svaku
        // transakciju (stotine ms po upitu). Pool je mali jer Supabase pooler ograničava broj
        // klijentskih konekcija, a jedna instanca servera ne treba više.
        val dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            driverClassName = "org.postgresql.Driver"
            username = user
            this.password = password
            poolName = "movienee-db"
            maximumPoolSize = System.getenv("DB_POOL_SIZE")?.toIntOrNull()?.takeIf { it > 0 } ?: 5
            minimumIdle = 1
            connectionTimeout = 10_000
            // Pooler/Postgres zatvaraju neaktivne konekcije - Hikari ih osvežava pre toga.
            idleTimeout = 5 * 60_000
            keepaliveTime = 2 * 60_000
            maxLifetime = 15 * 60_000
            validate()
        })
        Database.connect(dataSource)
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