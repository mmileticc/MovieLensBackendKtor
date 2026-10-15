package com.milinko.scraping.scraper

import com.milinko.scraping.agent.ScrapingConfig.SHOWTIME_DAYS
import com.milinko.scraping.agent.ScrapingConfig.USER_AGENT
import com.milinko.scraping.agent.ScrapingConfig.ZONE
import com.milinko.scraping.locations.CinemaLocations
import com.milinko.scraping.model.ScrapedMovie
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime

private val logger = LoggerFactory.getLogger("CineGrandScraper")

/**
 * Cine Grand (several locations), cinegrand-mcf.rs - the same Nuxt platform as MTS Dvorana, so
 * plain HTTP is enough (no browser): the programme (`/u-bioskopu`) and coming soon (`/uskoro`) list
 * the movies, and each movie's page carries its screenings at every location (see
 * [parseHappeningPage]). One row is produced per (movie, location), with that location's showtimes.
 */
class CineGrandScraper : CinemaScraper {
    override val cinemaName = "Cine Grand"
    private val baseUrl = "https://cinegrand-mcf.rs"

    override fun scrape(date: LocalDate): List<ScrapedMovie> {
        val now = LocalDateTime.now(ZONE)
        val nowShowing = fetch("$baseUrl/u-bioskopu")
            ?.let { parseMovies(it, isUpcoming = false, skipLinks = emptySet(), date, now) }
            .orEmpty()
        if (nowShowing.isEmpty()) logger.info("[$cinemaName] The programme returned 0 movies - check the selectors.")

        val programmeLinks = nowShowing.map { it.bookingUrl }.toSet()
        val comingSoon = fetch("$baseUrl/uskoro")
            ?.let { parseMovies(it, isUpcoming = true, skipLinks = programmeLinks, date, now) }
            .orEmpty()

        // De-duplicate per (movie, location); the programme wins.
        val byKey = nowShowing.associateBy { it.key() + it.cinemaLocation }.toMutableMap()
        val titlesPlaying = nowShowing.map { it.key() }.toSet()
        for (upcoming in comingSoon) {
            // A coming-soon movie without a location is added only if it plays nowhere yet.
            if (upcoming.cinemaLocation == ScrapedMovie.NO_LOCATION && upcoming.key() in titlesPlaying) continue
            byKey.putIfAbsent(upcoming.key() + upcoming.cinemaLocation, upcoming)
        }

        val result = byKey.values.toList()
        logger.info("[$cinemaName] ${result.size} rows, ${result.sumOf { it.showtimes.size }} showtimes")
        return result
    }

    private fun parseMovies(
        doc: Document,
        isUpcoming: Boolean,
        skipLinks: Set<String>,
        date: LocalDate,
        now: LocalDateTime,
    ): List<ScrapedMovie> {
        val section = if (isUpcoming) "COMING SOON" else "PROGRAMME"
        val cards = doc.select("a[href^=/film/]:has(h3)").distinctBy { it.attr("href") }
        logger.info("[$cinemaName] [$section] Movies on the page: ${cards.size}")

        return cards.flatMap { card ->
            val title = card.selectFirst("h3")?.text()?.trim().orEmpty()
            val link = baseUrl + card.attr("href").trim().substringBefore("?")
            if (title.isEmpty() || link in skipLinks) return@flatMap emptyList()

            // A short random pause between movie pages, to be gentle with the site.
            Thread.sleep((300..900).random().toLong())
            val page = fetch(link)?.let { parseHappeningPage(it) }
            if (page == null) logger.warn("[$cinemaName] No screening data on $link")
            val movieName = page?.name ?: title

            // One row per location with screenings (all of them, so a movie on pre-sale still shows
            // where it will play), each with its showtimes in the window.
            val byLocation = page?.projections.orEmpty().groupBy { it.locationName }
            if (byLocation.isEmpty()) {
                return@flatMap listOf(row(title, page?.originalName, ScrapedMovie.NO_LOCATION, link, isUpcoming))
            }
            byLocation.map { (location, projections) ->
                row(title, page?.originalName, location, link, isUpcoming).copy(
                    showtimes = projections.inWindow(date, SHOWTIME_DAYS, now).map { it.toShowtime(baseUrl, movieName) }
                )
            }
        }
    }

    private fun row(title: String, originalTitle: String?, location: String, link: String, isUpcoming: Boolean): ScrapedMovie {
        val coordinates = if (location == ScrapedMovie.NO_LOCATION) null else CinemaLocations.findCoordinates(location)
        return ScrapedMovie(
            title = title,
            originalTitle = originalTitle,
            cinemaName = cinemaName,
            cinemaLocation = location,
            latitude = coordinates?.lat,
            longitude = coordinates?.lon,
            bookingUrl = link,
            isUpcoming = isUpcoming,
        )
    }

    /** GET with one retry, so a single slow response does not lose a movie. */
    private fun fetch(url: String): Document? {
        repeat(2) { attempt ->
            try {
                return Jsoup.connect(url).userAgent(USER_AGENT).timeout(15_000).get()
            } catch (e: IOException) {
                logger.warn("[$cinemaName] Failed to load $url (attempt ${attempt + 1}/2): ${e.message}")
            }
        }
        return null
    }

    private fun ScrapedMovie.key() = originalTitle ?: title
}
