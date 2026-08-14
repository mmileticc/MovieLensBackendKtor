package com.milinko.db


import com.milinko.db.DatabaseFactory.dbQuery
import com.milinko.scraping.model.ScrapedMovie
import com.milinko.util.CINEMA_TIME_ZONE
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll

// 1. Mapiranje tabele iz baze u Kotlin objekat
object ScrapedMoviesTable : Table("scraped_movies") {
    val id = integer("id").autoIncrement()
    val title = varchar("title", 255)
    val originalTitle = varchar("original_title", 255).nullable()
    val cinemaName = varchar("cinema_name", 100)
    val cinemaLocation = varchar("cinema_location", 255)
    val latitude = double("latitude").nullable()
    val longitude = double("longitude").nullable()
    val bookingUrl = text("booking_url")
    val isUpcoming = bool("is_upcoming").default(false)

    val tmdbId = integer("tmdb_id").nullable()
    // Eksplicitan UTC default u Kotlinu (umesto oslanjanja na podrazumevanu vrednost kolone u
    // Postgres-u koja nije vidljiva ovde) - dosledno svim ostalim *Table.kt fajlovima. Poređenje
    // datuma u Application.kt (configureScrapingLifecycle) pretpostavlja da je ovo UTC.
    val createdAt = datetime("created_at").clientDefault { Clock.System.now().toLocalDateTime(TimeZone.UTC) }
    override val primaryKey = PrimaryKey(id)
}

// 2. Klasa koja sadrži logiku za rad sa podacima
class ScrapedMoviesRepository {

    suspend fun insertScrapedMovies(moviesList: List<ScrapedMovie>) = dbQuery {
        val obrisanoRedova = ScrapedMoviesTable.deleteAll()
        println("[DATABASE] Obrisano starih zapisa iz baze: $obrisanoRedova")

        moviesList.forEach { movie ->
            ScrapedMoviesTable.insert { tableRow ->
                tableRow[title] = movie.title
                tableRow[originalTitle] = movie.originalTitle
                tableRow[cinemaName] = movie.cinemaName
                tableRow[cinemaLocation] = movie.cinemaLocation
                tableRow[latitude] = movie.latitude
                tableRow[longitude] = movie.longitude
                tableRow[bookingUrl] = movie.bookingUrl
                tableRow[isUpcoming] = movie.isUpcoming
                tableRow[tmdbId] = movie.tmdbId
            }
        }
        println("[DATABASE] Skrapovani filmovi (${moviesList.size}) su uspešno sačuvani u Supabase!")
    }

    suspend fun isDatabaseEmpty(): Boolean = dbQuery {
        ScrapedMoviesTable.selectAll().limit(1).empty()
    }

    suspend fun getLatestScrapeDate(): java.time.LocalDate? = dbQuery {
        val firstRow = ScrapedMoviesTable.selectAll().limit(1).singleOrNull()
        if (firstRow != null) {
            // createdAt je sačuvan kao UTC wall-clock (clientDefault iznad). Prvo ga tumačimo kao
            // UTC Instant, pa ga prevodimo u beogradski kalendarski dan - ne uzimamo datumski deo
            // direktno iz UTC vrednosti (to je bio uzrok bug-a: dan bi se "prevrnuo" 1-2h kasnije
            // nego što je stvarno ponoć u Beogradu, pa bi se skrejpovanje ponovo pokretalo).
            val kotlinxDateTime = firstRow[ScrapedMoviesTable.createdAt]
            kotlinxDateTime.toInstant(TimeZone.UTC).toLocalDateTime(CINEMA_TIME_ZONE).date.toJavaLocalDate()
        } else {
            null
        }
    }

    // 2. Čitanje svih filmova iz baze (ako ti zatrebaju da ih vratiš kroz rutu)
    suspend fun getAllScrapedMovies(): List<ScrapedMovie> = dbQuery {
        ScrapedMoviesTable.selectAll().map { row ->
            ScrapedMovie(
                title = row[ScrapedMoviesTable.title],
                originalTitle = row[ScrapedMoviesTable.originalTitle],
                cinemaName = row[ScrapedMoviesTable.cinemaName],
                cinemaLocation = row[ScrapedMoviesTable.cinemaLocation],
                latitude = row[ScrapedMoviesTable.latitude],
                longitude = row[ScrapedMoviesTable.longitude],
                bookingUrl = row[ScrapedMoviesTable.bookingUrl],
                isUpcoming = row[ScrapedMoviesTable.isUpcoming],
                tmdbId = row[ScrapedMoviesTable.tmdbId]
            )
        }
    }

    /**
     * Svi bioskopski termini za konkretan film (Movie Detail ekran - "Bioskopski termini"
     * sekcija). `tmdbId eq tmdbId` unutar select{} bloka - eq je SqlExpressionBuilder član, radi
     * preko implicitnog receiver-a, bez potrebe za importom (isti obrazac kao ostatak baze).
     */
    suspend fun getShowtimesForMovie(tmdbId: Int): List<ScrapedMovie> = dbQuery {
        ScrapedMoviesTable.select { ScrapedMoviesTable.tmdbId eq tmdbId }.map { row ->
            ScrapedMovie(
                title = row[ScrapedMoviesTable.title],
                originalTitle = row[ScrapedMoviesTable.originalTitle],
                cinemaName = row[ScrapedMoviesTable.cinemaName],
                cinemaLocation = row[ScrapedMoviesTable.cinemaLocation],
                latitude = row[ScrapedMoviesTable.latitude],
                longitude = row[ScrapedMoviesTable.longitude],
                bookingUrl = row[ScrapedMoviesTable.bookingUrl],
                isUpcoming = row[ScrapedMoviesTable.isUpcoming],
                tmdbId = row[ScrapedMoviesTable.tmdbId]
            )
        }
    }
}