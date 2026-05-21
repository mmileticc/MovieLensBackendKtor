package com.milinko.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

object DatabaseFactory {
    fun init() {

        val host = System.getenv("DB_HOST")
            ?: throw IllegalStateException("DB_HOST nije postavljen u Environment Variables.")
        val port = System.getenv("DB_PORT") ?: "6543"
        val dbName = System.getenv("DB_NAME") ?: "postgres"
        val user = System.getenv("DB_USER") ?: "postgres"
        val password = System.getenv("DB_PASSWORD")
            ?: throw IllegalStateException("DB_PASSWORD nije postavljen u Environment Variables.")

          val jdbcUrl = "jdbc:postgresql://$host:$port/$dbName?sslmode=require&prepareThreshold=0"

        Database.connect(
            url = jdbcUrl,
            driver = "org.postgresql.Driver",
            user = user,
            password = password
        )
        println("[DATABASE] Uspešno uspostavljena veza sa Supabase PostgreSQL bazom!")
    }

    // Pomoćna funkcija koja obezbeđuje da se SQL upiti izvršavaju u pozadinskom threadu (IO)
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}