package com.milinko.scraping.scraper

import com.milinko.scraping.agent.ScrapingConfig.SHOWTIME_DAYS
import com.milinko.scraping.agent.ScrapingConfig.USER_AGENT
import com.milinko.scraping.agent.ScrapingConfig.ZONE
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

private val logger = LoggerFactory.getLogger("TuckwoodCineplexScraper")

/** "Marilyn Monroe 18:00" -> the time at the end. */
private val TIME_AT_END = Regex("""(\d{1,2}):(\d{2})\s*$""")

/**
 * Tuckwood Cineplex (Kneza Miloša 7a, Belgrade), tuck.rs: a server-rendered WordPress site
 * (WPBakery + the "Amy Movie" plugin), so - like Arena/Roda - plain Jsoup is enough.
 *
 * Movie cards (".amy-movie-item"):
 * - the title is always "Serbian title / Original title" in "h3.amy-movie-field-title a"; it is
 *   split on " / " (with spaces) so a title containing a bare "/" is not cut;
 * - the link to the movie page is the same href as on the title/poster ("/amy_movie/.../");
 * - showtimes: ".amy-movie-item-showtimes .amy-cell", one cell per day with the date in ".amy-head"
 *   ("15. oktobar 2026."), one "Hall HH:MM" line per screening (the halls are named after actors,
 *   e.g. "Marilyn Monroe 18:00") and a "Rezerviši" link (per movie, not per screening).
 *
 * One fixed location.
 */
class TuckwoodCineplexScraper : CinemaScraper {
    override val cinemaName = "Tuckwood Cineplex"
    private val baseUrl = "https://www.tuck.rs"
    private val location = "TUCKWOOD CINEPLEX"
    // Approximate coordinates of Kneza Miloša 7a, Belgrade.
    private val latitude = 44.80984832581882
    private val longitude = 20.465467427357588

    private val timeoutMs = (System.getenv("TUCKWOOD_TIMEOUT_MS")?.toIntOrNull() ?: 25000)

    override fun scrape(date: LocalDate): List<ScrapedMovie> {
        val nowShowing = mutableListOf<ScrapedMovie>()
        val comingSoon = mutableListOf<ScrapedMovie>()

        val programmeUrl = "$baseUrl/repertoar/"
        val comingSoonUrl = "$baseUrl/uskoro/"

        // 1. Current programme (isUpcoming = false)
        val programmeDoc = fetchWithRetry(programmeUrl, "PROGRAMME")
        if (programmeDoc != null) {
            val programmeMovies = parseMoviesFromDoc(programmeDoc, isUpcoming = false, date)
            if (programmeMovies.isEmpty()) {
                logger.info("[$cinemaName] The programme returned 0 movies - check the selectors.")
            }
            nowShowing.addAll(programmeMovies)
        }

        // 2. "Coming soon" section (isUpcoming = true)
        val comingSoonDoc = fetchWithRetry(comingSoonUrl, "COMING SOON")
        if (comingSoonDoc != null) {
            comingSoon.addAll(parseMoviesFromDoc(comingSoonDoc, isUpcoming = true, date))
        }

        // 3. De-duplicate within this cinema (same as Arena/Roda Cineplex).
        val byTitle = nowShowing.associateBy { it.originalTitle ?: it.title }.toMutableMap()

        for (upcoming in comingSoon) {
            val key = upcoming.originalTitle ?: upcoming.title
            if (!byTitle.containsKey(key)) {
                byTitle[key] = upcoming
            } else {
                logger.info("[$cinemaName] [DEDUP] Skipping '$key' from coming soon - already in the programme.")
            }
        }

        return byTitle.values.toList()
    }

    /**
     * Jsoup GET with one retry: a single "Read timed out" on this slower server should not leave
     * the cinema without data for the whole day. Returns null only if both attempts fail; the
     * caller then skips that section instead of throwing.
     */
    private fun fetchWithRetry(url: String, section: String): Document? {
        val maxAttempts = 2

        repeat(maxAttempts) { index ->
            val attempt = index + 1
            try {
                return Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(timeoutMs)
                    .get()
            } catch (e: IOException) {
                val suffix = if (attempt < maxAttempts) " - retrying" else ""
                logger.warn("[$cinemaName] [$section] Request failed (attempt $attempt/$maxAttempts, timeout ${timeoutMs}ms): ${e.message}$suffix")
            }
        }

        return null
    }

    internal fun parseMoviesFromDoc(
        doc: Document,
        isUpcoming: Boolean,
        date: LocalDate,
        now: LocalDateTime = LocalDateTime.now(ZONE),
    ): List<ScrapedMovie> {
        val result = mutableListOf<ScrapedMovie>()
        val cards = doc.select(".amy-movie-item")

        for (card in cards) {
            val titleLink = card.select("h3.amy-movie-field-title a").first()
            val fullTitle = titleLink?.text()?.trim().orEmpty()
            var link = titleLink?.attr("href")?.trim().orEmpty()

            if (link.isEmpty()) {
                link = card.select(".amy-movie-item-poster a").first()?.attr("href")?.trim().orEmpty()
            }

            if (fullTitle.isEmpty() || link.isEmpty()) continue

            val title = fullTitle.substringBeforeLast(" / ").trim()
            val originalTitle = fullTitle.substringAfterLast(" / ").trim()
                .takeIf { it.isNotEmpty() && it != title }

            if (title.isEmpty()) continue

            val section = if (isUpcoming) "COMING SOON" else "PROGRAMME"
            logger.info("[$cinemaName] [$section] Found: $title")

            result.add(
                ScrapedMovie(
                    title = title,
                    originalTitle = originalTitle,
                    cinemaName = cinemaName,
                    bookingUrl = link,
                    cinemaLocation = location,
                    latitude = latitude,
                    longitude = longitude,
                    isUpcoming = isUpcoming,
                    showtimes = parseShowtimes(card, date, now).map { it.copy(format = formatFromTitle(fullTitle)) }
                )
            )
        }
        return result
    }

    /** Screenings in the [SHOWTIME_DAYS] days from [from] that have not started yet. */
    private fun parseShowtimes(card: Element, from: LocalDate, now: LocalDateTime): List<ScrapedShowtime> {
        val until = from.plusDays(SHOWTIME_DAYS.toLong())
        return card.select(".amy-movie-item-showtimes .amy-cell").flatMap { cell ->
            val day = parseSerbianDate(cell.select(".amy-head > div").text(), from)
                ?.takeIf { !it.isBefore(from) && it.isBefore(until) } ?: return@flatMap emptyList()
            val booking = cell.selectFirst(".amy-intro-times a")?.attr("href")?.trim()?.takeIf { it.startsWith("http") }
            cell.select(".amy-intro-times > div").mapNotNull { line ->
                val text = line.text().trim()
                val time = TIME_AT_END.find(text) ?: return@mapNotNull null
                val (hour, minute) = time.destructured.toList().map { it.toInt() }
                if (hour > 23 || minute > 59) return@mapNotNull null
                val startsAt = LocalDateTime.of(day, LocalTime.of(hour, minute))
                if (startsAt.isBefore(now)) return@mapNotNull null
                ScrapedShowtime(
                    startsAt = startsAt.toString(),
                    hall = text.substring(0, time.range.first).trim().ifEmpty { null },
                    bookingUrl = booking,
                )
            }
        }.sortedBy { it.startsAt }
    }
}
