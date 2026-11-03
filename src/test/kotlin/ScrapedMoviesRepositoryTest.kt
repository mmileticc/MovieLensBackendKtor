package com.milinko

import com.milinko.db.ScrapedMoviesRepository
import com.milinko.db.ScrapedMoviesTable
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ScrapedMoviesRepositoryTest {

    @Test
    fun `newest scrape day per cinema, in Belgrade time`() = runBlocking {
        val db = Database.connect("jdbc:h2:mem:scraped${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        // Queries go to the default database; pin it to this one for the test, then let go.
        TransactionManager.defaultDatabase = db
        try {
            transaction(db) {
                SchemaUtils.create(ScrapedMoviesTable)
                fun row(cinema: String, utc: LocalDateTime) = ScrapedMoviesTable.insert {
                    it[title] = "M"; it[cinemaName] = cinema; it[cinemaLocation] = cinema; it[bookingUrl] = "https://x"
                    it[createdAt] = utc
                }
                row("Arena Cineplex", LocalDateTime(2026, 11, 3, 4, 20))
                row("Arena Cineplex", LocalDateTime(2026, 11, 2, 4, 20))
                // Failed today: its newest rows are from yesterday.
                row("Tuckwood Cineplex", LocalDateTime(2026, 11, 2, 4, 20))
                // 23:30 UTC on 2 Nov is already 3 Nov in Belgrade (UTC+1 in November).
                row("CineStar", LocalDateTime(2026, 11, 2, 23, 30))
            }

            val days = ScrapedMoviesRepository().getLatestScrapeDatesByCinema()

            assertEquals(LocalDate.of(2026, 11, 3), days["Arena Cineplex"])
            assertEquals(LocalDate.of(2026, 11, 2), days["Tuckwood Cineplex"])
            assertEquals(LocalDate.of(2026, 11, 3), days["CineStar"])
            assertEquals(null, days["Cineplexx"])
        } finally {
            TransactionManager.defaultDatabase = null
        }
    }
}
