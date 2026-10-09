package com.milinko.scraping.scraper

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import com.milinko.scraping.agent.ScrapingConfig.MOVIE_LENS_USER_AGENT
import com.milinko.scraping.locations.LokacijeBioskopa
import com.milinko.scraping.model.ScrapedMovie
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import java.time.LocalDate
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("CineGrandScraper")

/** What a Cine Grand movie page provides: the original title and the locations showing it. */
data class CineGrandDeepDetails(
    val originalTitle: String?,
    val locations: List<String>
)

/**
 * Cine Grand (several locations), cinegrand-mcf.rs: a Nuxt app rendered with Playwright.
 * The programme (`/u-bioskopu`) and coming soon (`/uskoro`) list movies; each movie's page lists
 * the locations showing it, so one row is produced per (movie, location).
 */
class CineGrandScraper : CinemaScraper {
    override val cinemaName = "Cine Grand"
    private val baseUrl = "https://cinegrand-mcf.rs"
    private val deepDetailsTimeoutMs =
        (System.getenv("PLAYWRIGHT_DEEP_TIMEOUT_MS")?.toDoubleOrNull() ?: 25000.0)

    override fun scrape(date: LocalDate): List<ScrapedMovie> {
        val tekuciFilmovi = mutableListOf<ScrapedMovie>()
        val upcomingFilmovi = mutableListOf<ScrapedMovie>()


        val urlRepertoar = "$baseUrl/u-bioskopu"
        val urlUskoro = "$baseUrl/uskoro"

        logger.info("[$cinemaName] Inicijalizacija Playwright drajvera...")

        Playwright.create().use { localPlaywright ->
            try {
                val browser = localPlaywright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
                val context = browser.newContext(Browser.NewContextOptions().setViewportSize(1920, 1080))
                val page = context.newPage()

                page.setExtraHTTPHeaders(mapOf("User-Agent" to MOVIE_LENS_USER_AGENT))

                page.route("**/*") { route ->
                    val resourceType = route.request().resourceType()
                    if (resourceType == "image" || resourceType == "font" || resourceType == "media") {
                        route.abort()
                    } else {
                        route.resume()
                    }
                }

                // 1. Current programme (/u-bioskopu)
                try {
                    logger.info("[$cinemaName] [REPERTOAR] Otvaram stranicu: $urlRepertoar")
                    page.navigate(urlRepertoar)
                    page.waitForSelector("a.group.block", Page.WaitForSelectorOptions().setTimeout(15000.0))

                    val docRepertoar = Jsoup.parse(page.content())
                    tekuciFilmovi.addAll(
                        parseMoviesFromDoc(context, docRepertoar, isUpcoming = false, vecSkrapovaniLinkovi = emptySet())
                    )
                } catch (e: Exception) {
                    logger.warn("[$cinemaName] Greška pri skrapovanju tekućeg repertoara: ${e.message}")
                    // Save exactly what the browser saw when it failed; otherwise the next time
                    // nobody knows whether a selector changed, the site blocked the bot, or something else.
                }

                val skrapovaniGlavniLinkovi = tekuciFilmovi.map { it.bookingUrl.substringBefore("?") }.toSet()

                try {
                    logger.info("[$cinemaName] [USKORO] Otvaram stranicu: $urlUskoro")
                    page.navigate(urlUskoro)
                    page.waitForSelector("a.group.block", Page.WaitForSelectorOptions().setTimeout(15000.0))

                    val docUskoro = Jsoup.parse(page.content())
                    upcomingFilmovi.addAll(
                        parseMoviesFromDoc(context, docUskoro, isUpcoming = true, vecSkrapovaniLinkovi = skrapovaniGlavniLinkovi)
                    )
                } catch (e: Exception) {
                    logger.warn("[$cinemaName] Greška pri skrapovanju sekcije Uskoro: ${e.message}")
                } finally {
                    context.close()
                    browser.close()
                }
            } catch (e: Exception) {
                logger.warn("[$cinemaName] KRITIČNA greška pri pokretanju/pripremi browsera - ništa nije skrapovano ovog ciklusa: ${e.message}")
            }
        }

        // 3. De-duplicate per (movie, location).
        val filtriranaMapa = tekuciFilmovi.associateBy { (it.originalTitle ?: it.title) + it.cinemaLocation }.toMutableMap()
        val nasloviKojiVecIgraju = tekuciFilmovi.map { it.originalTitle ?: it.title }.toSet()

        for (upcomingIno in upcomingFilmovi) {
            val naslovKljuc = upcomingIno.originalTitle ?: upcomingIno.title
            val punKljuc = naslovKljuc + upcomingIno.cinemaLocation

            if (upcomingIno.cinemaLocation == "Uskoro u bioskopima") {
                if (!nasloviKojiVecIgraju.contains(naslovKljuc)) {
                    filtriranaMapa[punKljuc] = upcomingIno
                }
            } else {
                if (!filtriranaMapa.containsKey(punKljuc)) {
                    filtriranaMapa[punKljuc] = upcomingIno
                }
            }
        }

        return filtriranaMapa.values.toList()
    }

    private fun parseMoviesFromDoc(
        context: BrowserContext,
        doc: Document,
        isUpcoming: Boolean,
        vecSkrapovaniLinkovi: Set<String>
    ): List<ScrapedMovie> {
        val result = mutableListOf<ScrapedMovie>()
        val filmovi = doc.select("a.group.block")
        val tipLoga = if (isUpcoming) "USKORO" else "REPERTOAR"

        logger.info("[$cinemaName] [$tipLoga] Pronađeno filmova na stranici: ${filmovi.size}")

        for (film in filmovi) {
            var linkFilma = film.attr("href").trim()
            val naslov = film.select("h3").first()?.text()?.trim() ?: ""

            if (linkFilma.startsWith("/")) {
                linkFilma = baseUrl + linkFilma
            }

            val cistUrl = linkFilma.substringBefore("?")

            if (naslov.isNotEmpty() && linkFilma.isNotEmpty()) {
                if (isUpcoming && vecSkrapovaniLinkovi.contains(cistUrl)) {
                    logger.info("[$cinemaName] [$tipLoga] Preskačem duboki zahtev za: $naslov (već skinut kroz repertoar)")
                    continue
                }

                logger.info("[$cinemaName] [$tipLoga] Otvaram detalje za film: $naslov")
                val deepDetails = scrapeDeepDetails(context, linkFilma)

                // One row per location found on the movie page.
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

    private fun scrapeDeepDetails(context: BrowserContext, urlFilma: String): CineGrandDeepDetails {
        if (urlFilma.isEmpty()) return CineGrandDeepDetails(null, emptyList())

        val maxAttempts = 2

        repeat(maxAttempts) { index ->
            val attempt = index + 1
            val pronadjeneLokacije = mutableListOf<String>()
            var originalniNaslov: String? = null
            val privremenaStranica = context.newPage()

            try {
                privremenaStranica.route("**/*") { route ->
                    val resourceType = route.request().resourceType()
                    if (resourceType == "image" || resourceType == "font" || resourceType == "media") {
                        route.abort()
                    } else {
                        route.resume()
                    }
                }

                privremenaStranica.navigate(urlFilma)
                privremenaStranica.waitForLoadState(LoadState.DOMCONTENTLOADED)
                privremenaStranica.waitForSelector(".hf-original", Page.WaitForSelectorOptions().setTimeout(10000.0))
                try {
                    privremenaStranica.waitForSelector(".hp-raspon-cname", Page.WaitForSelectorOptions().setTimeout(3500.0))
                } catch (e: Exception) {
                    // Ignored - the movie probably has no screenings scheduled yet.
                }

                val doc = Jsoup.parse(privremenaStranica.content())

                // Original title
                val originalniNaslovDiv = doc.select(".hf-original").first()
                if (originalniNaslovDiv != null) {
                    originalniNaslov = originalniNaslovDiv.text().trim()
                }

                // Every location with screenings is a ".hp-raspon-cinema" block with a
                // ".hp-raspon-cname" heading; each becomes its own row.
                val bioskopiElementi = doc.select(".hp-raspon-cname")
                for (bioskop in bioskopiElementi) {
                    val imeBioskopa = bioskop.text().trim()
                    if (imeBioskopa.isNotEmpty() && !pronadjeneLokacije.contains(imeBioskopa)) {
                        pronadjeneLokacije.add(imeBioskopa)
                    }
                }

                return CineGrandDeepDetails(originalniNaslov, pronadjeneLokacije)
            } catch (e: Exception) {
                val suffix = if (attempt < maxAttempts) " - retrying" else ""
                logger.warn("[$cinemaName] Greška pri dubokom čitanju za $urlFilma (attempt $attempt/$maxAttempts): ${e.message}$suffix")
            } finally {
                privremenaStranica.close()
            }
        }

        return CineGrandDeepDetails(null, emptyList())
    }
}