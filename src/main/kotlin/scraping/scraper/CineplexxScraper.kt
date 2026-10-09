package com.milinko.scraping.scraper

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitForSelectorState
import com.milinko.scraping.agent.ScrapingConfig.MOVIE_LENS_USER_AGENT
import com.milinko.scraping.locations.LokacijeBioskopa
import com.milinko.scraping.model.ScrapedMovie
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("CineplexxScraper")

/** What a Cineplexx movie page provides: the original title and the cinemas showing it. */
data class CineplexxDeepDetails(
    val originalTitle: String?,
    val locations: List<String>
)

/**
 * Cineplexx (7 cinemas), cineplexx.rs: a React app rendered with Playwright.
 * The list page for a date (`/film?category=now&location=all&date=...`) gives the movies; each
 * movie's page gives the original title and the cinemas, producing one row per (movie, cinema).
 * Coming soon: `/film?category=upcoming&date=all`.
 */
class CineplexxScraper : CinemaScraper {
    override val cinemaName = "Cineplexx"
    private val baseUrl = "https://www.cineplexx.rs"
    private val deepDetailsTimeoutMs =
        (System.getenv("PLAYWRIGHT_DEEP_TIMEOUT_MS")?.toDoubleOrNull() ?: 25000.0)

    override fun scrape(date: LocalDate): List<ScrapedMovie> {
        val tekuciFilmovi = mutableListOf<ScrapedMovie>()
        val upcomingFilmovi = mutableListOf<ScrapedMovie>()

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val formatiranDatum = date.format(formatter)

        val urlRepertoar = "$baseUrl/film?category=now&location=all&date=$formatiranDatum"
        val urlUskoro = "$baseUrl/film?category=upcoming&date=all"

        logger.info("[$cinemaName] Inicijalizacija Playwright drajvera na radnoj niti...")

        Playwright.create().use { localPlaywright ->
            val browser = localPlaywright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
            val context = browser.newContext(Browser.NewContextOptions().setViewportSize(1920, 1080))
            val page = context.newPage()


            val mojUserAgent = MOVIE_LENS_USER_AGENT
            page.setExtraHTTPHeaders(mapOf(
                "User-Agent" to mojUserAgent
            ))

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
                logger.info("[$cinemaName] [REPERTOAR] Otvaram glavnu stranicu: $urlRepertoar")
                page.navigate(urlRepertoar)
                page.waitForSelector("ul.movie-list, li.l-entity__item", Page.WaitForSelectorOptions().setTimeout(15000.0))

                val docRepertoar = Jsoup.parse(page.content())
                tekuciFilmovi.addAll(parseMoviesFromDoc(context, docRepertoar, isUpcoming = false, formatiranDatum = formatiranDatum, vecSkrapovaniLinkovi = emptySet()))
            } catch (e: Exception) {
                logger.warn("[$cinemaName] Greška pri skrapovanju tekućeg repertoara: ${e.message}")
            }

            // Links (without query parameters) already in the programme.
            val skrapovaniGlavniLinkovi = tekuciFilmovi.map { it.bookingUrl.substringBefore("?") }.toSet()

            // 2. "Coming soon" section
            try {
                logger.info("[$cinemaName] [USKORO] Otvaram stranicu nadolazećih filmova: $urlUskoro")
                page.navigate(urlUskoro)
                page.waitForSelector("ul.movie-list, li.l-entity__item", Page.WaitForSelectorOptions().setTimeout(15000.0))

                val docUskoro = Jsoup.parse(page.content())
                upcomingFilmovi.addAll(parseMoviesFromDoc(context, docUskoro, isUpcoming = true, formatiranDatum = formatiranDatum, vecSkrapovaniLinkovi = skrapovaniGlavniLinkovi))
            } catch (e: Exception) {
                logger.warn("[$cinemaName] Greška pri skrapovanju sekcije Uskoro: ${e.message}")
            } finally {
                context.close()
                browser.close()
            }
        }

        // 3. De-duplicate per (movie, cinema) without losing locations.
        val filtriranaMapa = tekuciFilmovi.associateBy { (it.originalTitle ?: it.title) + it.cinemaLocation }.toMutableMap()

        // Titles that are already playing somewhere today.
        val nasloviKojiVecIgraju = tekuciFilmovi.map { it.originalTitle ?: it.title }.toSet()

        for (upcomingIno in upcomingFilmovi) {
            val naslovKljuc = upcomingIno.originalTitle ?: upcomingIno.title
            val punKljuc = naslovKljuc + upcomingIno.cinemaLocation

            if (upcomingIno.cinemaLocation == "Uskoro u bioskopima") {
                // A coming-soon movie without a cinema is added only if it plays nowhere today.
                if (!nasloviKojiVecIgraju.contains(naslovKljuc)) {
                    filtriranaMapa[punKljuc] = upcomingIno
                } else {
                    logger.info("[$cinemaName] [DEDUPLIKACIJA] Preskačem prazan unos 'Uskoro u bioskopima' za '$naslovKljuc' jer već ima aktivne projekcije.")
                }
            } else {
                // A coming-soon movie with a cinema (presale started) uses the (movie, cinema) key.
                if (!filtriranaMapa.containsKey(punKljuc)) {
                    filtriranaMapa[punKljuc] = upcomingIno
                } else {
                    logger.info("[$cinemaName] [DEDUPLIKACIJA] Preskačem duplikat za '$naslovKljuc' na lokaciji [${upcomingIno.cinemaLocation}].")
                }
            }
        }

        return filtriranaMapa.values.toList()
    }

    private fun parseMoviesFromDoc(
        context: BrowserContext,
        doc: Document,
        isUpcoming: Boolean,
        formatiranDatum: String,
        vecSkrapovaniLinkovi: Set<String>
    ): List<ScrapedMovie> {
        val result = mutableListOf<ScrapedMovie>()
        val filmovi = doc.select("li.l-entity__item")
        val tipLoga = if (isUpcoming) "USKORO" else "REPERTOAR"

        logger.info("[$cinemaName] [$tipLoga] Broj filmova na listi za obradu: ${filmovi.size}")

        for (film in filmovi) {
            val linkElement = film.select("a.l-entity__item-link")
            var linkFilma = linkElement.attr("href").trim()
            val naslov = film.select("figcaption.l-entity__figure-caption").first()?.text()?.trim() ?: ""

            if (linkFilma.startsWith("/")) {
                linkFilma = baseUrl + linkFilma
            }

            // URL without query parameters, for duplicate checks.
            val cistUrl = linkFilma.substringBefore("?")

            if (naslov.isNotEmpty() && linkFilma.isNotEmpty()) {

                // A coming-soon movie already in the programme: skip the expensive Playwright navigation.
                if (isUpcoming && vecSkrapovaniLinkovi.contains(cistUrl)) {
                    logger.info("[$cinemaName] [$tipLoga] Preskačem mrežni zahtev za: $naslov (već skinut kroz tekući repertoar)")
                    continue
                }

                // Add the date to the URL for the current programme only.
                if (!isUpcoming && !linkFilma.contains("date=")) {
                    linkFilma = if (linkFilma.contains("?")) {
                        "$linkFilma&date=$formatiranDatum&location=all"
                    } else {
                        "$linkFilma?date=$formatiranDatum&location=all"
                    }
                }

                logger.info("[$cinemaName] [$tipLoga] Otvaram detalje za film: $naslov")
                val deepDetails = scrapeDeepDetails(context, linkFilma)

                // The movie has cinemas (current programme): one row per cinema.
                if (deepDetails.locations.isNotEmpty()) {
                    for (imeBioskopa in deepDetails.locations) {
                        val koordinate = LokacijeBioskopa.nadjiKoordinate(imeBioskopa)

                        result.add(
                            ScrapedMovie(
                                title = naslov,
                                originalTitle = deepDetails.originalTitle,
                                cinemaName = cinemaName,
                                cinemaLocation = imeBioskopa,
                                latitude = koordinate?.lat,
                                longitude = koordinate?.lon,
                                bookingUrl = linkFilma,
                                isUpcoming = isUpcoming
                            )
                        )
                    }
                } else {
                    // No cinemas (normal for "coming soon", or a failed detail page): one row without location.
                    result.add(
                        ScrapedMovie(
                            title = naslov,
                            originalTitle = deepDetails.originalTitle,
                            cinemaName = cinemaName,
                            cinemaLocation = "Uskoro u bioskopima",
                            latitude = null,
                            longitude = null,
                            bookingUrl = linkFilma,
                            isUpcoming = isUpcoming
                        )
                    )
                }
            }
        }
        return result
    }

    private fun scrapeDeepDetails(context: BrowserContext, urlFilma: String): CineplexxDeepDetails {
        if (urlFilma.isEmpty()) return CineplexxDeepDetails(null, emptyList())

        val maxAttempts = 2

        repeat(maxAttempts) { index ->
            val attempt = index + 1
            val pronadjeneLokacije = mutableListOf<String>()
            var originalniNaslov: String? = null
            val privremenaStranica = context.newPage()

            try {
                privremenaStranica.route("**/*") { route ->
                    val resourceType = route.request().resourceType()
                    if (resourceType == "image" || resourceType == "font") {
                        route.abort()
                    } else {
                        route.resume()
                    }
                }

                privremenaStranica.navigate(urlFilma)
                privremenaStranica.waitForLoadState(LoadState.DOMCONTENTLOADED)
                privremenaStranica.waitForSelector(
                    ".s-entity-details, .b-entity-content__title",
                    Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.ATTACHED)
                        .setTimeout(deepDetailsTimeoutMs)
                )

                val doc = Jsoup.parse(privremenaStranica.content())

                // Original title
                val strongTag = doc.select("strong:contains(Originalni naslov)").first()
                val naslovTekst = strongTag?.nextElementSibling()?.text()?.trim() ?: ""
                if (naslovTekst.isNotEmpty()) {
                    originalniNaslov = naslovTekst
                }

                // Cinemas
                val bioskopiLinkovi = doc.select("a.b-entity-content__title")
                for (link in bioskopiLinkovi) {
                    val imeBioskopa = link.text().trim()
                    if (imeBioskopa.isNotEmpty() && !pronadjeneLokacije.contains(imeBioskopa)) {
                        pronadjeneLokacije.add(imeBioskopa)
                    }
                }

                return CineplexxDeepDetails(originalniNaslov, pronadjeneLokacije)
            } catch (e: Exception) {
                val suffix = if (attempt < maxAttempts) " - retrying" else ""
                logger.warn("[$cinemaName] Greška pri dubokom čitanju (attempt $attempt/$maxAttempts): ${e.message}$suffix")
            } finally {
                privremenaStranica.close()
            }
        }

        return CineplexxDeepDetails(null, emptyList())
    }
}