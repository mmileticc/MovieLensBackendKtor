package com.milinko.scraping.scraper

import com.milinko.scraping.agent.ScrapingConfig.SHOWTIME_DAYS
import com.milinko.scraping.agent.ScrapingConfig.USER_AGENT
import com.milinko.scraping.agent.ScrapingConfig.ZONE
import com.milinko.scraping.locations.CinemaLocations
import com.milinko.scraping.model.ScrapedMovie
import com.milinko.scraping.model.ScrapedShowtime
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private val logger = LoggerFactory.getLogger("CineStarScraper")

private val TIME = Regex("""(\d{1,2}):(\d{2})""")

/**
 * CineStar (4 locations), cinestarcinemas.rs: classic server-rendered pages (jQuery + Bootstrap),
 * read with Jsoup.
 *
 * Every location has its own page (e.g. "/novi-sad-big") with its complete programme: `.movie-item`
 * cards with the title and, per day, a `.day-wrapper` ("sreda 21.10.") holding one `a.perf` link per
 * screening (format "4DX - TITL", time, hall, ticket page). The original title is only on the movie's
 * own page ("Izvorni naslov"), so that page is read once per unique movie - not per (movie, location),
 * since the same movie often plays at all four locations.
 *
 * The location pages were taken from the "Izaberi svoj CineStar" dropdown (`<select id="kino">`)
 * on cinestarcinemas.rs/program-svi-bioskopi.
 */
class CineStarScraper : CinemaScraper {
    override val cinemaName = "CineStar"
    private val baseUrl = "https://cinestarcinemas.rs"

    // Location page path -> location name (the key used in CinemaLocations).
    private val locations = mapOf(
        "/beograd-concept-cinema-ada-mall" to "CINESTAR BEOGRAD ADA MALL",
        "/novi-sad-big" to "CINESTAR NOVI SAD BIG",
        "/pancevo-big" to "CINESTAR PANČEVO BIG",
        "/zrenjanin-big" to "CINESTAR ZRENJANIN BIG"
    )

    override fun scrape(date: LocalDate): List<ScrapedMovie> {
        val now = LocalDateTime.now(ZONE)
        val rawMovies = locations.flatMap { (path, locationName) ->
            val doc = fetch("$baseUrl$path") ?: return@flatMap emptyList()
            parseLocationPage(doc, locationName, date, now).also { movies ->
                logger.info("[$cinemaName] [$locationName] Movies: ${movies.size}, showtimes: ${movies.sumOf { it.showtimes.size }}")
            }
        }
        return try {
            enrichWithOriginalTitles(rawMovies)
        } catch (e: Exception) {
            // Losing the original titles must not lose the whole run.
            logger.warn("[$cinemaName] Adding original titles failed - returning the movies without them: ${e.message}")
            rawMovies
        }
    }

    /** The programme of one location, with the screenings in the window that have not started yet. */
    internal fun parseLocationPage(doc: Document, locationName: String, date: LocalDate, now: LocalDateTime): List<ScrapedMovie> {
        val coordinates = CinemaLocations.findCoordinates(locationName)
        return doc.select(".movie-item").mapNotNull { card ->
            val bookingUrl = card.selectFirst("a.poster-wrapper")?.attr("href")?.trim().orEmpty()
            val heading = card.selectFirst(".movie-desc h2") ?: return@mapNotNull null
            if (bookingUrl.isEmpty()) return@mapNotNull null

            // A ".filmlabel" badge ("Pretpremijera", "Uskoro") marks a movie that has not opened
            // yet; it is removed from the title text.
            val label = heading.selectFirst(".filmlabel")
            label?.remove()
            val title = heading.text().trim()
            if (title.isEmpty()) return@mapNotNull null

            ScrapedMovie(
                title = title,
                originalTitle = null,
                cinemaName = cinemaName,
                cinemaLocation = locationName,
                latitude = coordinates?.lat,
                longitude = coordinates?.lon,
                bookingUrl = bookingUrl,
                isUpcoming = label != null,
                showtimes = parseShowtimes(card, date, now),
            )
        }
    }

    private fun parseShowtimes(card: Element, from: LocalDate, now: LocalDateTime): List<ScrapedShowtime> {
        val until = from.plusDays(SHOWTIME_DAYS.toLong())
        return card.select(".day-wrapper").flatMap { dayBlock ->
            val day = parseNumericDate(dayBlock.selectFirst(".day")?.text().orEmpty(), from)
                ?.takeIf { !it.isBefore(from) && it.isBefore(until) }
                ?: return@flatMap emptyList()
            dayBlock.select("a.perf").mapNotNull { perf -> parseScreening(perf, day, now) }
        }.sortedBy { it.startsAt }
    }

    /** `<a class="perf" href=ticket><div class="format">4DX - TITL</div><div class="time">19:00</div><div class="venue">Sala 2</div></a>` */
    private fun parseScreening(perf: Element, day: LocalDate, now: LocalDateTime): ScrapedShowtime? {
        val time = TIME.find(perf.selectFirst(".time")?.text().orEmpty()) ?: return null
        val (hour, minute) = time.destructured.toList().map { it.toInt() }
        if (hour > 23 || minute > 59) return null
        val startsAt = LocalDateTime.of(day, LocalTime.of(hour, minute))
        if (startsAt.isBefore(now)) return null
        return ScrapedShowtime(
            startsAt = startsAt.toString(),
            hall = perf.selectFirst(".venue")?.text()?.trim()?.ifEmpty { null },
            format = perf.selectFirst(".format")?.text()?.trim()?.ifEmpty { null },
            bookingUrl = perf.attr("href").trim().takeIf { it.startsWith("http") },
        )
    }

    /**
     * Adds the original title ("Izvorni naslov") from each movie's page. The same movie (same
     * numeric id at the end of bookingUrl, e.g. ".../hari-poter-i-kamen-mudrosti/6579") appears once
     * per location, so titles are cached by that id and each page is read only once.
     */
    private fun enrichWithOriginalTitles(movies: List<ScrapedMovie>): List<ScrapedMovie> {
        val originalTitlesById = mutableMapOf<String, String?>()
        return movies.map { movie ->
            val movieId = movie.bookingUrl.substringAfterLast("/")
            val originalTitle = originalTitlesById.getOrPut(movieId) {
                // A short pause between movie pages, to be gentle with the site.
                Thread.sleep((200..600).random().toLong())
                fetch(movie.bookingUrl)
                    ?.select(".movie-detail-item:has(span.gray:contains(Izvorni naslov)) span:not(.gray)")
                    ?.first()?.text()?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (originalTitle != null) movie.copy(originalTitle = originalTitle) else movie
        }
    }

    private fun fetch(url: String): Document? = try {
        Jsoup.connect(url).userAgent(USER_AGENT).timeout(15_000).get()
    } catch (e: IOException) {
        logger.warn("[$cinemaName] Failed to load $url: ${e.message}")
        null
    }
}
