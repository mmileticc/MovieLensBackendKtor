package com.milinko.scraping.scraper

import com.milinko.scraping.agent.ScrapingConfig.SHOWTIME_DAYS
import com.milinko.scraping.agent.ScrapingConfig.USER_AGENT
import com.milinko.scraping.agent.ScrapingConfig.ZONE
import com.milinko.scraping.model.ScrapedMovie
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime

private val logger = LoggerFactory.getLogger("MtsDvoranaScraper")

/**
 * mts Dvorana (Belgrade), mtsdvorana.rs. The home page lists the current programme and
 * `/filmovi/uskoro` the coming-soon movies; each movie's own page provides the original title and
 * the showtimes (see [parseHappeningPage]).
 */
class MtsDvoranaScraper : CinemaScraper {
    override val cinemaName = "MTS Dvorana"
    private val baseUrl = "https://www.mtsdvorana.rs"
    private val location = "MTS Dvorana, Trg Nikole Pašića"
    private val latitude = 44.81348329306508
    private val longitude = 20.46304171756712

    override fun scrape(date: LocalDate): List<ScrapedMovie> {
        val nowShowing = mutableListOf<ScrapedMovie>()
        val comingSoon = mutableListOf<ScrapedMovie>()

        val programmeUrl = "$baseUrl/"
        val comingSoonUrl = "$baseUrl/filmovi/uskoro"

        // 1. Current programme
        try {
            val programmeDoc = Jsoup.connect(programmeUrl)
                .userAgent(USER_AGENT)
                .timeout(10000)
                .get()

            nowShowing.addAll(parseMoviesFromDoc(programmeDoc, isUpcoming = false, alreadyScrapedLinks = emptySet(), date))
        } catch (e: IOException) {
            logger.warn("[$cinemaName] Failed to scrape the programme page: ${e.message}")
        }

        // Links already in the programme, so their pages are not fetched again for "coming soon".
        val programmeLinks = nowShowing.map { it.bookingUrl }.toSet()

        // 2. "Coming soon" section
        try {
            val comingSoonDoc = Jsoup.connect(comingSoonUrl)
                .userAgent(USER_AGENT)
                .timeout(10000)
                .get()

            comingSoon.addAll(parseMoviesFromDoc(comingSoonDoc, isUpcoming = true, alreadyScrapedLinks = programmeLinks, date))
        } catch (e: IOException) {
            logger.warn("[$cinemaName] Failed to scrape the coming-soon section: ${e.message}")
        }

        // 3. De-duplicate by originalTitle (or title when there is none); the current programme wins.
        val byTitle = nowShowing.associateBy { it.originalTitle ?: it.title }.toMutableMap()

        for (upcoming in comingSoon) {
            val key = upcoming.originalTitle ?: upcoming.title
            if (!byTitle.containsKey(key)) {
                byTitle[key] = upcoming
            } else {
                logger.info("[$cinemaName] [DEDUP] Skipping '${upcoming.title}' from coming soon - already in the programme.")
            }
        }

        return byTitle.values.toList()
    }

    /**
     * The site is a Nuxt + Tailwind app, but the movie cards are server-rendered, so Jsoup is
     * enough: `<a href="/film/<slug>" class="group block"><img/><h3>Title</h3><p>Genre</p></a>`.
     * The home page also lists events ("/dogadjaj/..."), so only "/film/" links are kept.
     */
    private fun parseMoviesFromDoc(
        doc: Document,
        isUpcoming: Boolean,
        alreadyScrapedLinks: Set<String>,
        date: LocalDate,
    ): List<ScrapedMovie> {
        val now = LocalDateTime.now(ZONE)
        val result = mutableListOf<ScrapedMovie>()
        val cards = doc.select("a[href^=/film/]:has(h3)").distinctBy { it.attr("href") }

        for (card in cards) {
            val title = card.select("h3").first()?.text()?.trim().orEmpty()
            var link = card.attr("href").trim()

            if (link.startsWith("/")) {
                link = baseUrl + link
            }

            if (title.isNotEmpty() && link.isNotEmpty()) {
                val section = if (isUpcoming) "COMING SOON" else "PROGRAMME"

                // A coming-soon movie whose link is already in the programme is a duplicate - skip
                // fetching its page.
                if (isUpcoming && alreadyScrapedLinks.contains(link)) {
                    logger.info("[$cinemaName] [$section] Skipping the request for: $title (already scraped from the programme)")
                    continue
                }

                // A short random pause between movie pages, to be gentle with the site.
                Thread.sleep((500..1500).random().toLong())

                logger.info("[$cinemaName] [$section] Processing: $title")

                // The movie's own page: original title and showtimes.
                val page = fetchMoviePage(link)
                val showtimes = page?.happening?.projections.orEmpty()
                    .inWindow(date, SHOWTIME_DAYS, now)
                    .map { it.toShowtime(baseUrl, page?.happening?.name ?: title) }

                result.add(
                    ScrapedMovie(
                        title = title,
                        originalTitle = page?.originalTitle,
                        cinemaName = cinemaName,
                        bookingUrl = link,
                        cinemaLocation = location,
                        latitude = latitude,
                        longitude = longitude,
                        isUpcoming = isUpcoming,
                        showtimes = showtimes
                    )
                )
            }
        }
        return result
    }

    private class MoviePage(val originalTitle: String?, val happening: HappeningPage?)

    private fun fetchMoviePage(movieUrl: String): MoviePage? {
        return try {
            val doc = Jsoup.connect(movieUrl)
                .userAgent(USER_AGENT)
                .timeout(10000)
                .get()

            val happening = parseHappeningPage(doc)
            if (happening == null) logger.warn("[$cinemaName] No Nuxt data on $movieUrl - showtimes unavailable")
            // The Nuxt data is the primary source; ".hf-original" (rendered twice for mobile and
            // desktop, hence first()) is the fallback for the original title.
            val originalTitle = happening?.originalName
                ?: doc.select(".hf-original").first()?.text()?.trim()?.ifEmpty { null }
            MoviePage(originalTitle, happening)
        } catch (e: IOException) {
            logger.warn("[$cinemaName] Failed to load the movie page: $movieUrl -> ${e.message}")
            null
        }
    }
}
