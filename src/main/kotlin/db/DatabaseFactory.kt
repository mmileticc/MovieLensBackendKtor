package com.milinko.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("DatabaseFactory")

/**
 * Database connection (HikariCP pool over JDBC) and schema bootstrap.
 *
 * Connection settings come from environment variables: `DB_HOST` and `DB_PASSWORD` are required;
 * `DB_PORT` (6543 = Supabase pooler, 5432 = direct), `DB_NAME`, `DB_USER`, `DB_SSLMODE` and
 * `DB_POOL_SIZE` are optional.
 */
object DatabaseFactory {
    /** Connects to the database and creates any missing tables/columns. Call once at startup. */
    fun init() {

        val host = System.getenv("DB_HOST")
            ?: throw IllegalStateException("DB_HOST nije postavljen u Environment Variables.")
        val port = System.getenv("DB_PORT") ?: "6543"
        val dbName = System.getenv("DB_NAME") ?: "postgres"
        val user = System.getenv("DB_USER") ?: "postgres"
        val password = System.getenv("DB_PASSWORD")
            ?: throw IllegalStateException("DB_PASSWORD nije postavljen u Environment Variables.")

        val isPooler = host.contains("pooler.supabase.com")
        // Supabase requires SSL; the local Postgres from docker-compose.yml has none (DB_SSLMODE=disable).
        val sslMode = System.getenv("DB_SSLMODE")?.trim()?.ifBlank { null } ?: "require"
        val jdbcUrl = if (isPooler) {
            // Supabase connection pooler (transaction mode, port 6543). prepareThreshold=0 because
            // pgBouncer in transaction mode cannot keep server-side prepared statements.
            "jdbc:postgresql://$host:$port/$dbName?sslmode=$sslMode&prepareThreshold=0&reWriteBatchedInserts=true&application_name=movielens&options=-c%20search_path%3Dpublic"
        } else {
            // Direct connection (port 5432), e.g. a local Postgres.
            "jdbc:postgresql://$host:$port/$dbName?sslmode=$sslMode&prepareThreshold=0&reWriteBatchedInserts=true"
        }

        logger.info("[DATABASE] Povezujem: $host:$port (${if (isPooler) "Pooler" else "Direct"})")

        // Without a pool, Exposed would open a new SSL connection for every transaction (hundreds
        // of milliseconds per query). The pool is small on purpose: the Supabase pooler limits
        // client connections and one server instance does not need more.
        val dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            driverClassName = "org.postgresql.Driver"
            username = user
            this.password = password
            poolName = "movienee-db"
            maximumPoolSize = System.getenv("DB_POOL_SIZE")?.toIntOrNull()?.takeIf { it > 0 } ?: 5
            minimumIdle = 1
            connectionTimeout = 10_000
            // The pooler/Postgres close idle connections; Hikari refreshes them before that happens.
            idleTimeout = 5 * 60_000
            keepaliveTime = 2 * 60_000
            maxLifetime = 15 * 60_000
            validate()
        })
        Database.connect(dataSource)
        logger.info("[DATABASE] Uspešno uspostavljena veza sa Supabase PostgreSQL bazom!")

        // The schema is generated from the Exposed table objects, so the code is the single source
        // of truth. createMissingTablesAndColumns is idempotent: it only adds missing tables and
        // columns. It does NOT change existing columns or CHECK constraints - such changes need a
        // manual SQL migration.
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                UsersTable,
                ScrapedMoviesTable,
                MoviesTable,
                UserActionsTable,
                FriendshipsTable,
                NotificationsTable,
                MovieEmbeddingsTable,
                CommentsTable,
                CommentLikesTable,
                UserAvatarsTable,
                DeviceTokensTable
            )
        }
        logger.info("[DATABASE] Šema baze proverena/ažurirana (users, scraped_movies, movies, user_actions, friendships, notifications, movie_embeddings, movie_comments, comment_likes, user_avatars, device_tokens).")
    }

    /** Runs [block] in a database transaction on the IO dispatcher, so queries never block request threads. */
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}