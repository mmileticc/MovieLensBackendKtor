package com.milinko.scraping.scraper

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitForSelectorState
import com.milinko.scraping.agent.ScrapingConfig.USER_AGENT
import com.milinko.scraping.locations.CinemaLocations
import com.milinko.scraping.model.ScrapedMovie
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val logger = LoggerFactory.getLogger("CineplexxBrowserScraper")

/** What a Cineplexx movie page provides: the original title and the cinemas showing it. */
data class CineplexxDeepDetails(
    val originalTitle: String?,
    val locations: List<String>
)

/**
 * Fallback for [CineplexxScraper] when the site's API is unavailable: reads the rendered website
 * with Playwright (slower, and without showtimes).
 *
 * Cineplexx (7 cinemas), cineplexx.rs: a React app rendered with Playwright.
 * The list page for a date (`/film?category=now&location=all&date=...`) gives the movies; each
 * movie's page gives the original title and the cinemas, producing one row per (movie, cinema).
 * Coming soon: `/film?category=upcoming&date=all`.
 */
class CineplexxBrowserScraper : CinemaScraper {
    override val cinemaName = "Cineplexx"
    private val baseUrl = "https://www.cineplexx.rs"
    private val deepDetailsTimeoutMs =
        (System.getenv("PLAYWRIGHT_DEEP_TIMEOUT_MS")?.toDoubleOrNull() ?: 25000.0)

    override fun scrape(date: LocalDate): List<ScrapedMovie> {
        val nowShowing = mutableListOf<ScrapedMovie>()
        val comingSoon = mutableListOf<ScrapedMovie>()

        val formattedDate = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

        val programmeUrl = "$baseUrl/film?category=now&location=all&date=$formattedDate"
        val comingSoonUrl = "$baseUrl/film?category=upcoming&date=all"

        logger.info("[$cinemaName] Starting the Playwright driver on the worker thread...")

        Playwright.create().use { playwright ->
            val browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
            val context = browser.newContext(Browser.NewContextOptions().setViewportSize(1920, 1080))
            val page = context.newPage()

            page.setExtraHTTPHeaders(mapOf("User-Agent" to USER_AGENT))

            // Block images and fonts for speed.
            page.route("**/*") { route ->
                val resourceType = route.request().resourceType()
                if (resourceType == "image" || resourceType == "font") {
                    route.abort()
                } else {
                    route.resume()
                }
            }

            // 1. Current programme
            try {
                logger.info("[$cinemaName] [PROGRAMME] Opening the list page: $programmeUrl")
                page.navigate(programmeUrl)
                page.waitForSelector("ul.movie-list, li.l-entity__item", Page.WaitForSelectorOptions().setTimeout(15000.0))

                val programmeDoc = Jsoup.parse(page.content())
                nowShowing.addAll(parseMoviesFromDoc(context, programmeDoc, isUpcoming = false, formattedDate = formattedDate, alreadyScrapedLinks = emptySet()))
            } catch (e: Exception) {
                logger.warn("[$cinemaName] Failed to scrape the current programme: ${e.message}")
                saveDebugSnapshot(page, cinemaName, "programme")
            }

            // Links (without query parameters) already in the programme.
            val programmeLinks = nowShowing.map { it.bookingUrl.substringBefore("?") }.toSet()

            // 2. "Coming soon" section
            try {
                logger.info("[$cinemaName] [COMING SOON] Opening the upcoming movies page: $comingSoonUrl")
                page.navigate(comingSoonUrl)
                page.waitForSelector("ul.movie-list, li.l-entity__item", Page.WaitForSelectorOptions().setTimeout(15000.0))

                val comingSoonDoc = Jsoup.parse(page.content())
                comingSoon.addAll(parseMoviesFromDoc(context, comingSoonDoc, isUpcoming = true, formattedDate = formattedDate, alreadyScrapedLinks = programmeLinks))
            } catch (e: Exception) {
                logger.warn("[$cinemaName] Failed to scrape the coming-soon section: ${e.message}")
                saveDebugSnapshot(page, cinemaName, "coming-soon")
            } finally {
                context.close()
                browser.close()
            }
        }

        // 3. De-duplicate per (movie, cinema) without losing locations.
        val byKey = nowShowing.associateBy { (it.originalTitle ?: it.title) + it.cinemaLocation }.toMutableMap()

        // Titles that are already playing somewhere today.
        val titlesPlayingToday = nowShowing.map { it.originalTitle ?: it.title }.toSet()

        for (upcoming in comingSoon) {
            val titleKey = upcoming.originalTitle ?: upcoming.title
            val fullKey = titleKey + upcoming.cinemaLocation

            if (upcoming.cinemaLocation == ScrapedMovie.NO_LOCATION) {
                // A coming-soon movie without a cinema is added only if it plays nowhere today.
                if (!titlesPlayingToday.contains(titleKey)) {
                    byKey[fullKey] = upcoming
                } else {
                    logger.info("[$cinemaName] [DEDUP] Skipping the location-less entry for '$titleKey' - it already has screenings.")
                }
            } else {
                // A coming-soon movie with a cinema (presale started) uses the (movie, cinema) key.
                if (!byKey.containsKey(fullKey)) {
                    byKey[fullKey] = upcoming
                } else {
                    logger.info("[$cinemaName] [DEDUP] Skipping a duplicate of '$titleKey' at [${upcoming.cinemaLocation}].")
                }
            }
        }

        return byKey.values.toList()
    }

    private fun parseMoviesFromDoc(
        context: BrowserContext,
        doc: Document,
        isUpcoming: Boolean,
        formattedDate: String,
        alreadyScrapedLinks: Set<String>
    ): List<ScrapedMovie> {
        val result = mutableListOf<ScrapedMovie>()
        val cards = doc.select("li.l-entity__item")
        val section = if (isUpcoming) "COMING SOON" else "PROGRAMME"

        logger.info("[$cinemaName] [$section] Movies on the list: ${cards.size}")

        for (card in cards) {
            val linkElement = card.select("a.l-entity__item-link")
            var movieLink = linkElement.attr("href").trim()
            val title = card.select("figcaption.l-entity__figure-caption").first()?.text()?.trim() ?: ""

            if (movieLink.startsWith("/")) {
                movieLink = baseUrl + movieLink
            }

            // URL without query parameters, for duplicate checks.
            val cleanUrl = movieLink.substringBefore("?")

            if (title.isNotEmpty() && movieLink.isNotEmpty()) {

                // A coming-soon movie already in the programme: skip the expensive Playwright navigation.
                if (isUpcoming && alreadyScrapedLinks.contains(cleanUrl)) {
                    logger.info("[$cinemaName] [$section] Skipping the request for: $title (already scraped from the programme)")
                    continue
                }

                // Add the date to the URL for the current programme only.
                if (!isUpcoming && !movieLink.contains("date=")) {
                    movieLink = if (movieLink.contains("?")) {
                        "$movieLink&date=$formattedDate&location=all"
                    } else {
                        "$movieLink?date=$formattedDate&location=all"
                    }
                }

                logger.info("[$cinemaName] [$section] Opening details for: $title")
                val deepDetails = scrapeDeepDetails(context, movieLink)

                // The movie has cinemas (current programme): one row per cinema.
                if (deepDetails.locations.isNotEmpty()) {
                    for (cinemaLocation in deepDetails.locations) {
                        val coordinates = CinemaLocations.findCoordinates(cinemaLocation)

                        result.add(
                            ScrapedMovie(
                                title = title,
                                originalTitle = deepDetails.originalTitle,
                                cinemaName = cinemaName,
                                cinemaLocation = cinemaLocation,
                                latitude = coordinates?.lat,
                                longitude = coordinates?.lon,
                                bookingUrl = movieLink,
                                isUpcoming = isUpcoming
                            )
                        )
                    }
                } else {
                    // No cinemas (normal for "coming soon", or a failed detail page): one row without location.
                    result.add(
                        ScrapedMovie(
                            title = title,
                            originalTitle = deepDetails.originalTitle,
                            cinemaName = cinemaName,
                            cinemaLocation = ScrapedMovie.NO_LOCATION,
                            latitude = null,
                            longitude = null,
                            bookingUrl = movieLink,
                            isUpcoming = isUpcoming
                        )
                    )
                }
            }
        }
        return result
    }

    private fun scrapeDeepDetails(context: BrowserContext, movieUrl: String): CineplexxDeepDetails {
        if (movieUrl.isEmpty()) return CineplexxDeepDetails(null, emptyList())

        val maxAttempts = 2

        repeat(maxAttempts) { index ->
            val attempt = index + 1
            val foundLocations = mutableListOf<String>()
            var originalTitle: String? = null
            val detailPage = context.newPage()

            try {
                detailPage.route("**/*") { route ->
                    val resourceType = route.request().resourceType()
                    if (resourceType == "image" || resourceType == "font") {
                        route.abort()
                    } else {
                        route.resume()
                    }
                }

                detailPage.navigate(movieUrl)
                detailPage.waitForLoadState(LoadState.DOMCONTENTLOADED)
                detailPage.waitForSelector(
                    ".s-entity-details, .b-entity-content__title",
                    Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.ATTACHED)
                        .setTimeout(deepDetailsTimeoutMs)
                )

                val doc = Jsoup.parse(detailPage.content())

                // Original title (the site labels it "Originalni naslov")
                val label = doc.select("strong:contains(Originalni naslov)").first()
                val titleText = label?.nextElementSibling()?.text()?.trim() ?: ""
                if (titleText.isNotEmpty()) {
                    originalTitle = titleText
                }

                // Cinemas
                for (link in doc.select("a.b-entity-content__title")) {
                    val cinemaLocation = link.text().trim()
                    if (cinemaLocation.isNotEmpty() && !foundLocations.contains(cinemaLocation)) {
                        foundLocations.add(cinemaLocation)
                    }
                }

                return CineplexxDeepDetails(originalTitle, foundLocations)
            } catch (e: Exception) {
                val suffix = if (attempt < maxAttempts) " - retrying" else ""
                logger.warn("[$cinemaName] Failed to read the movie page (attempt $attempt/$maxAttempts): ${e.message}$suffix")
                if (attempt == maxAttempts) saveDebugSnapshot(detailPage, cinemaName, "movie-page")
            } finally {
                detailPage.close()
            }
        }

        return CineplexxDeepDetails(null, emptyList())
    }
}
