package com.milinko.scraping.scraper

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.milinko.scraping.agent.ScrapingConfig.MOVIE_LENS_USER_AGENT
import com.milinko.scraping.locations.LokacijeBioskopa
import com.milinko.scraping.model.ScrapedMovie
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("CineStarScraper")

/**
 * CineStar (4 locations), cinestarcinemas.rs: classic server-rendered pages (jQuery + Bootstrap),
 * currently read with Playwright.
 *
 * Unlike Cine Grand (organized per movie), every location has its own page (e.g. "/novi-sad-big")
 * with its complete programme (title, genre, runtime, screenings for about three weeks). The
 * original title is not on those cards, only on the movie's own page ("Izvorni naslov" inside
 * ".actors-producer"), so that page is opened once per unique movie - not per (movie, location),
 * since the same movie often plays at all four locations.
 *
 * The location pages were taken from the "Izaberi svoj CineStar" dropdown (`<select id="kino">`)
 * on cinestarcinemas.rs/program-svi-bioskopi.
 */
class CineStarScraper : CinemaScraper {
    override val cinemaName = "CineStar"
    private val baseUrl = "https://cinestarcinemas.rs"

    private val lokacije = mapOf(
        "/beograd-concept-cinema-ada-mall" to "CINESTAR BEOGRAD ADA MALL",
        "/novi-sad-big" to "CINESTAR NOVI SAD BIG",
        "/pancevo-big" to "CINESTAR PANČEVO BIG",
        "/zrenjanin-big" to "CINESTAR ZRENJANIN BIG"
    )

    override fun scrape(date: LocalDate): List<ScrapedMovie> {
        val sirovFilmovi = mutableListOf<ScrapedMovie>()
        var konacniFilmovi: List<ScrapedMovie> = emptyList()

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

                try {
                    for ((putanja, imeLokacije) in lokacije) {
                        try {
                            val url = "$baseUrl$putanja"
                            logger.info("[$cinemaName] [$imeLokacije] Otvaram stranicu: $url")
                            page.navigate(url)
                            page.waitForSelector(".movie-item", Page.WaitForSelectorOptions().setTimeout(15000.0))

                            val doc = Jsoup.parse(page.content())
                            val filmoviSaLokacije = parseMoviesFromDoc(doc, imeLokacije)
                            logger.info("[$cinemaName] [$imeLokacije] Pronađeno filmova: ${filmoviSaLokacije.size}")
                            sirovFilmovi.addAll(filmoviSaLokacije)
                        } catch (e: Exception) {
                            logger.warn("[$cinemaName] [$imeLokacije] Greška pri skrapovanju: ${e.message}")
                        }
                    }

                    konacniFilmovi = try {
                        enrichWithOriginalTitles(context, sirovFilmovi)
                    } catch (e: Exception) {
                        // If enrichment fails completely, return the movies without original
                        // titles rather than lose the whole run over a secondary detail.
                        logger.warn("[$cinemaName] Greška pri obogaćivanju izvornim naslovima - vraćam filmove bez toga: ${e.message}")
                        sirovFilmovi
                    }
                } finally {
                    context.close()
                    browser.close()
                }
            } catch (e: Exception) {
                logger.warn("[$cinemaName] KRITIČNA greška pri pokretanju/pripremi browsera - ništa nije skrapovano ovog ciklusa: ${e.message}")
                konacniFilmovi = sirovFilmovi
            }
        }

        return konacniFilmovi
    }

    private fun parseMoviesFromDoc(doc: Document, imeLokacije: String): List<ScrapedMovie> {
        val result = mutableListOf<ScrapedMovie>()
        val koordinate = LokacijeBioskopa.nadjiKoordinate(imeLokacije)

        val filmovi = doc.select(".movie-item")

        for (film in filmovi) {
            val bookingUrl = film.select("a.poster-wrapper").first()?.attr("href")?.trim().orEmpty()
            val h2 = film.select(".movie-desc h2").first()

            if (bookingUrl.isEmpty() || h2 == null) {
                continue
            }

            val labelElement = h2.select(".filmlabel").first()
            val isUpcoming = labelElement != null
            labelElement?.remove()
            val naslov = h2.text().trim()

            if (naslov.isEmpty()) {
                continue
            }

            result.add(
                ScrapedMovie(
                    title = naslov,
                    originalTitle = null,
                    cinemaName = cinemaName,
                    cinemaLocation = imeLokacije,
                    latitude = koordinate?.lat,
                    longitude = koordinate?.lon,
                    bookingUrl = bookingUrl,
                    isUpcoming = isUpcoming
                )
            )
        }

        return result
    }

    /**
     * Adds the original title ("Izvorni naslov") from each movie's page. The same movie (same
     * numeric id at the end of bookingUrl, e.g. ".../hari-poter-i-kamen-mudrosti/6579") appears once
     * per location, so titles are cached by that id and each page is read only once.
     */
    private fun enrichWithOriginalTitles(context: BrowserContext, filmovi: List<ScrapedMovie>): List<ScrapedMovie> {
        val izvorniNasloviPoId = mutableMapOf<String, String?>()

        return filmovi.map { film ->
            val filmId = film.bookingUrl.substringAfterLast("/")
            val izvorniNaslov = if (izvorniNasloviPoId.containsKey(filmId)) {
                izvorniNasloviPoId.getValue(filmId)
            } else {
                logger.info("[$cinemaName] Čitam izvorni naslov za: ${film.title}")
                val naslov = izvuciIzvorniNaslov(context, film.bookingUrl)
                izvorniNasloviPoId[filmId] = naslov
                naslov
            }
            if (izvorniNaslov != null) film.copy(originalTitle = izvorniNaslov) else film
        }
    }

    private fun izvuciIzvorniNaslov(context: BrowserContext, urlFilma: String): String? {
        if (urlFilma.isEmpty()) return null

        val privremenaStranica = context.newPage()
        return try {
            privremenaStranica.route("**/*") { route ->
                val resourceType = route.request().resourceType()
                if (resourceType == "image" || resourceType == "font" || resourceType == "media") {
                    route.abort()
                } else {
                    route.resume()
                }
            }

            privremenaStranica.navigate(urlFilma)
            privremenaStranica.waitForSelector(".movie-desc h1", Page.WaitForSelectorOptions().setTimeout(10000.0))

            val doc = Jsoup.parse(privremenaStranica.content())

            doc.select(".movie-detail-item:has(span.gray:contains(Izvorni naslov)) span:not(.gray)")
                .first()?.text()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            logger.warn("[$cinemaName] Greška pri čitanju izvornog naslova za $urlFilma: ${e.message}")
            null
        } finally {
            privremenaStranica.close()
        }
    }
}
