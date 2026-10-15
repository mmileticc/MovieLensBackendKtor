package com.milinko.db


import com.milinko.db.DatabaseFactory.dbQuery
import com.milinko.scraping.model.ScrapedMovie
import com.milinko.util.CINEMA_TIME_ZONE
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ScrapedMoviesTable")

/**
 * Current cinema programme, one row per (movie, cinema location), written by the daily scraper.
 * `tmdb_id` is resolved after scraping and is null when no TMDB match was found.
 */
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
    // Explicit UTC default (instead of a database default not visible here), like every other
    // table. Freshness checks (getLatestScrapeDate) rely on this being UTC.
    val createdAt = datetime("created_at").clientDefault { Clock.System.now().toLocalDateTime(TimeZone.UTC) }
    override val primaryKey = PrimaryKey(id)

    init {
        // The index already exists in the database; mapped here so Exposed does not report it as unknown.
        index("idx_scraped_tmdb_id", false, tmdbId)
    }
}

/** Reads and writes the scraped cinema programme. */
class ScrapedMoviesRepository {

    /**
     * Replaces the programme of every cinema that returned movies in this run, together with its
     * showtimes (`showtimes`), in one transaction.
     *
     * Only those cinemas are replaced, not the whole table: if one cinema's site failed tonight
     * (timeout, layout change), its rows from the previous day stay visible instead of the cinema
     * disappearing from the app until the scraper works again.
     */
    suspend fun insertScrapedMovies(moviesList: List<ScrapedMovie>) = dbQuery {
        val bioskopiZaZamenu = moviesList.map { it.cinemaName }.distinct()
        if (bioskopiZaZamenu.isEmpty()) {
            logger.info("[DATABASE] Nijedan scraper nije vratio nijedan film ovog ciklusa - baza se ne dira, stari podaci ostaju.")
            return@dbQuery
        }

        val obrisanoRedova = ScrapedMoviesTable.deleteWhere { ScrapedMoviesTable.cinemaName inList bioskopiZaZamenu }
        val obrisanoTermina = ShowtimesTable.deleteWhere { ShowtimesTable.cinemaName inList bioskopiZaZamenu }
        logger.info("[DATABASE] Obrisano starih zapisa za bioskope $bioskopiZaZamenu: $obrisanoRedova filmova, $obrisanoTermina termina")

        // One batch instead of ~500 single INSERTs (with reWriteBatchedInserts in the JDBC URL the
        // driver turns it into multi-row INSERT statements).
        ScrapedMoviesTable.batchInsert(moviesList, shouldReturnGeneratedValues = false) { movie ->
            this[ScrapedMoviesTable.title] = movie.title
            this[ScrapedMoviesTable.originalTitle] = movie.originalTitle
            this[ScrapedMoviesTable.cinemaName] = movie.cinemaName
            this[ScrapedMoviesTable.cinemaLocation] = movie.cinemaLocation
            this[ScrapedMoviesTable.latitude] = movie.latitude
            this[ScrapedMoviesTable.longitude] = movie.longitude
            this[ScrapedMoviesTable.bookingUrl] = movie.bookingUrl
            this[ScrapedMoviesTable.isUpcoming] = movie.isUpcoming
            this[ScrapedMoviesTable.tmdbId] = movie.tmdbId
        }
        // Showtimes (the scrapers already left out past ones); an unparsable time is skipped and long
        // texts are cut to the column size.
        val termini = moviesList.flatMap { movie ->
            movie.showtimes.mapNotNull { showtime ->
                val start = runCatching { java.time.LocalDateTime.parse(showtime.startsAt) }.getOrNull()
                    ?: return@mapNotNull null
                Triple(movie, showtime, start)
            }
        }
        ShowtimesTable.batchInsert(termini, shouldReturnGeneratedValues = false) { (movie, showtime, start) ->
            this[ShowtimesTable.tmdbId] = movie.tmdbId
            this[ShowtimesTable.movieTitle] = movie.title.take(255)
            this[ShowtimesTable.cinemaName] = movie.cinemaName.take(100)
            this[ShowtimesTable.cinemaLocation] = movie.cinemaLocation.take(255)
            this[ShowtimesTable.startsAt] = start.toKotlinLocalDateTime()
            this[ShowtimesTable.hall] = showtime.hall?.take(100)
            this[ShowtimesTable.format] = showtime.format?.take(100)
            this[ShowtimesTable.bookingUrl] = showtime.bookingUrl
            this[ShowtimesTable.priceRsd] = showtime.priceRsd
        }
        logger.info("[DATABASE] Skrapovani filmovi (${moviesList.size}) i termini (${termini.size}) za ${bioskopiZaZamenu.size} bioskopa su uspešno sačuvani u Supabase!")
    }

    /** True if no programme has been stored yet. */
    suspend fun isDatabaseEmpty(): Boolean = dbQuery {
        ScrapedMoviesTable.selectAll().limit(1).empty()
    }

    /**
     * The newest `created_at` in the table (UTC). Uses MAX() because, after per-cinema replacement,
     * rows of different ages coexist (a cinema that failed keeps yesterday's rows).
     * Also used to detect new data for refreshing the semantic index.
     */
    suspend fun getLatestScrapeTimestamp(): LocalDateTime? = dbQuery {
        val maxCreatedAt = ScrapedMoviesTable.createdAt.max()
        ScrapedMoviesTable.select(maxCreatedAt).singleOrNull()?.get(maxCreatedAt)
    }

    /** Calendar day (Belgrade time) of the newest scrape, or null if the table is empty. */
    suspend fun getLatestScrapeDate(): java.time.LocalDate? {
        // created_at is UTC wall-clock time: interpret it as a UTC instant and convert to a
        // Belgrade calendar day. Taking the date part of the UTC value directly would roll the day
        // over 1-2 hours after the real midnight in Belgrade and trigger an unnecessary re-scrape.
        val najnoviji = getLatestScrapeTimestamp() ?: return null
        return najnoviji.toInstant(TimeZone.UTC).toLocalDateTime(CINEMA_TIME_ZONE).date.toJavaLocalDate()
    }

    /** The whole current programme (all cinemas). */
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

    /** Every cinema currently showing (or about to show) the movie, for the movie detail screen. */
    suspend fun getShowtimesForMovie(tmdbId: Int): List<ScrapedMovie> = dbQuery {
        ScrapedMoviesTable.selectAll().where { ScrapedMoviesTable.tmdbId eq tmdbId }.map { row ->
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