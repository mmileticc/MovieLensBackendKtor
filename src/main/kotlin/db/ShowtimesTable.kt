package com.milinko.db

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * Screenings of the next days, one row per screening, written together with `scraped_movies` by the
 * daily scraper (same per-cinema replacement). `starts_at` is the local time in Belgrade, as the
 * cinemas publish it; `tmdb_id` is copied from the matched movie and null when there was no match.
 */
object ShowtimesTable : Table("showtimes") {
    val id = integer("id").autoIncrement()
    val tmdbId = integer("tmdb_id").nullable()
    val movieTitle = varchar("movie_title", 255)
    val cinemaName = varchar("cinema_name", 100)
    val cinemaLocation = varchar("cinema_location", 255)
    val startsAt = datetime("starts_at")
    val hall = varchar("hall", 100).nullable()
    val format = varchar("format", 100).nullable()
    val bookingUrl = text("booking_url").nullable()
    val priceRsd = integer("price_rsd").nullable()
    val createdAt = datetime("created_at").clientDefault { Clock.System.now().toLocalDateTime(TimeZone.UTC) }
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_showtimes_tmdb_starts", false, tmdbId, startsAt)
    }
}

/** One screening of a movie, as the movie detail API returns it. */
data class ShowtimeSlot(
    val cinemaLocation: String,
    val startsAt: kotlinx.datetime.LocalDateTime,
    val hall: String?,
    val format: String?,
    val bookingUrl: String?,
    val priceRsd: Int?,
)

/** Reads the stored screenings (they are written by [ScrapedMoviesRepository.insertScrapedMovies]). */
class ShowtimesRepository {

    /** Screenings of a movie from now on (Belgrade time), earliest first. */
    suspend fun upcomingForMovie(tmdbId: Int): List<ShowtimeSlot> = DatabaseFactory.dbQuery {
        val now = Clock.System.now().toLocalDateTime(com.milinko.util.CINEMA_TIME_ZONE)
        ShowtimesTable.selectAll()
            .where { (ShowtimesTable.tmdbId eq tmdbId) and (ShowtimesTable.startsAt greaterEq now) }
            .orderBy(ShowtimesTable.startsAt)
            .map { row ->
                ShowtimeSlot(
                    cinemaLocation = row[ShowtimesTable.cinemaLocation],
                    startsAt = row[ShowtimesTable.startsAt],
                    hall = row[ShowtimesTable.hall],
                    format = row[ShowtimesTable.format],
                    bookingUrl = row[ShowtimesTable.bookingUrl],
                    priceRsd = row[ShowtimesTable.priceRsd],
                )
            }
    }
}
