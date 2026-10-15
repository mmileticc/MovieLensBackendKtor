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

private val logger = LoggerFactory.getLogger("CineplexPlatformScraper")

/** Screenings that start before this hour belong to the night after the listed day (e.g. 00:15). */
private const val NIGHT_END_HOUR = 5

private val TIME = Regex("""(\d{1,2}):(\d{2})""")
private val PRICE = Regex("""(\d+)(?:[.,]\d{1,2})?\s*Rsd""", RegexOption.IGNORE_CASE)

/**
 * Arena Cineplex (Novi Sad) and Roda Cineplex (Belgrade) run the same server-rendered website, read
 * with Jsoup:
 * - `/repertoar/YYYY-MM-DD` - the programme of one day: `li.replist` cards with the movie and, in
 *   `div.vreme_block`, one link per screening (time in `h3`, price, "Sala: N", ticket page);
 * - `/uskoro` - coming soon.
 *
 * The programme pages of [SHOWTIME_DAYS] days are combined: a movie playing today is "now showing",
 * one that starts later in the week is upcoming but already has its showtimes.
 */
abstract class CineplexPlatformScraper(
    final override val cinemaName: String,
    private val baseUrl: String,
    private val location: String,
    private val latitude: Double,
    private val longitude: Double,
) : CinemaScraper {

    override fun scrape(date: LocalDate): List<ScrapedMovie> {
        val now = LocalDateTime.now(ZONE)
        // Keyed like before (original title, else title), in the order the site lists them.
        val movies = LinkedHashMap<String, ScrapedMovie>()

        for (offset in 0 until SHOWTIME_DAYS) {
            val day = date.plusDays(offset.toLong())
            val doc = fetch("$baseUrl/repertoar/$day") ?: continue
            val dayMovies = parseProgramme(doc, day, now)
            if (offset == 0 && dayMovies.isEmpty()) {
                logger.info("[$cinemaName] Today's programme returned 0 movies - check the selectors.")
            }
            for (movie in dayMovies) {
                val key = movie.key()
                val known = movies[key]
                movies[key] = known?.copy(showtimes = known.showtimes + movie.showtimes)
                    ?: movie.copy(isUpcoming = offset > 0)
            }
        }

        fetch("$baseUrl/uskoro")?.let { doc ->
            for (movie in parseMovies(doc, isUpcoming = true)) {
                if (movies.putIfAbsent(movie.key(), movie) != null) {
                    logger.info("[$cinemaName] [DEDUP] '${movie.key()}' from coming soon is already in the programme.")
                }
            }
        }

        val result = movies.values.map { it.copy(showtimes = it.showtimes.distinct().sortedBy { s -> s.startsAt }) }
        logger.info("[$cinemaName] ${result.size} movies, ${result.sumOf { it.showtimes.size }} showtimes")
        return result
    }

    /**
     * Movies of one programme page with their screenings on [day]. Screenings that already started
     * (before [now]) are left out.
     */
    internal fun parseProgramme(doc: Document, day: LocalDate, now: LocalDateTime): List<ScrapedMovie> =
        doc.select("li.replist").mapNotNull { card ->
            val movie = parseCard(card, isUpcoming = false) ?: return@mapNotNull null
            val format = formatFromTitle(movie.title)
            val showtimes = card.select("div.vreme_block a").mapNotNull { parseShowtime(it, day)?.copy(format = format) }
                .filter { !LocalDateTime.parse(it.startsAt).isBefore(now) }
            movie.copy(showtimes = showtimes)
        }

    private fun parseMovies(doc: Document, isUpcoming: Boolean): List<ScrapedMovie> =
        doc.select("li.replist").mapNotNull { parseCard(it, isUpcoming) }

    private fun parseCard(card: Element, isUpcoming: Boolean): ScrapedMovie? {
        val details = card.selectFirst("div.col-md-5") ?: return null
        val title = details.select("h3").text().trim()
        val originalTitleRaw = details.select("p").text().trim()
        val link = absolute(details.select("a").attr("href").trim())
        if (title.isEmpty() || link.isEmpty()) return null

        val section = if (isUpcoming) "COMING SOON" else "PROGRAMME"
        logger.debug("[$cinemaName] [$section] Found: $title")
        return ScrapedMovie(
            title = title,
            originalTitle = originalTitleRaw.takeIf { it.isNotEmpty() && it != title },
            cinemaName = cinemaName,
            cinemaLocation = location,
            latitude = latitude,
            longitude = longitude,
            bookingUrl = link,
            isUpcoming = isUpcoming,
        )
    }

    /** One screening link: `<a href=ticket><h3>19:00</h3>600.00 Rsd ... <strong>Sala:</strong>2</a>`. */
    private fun parseShowtime(link: Element, day: LocalDate): ScrapedShowtime? {
        val time = TIME.find(link.selectFirst("h3")?.text().orEmpty()) ?: return null
        val (hour, minute) = time.destructured.toList().map { it.toInt() }
        if (hour > 23 || minute > 59) return null
        val date = if (hour < NIGHT_END_HOUR) day.plusDays(1) else day
        val text = link.text()
        return ScrapedShowtime(
            startsAt = LocalDateTime.of(date, LocalTime.of(hour, minute)).toString(),
            hall = link.selectFirst("span")?.text()?.substringAfter("Sala:", "")?.trim()?.ifEmpty { null },
            bookingUrl = absolute(link.attr("href").trim()).takeIf { it.startsWith("http") },
            priceRsd = PRICE.find(text)?.groupValues?.get(1)?.toIntOrNull(),
        )
    }

    private fun absolute(href: String): String = if (href.startsWith("/")) baseUrl + href else href

    private fun fetch(url: String): Document? = try {
        Jsoup.connect(url).userAgent(USER_AGENT).timeout(10_000).get()
    } catch (e: IOException) {
        logger.warn("[$cinemaName] Failed to load $url: ${e.message}")
        null
    }

    private fun ScrapedMovie.key() = originalTitle ?: title
}
