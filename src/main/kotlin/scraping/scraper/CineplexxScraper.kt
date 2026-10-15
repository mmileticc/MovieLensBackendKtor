package com.milinko.scraping.scraper

import com.milinko.scraping.agent.ScrapingConfig.SHOWTIME_DAYS
import com.milinko.scraping.agent.ScrapingConfig.USER_AGENT
import com.milinko.scraping.agent.ScrapingConfig.ZONE
import com.milinko.scraping.locations.CinemaLocations
import com.milinko.scraping.model.ScrapedMovie
import com.milinko.scraping.model.ScrapedShowtime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

private val logger = LoggerFactory.getLogger("CineplexxScraper")

/** A movie in the API's lists (only the fields used here). */
@Serializable
internal data class CineplexxMovie(
    val id: String,
    val titleCalculated: String? = null,
    val title: String? = null,
    val titleOriginalCalculated: String? = null,
    val shortURL: String? = null,
)

/** One day of a movie's screenings. */
@Serializable
internal data class CineplexxSessionDay(val sessions: List<CineplexxSession> = emptyList())

@Serializable
internal data class CineplexxSession(
    val id: String,
    val cinemaName: String,
    val screenName: String? = null,
    val showtime: String,
    val technologies: List<List<String>> = emptyList(),
)

/**
 * Cineplexx (7 cinemas), cineplexx.rs. The website is a React app that loads everything from a JSON
 * API (`app.cineplexx.rs`, the same data the site shows publicly), which is read directly - much
 * faster and more reliable than rendering the site:
 * - `/api/v2/movies?location=all&date=D` - the movies playing on day D (read for every day of the
 *   showtime window; a movie on today's list is "now showing");
 * - `/api/v3/moviesweb/{id}/sessions?location=all` - a movie's screenings at every cinema, by day;
 * - `/api/v2/movies/coming-soon?location=all` - coming soon.
 *
 * One row per (movie, cinema), like the other multi-location chains. The API is undocumented and
 * may change: if it fails, [CineplexxBrowserScraper] reads the rendered site instead (without
 * showtimes).
 */
class CineplexxScraper(
    private val fallback: CinemaScraper = CineplexxBrowserScraper(),
) : CinemaScraper {
    override val cinemaName = "Cineplexx"
    private val siteUrl = "https://www.cineplexx.rs"
    private val apiUrl = "https://app.cineplexx.rs/api"
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun scrape(date: LocalDate): List<ScrapedMovie> {
        val result = try {
            scrapeApi(date)
        } catch (e: Exception) {
            logger.warn("[$cinemaName] API failed: ${e.message}")
            emptyList()
        }
        if (result.isNotEmpty()) return result
        logger.warn("[$cinemaName] No data from the API - falling back to the website (without showtimes)")
        return fallback.scrape(date)
    }

    private fun scrapeApi(date: LocalDate): List<ScrapedMovie> {
        val now = LocalDateTime.now(ZONE)

        // Movies of every day in the window, in the order they first appear; today's are now showing.
        val movies = LinkedHashMap<String, CineplexxMovie>()
        var today = emptySet<String>()
        for (offset in 0 until SHOWTIME_DAYS) {
            val day = date.plusDays(offset.toLong())
            val list = get<List<CineplexxMovie>>("$apiUrl/v2/movies?location=all&date=$day") ?: continue
            if (offset == 0) today = list.map { it.id }.toSet()
            list.forEach { movies.putIfAbsent(it.id, it) }
        }
        if (today.isEmpty()) return emptyList()

        val rows = movies.values.flatMap { movie ->
            val days = get<List<CineplexxSessionDay>>("$apiUrl/v3/moviesweb/${movie.id}/sessions?location=all").orEmpty()
            val byCinema = days.flatMap { it.sessions }.groupBy { it.cinemaName.trim() }
            val isUpcoming = movie.id !in today
            if (byCinema.isEmpty()) return@flatMap listOf(row(movie, ScrapedMovie.NO_LOCATION, isUpcoming))
            byCinema.map { (cinema, sessions) ->
                row(movie, cinema.uppercase(), isUpcoming).copy(
                    showtimes = sessions.mapNotNull { it.toShowtime(date, now) }.sortedBy { it.startsAt }
                )
            }
        }

        // Coming soon: only movies that are not in the window already.
        val comingSoon = get<List<CineplexxMovie>>("$apiUrl/v2/movies/coming-soon?location=all").orEmpty()
            .filter { it.id !in movies }
            .map { row(it, ScrapedMovie.NO_LOCATION, isUpcoming = true) }

        logger.info("[$cinemaName] ${rows.size} rows (+${comingSoon.size} coming soon), ${rows.sumOf { it.showtimes.size }} showtimes")
        return rows + comingSoon
    }

    private fun row(movie: CineplexxMovie, location: String, isUpcoming: Boolean): ScrapedMovie {
        val title = (movie.titleCalculated ?: movie.title).orEmpty().trim()
        val coordinates = if (location == ScrapedMovie.NO_LOCATION) null else CinemaLocations.findCoordinates(location)
        return ScrapedMovie(
            title = title,
            originalTitle = movie.titleOriginalCalculated?.trim()?.takeIf { it.isNotEmpty() && it != title },
            cinemaName = cinemaName,
            cinemaLocation = location,
            latitude = coordinates?.lat,
            longitude = coordinates?.lon,
            bookingUrl = movie.shortURL?.let { "$siteUrl/film/$it" } ?: "$siteUrl/film",
            isUpcoming = isUpcoming,
        )
    }

    /** A screening in the window that has not started yet; the API sends local time with an offset. */
    private fun CineplexxSession.toShowtime(from: LocalDate, now: LocalDateTime): ScrapedShowtime? {
        val start = runCatching { OffsetDateTime.parse(showtime).atZoneSameInstant(ZONE).toLocalDateTime() }
            .getOrNull() ?: return null
        if (start.isBefore(now) || !start.toLocalDate().isBefore(from.plusDays(SHOWTIME_DAYS.toLong()))) return null
        return ScrapedShowtime(
            startsAt = start.withSecond(0).withNano(0).toString(),
            hall = screenName?.trim()?.ifEmpty { null },
            // [["3D"], ["SINH"]] -> "3D - SINH"
            format = technologies.flatten().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" - ").ifEmpty { null },
            bookingUrl = "$siteUrl/purchase/wizard/$id",
        )
    }

    private inline fun <reified T> get(url: String): T? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                logger.warn("[$cinemaName] HTTP ${response.statusCode()} for $url")
                null
            } else {
                json.decodeFromString<T>(response.body())
            }
        } catch (e: Exception) {
            logger.warn("[$cinemaName] Failed to read $url: ${e.message}")
            null
        }
    }
}
