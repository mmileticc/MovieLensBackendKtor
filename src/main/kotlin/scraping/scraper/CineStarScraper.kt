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
 * cinestarcinemas.rs je klasičan server-renderovan sajt (jQuery + Bootstrap-select), NE
 * moderan JS-framework SPA kao Cine Grand (Nuxt) - HTML sa kartama filmova je već prisutan
 * posle prve navigacije, bez potrebe za simulacijom klikova ili čekanjem hidratacije.
 *
 * KLJUČNA razlika u odnosu na CineGrandScraper: tamo smo morali da otvaramo DETALJ STRANICU
 * svakog filma pojedinačno da bismo saznali u kojim se bioskopima prikazuje (jer je repertoar
 * organizovan PO FILMU). Ovde je obrnuto - svaka od 4 poznate lokacije ima SOPSTVENU stranicu
 * (npr. "/novi-sad-big") koja već sadrži KOMPLETAN repertoar samo za tu lokaciju (naslov, žanr,
 * trajanje, termine za narednih ~3 nedelje unapred) - PA ipak, ORIGINALNI (izvorni) naslov filma
 * SE NE nalazi na toj kartici, samo na zasebnoj detalj-stranici filma (potvrđeno preko
 * scraper-debug/cinestarprobe_detalj-filma_*.html - "Izvorni naslov: ..." unutar
 * ".actors-producer" bloka). Zato i dalje otvaramo po jednu detalj-stranicu PO JEDINSTVENOM
 * FILMU (ne po (film, lokacija) paru - isti film ume da igra na sve 4 lokacije, pa bismo bez
 * dedupe-a nepotrebno 4x skidali isti podatak).
 *
 * Lokacije su nađene ručno (nisu nagađane) - iz "Izaberi svoj CineStar" <select id="kino">
 * dropdowna na agregatnoj strani cinestarcinemas.rs/program-svi-bioskopi (videti scraper-debug/
 * cinestarprobe_agregat-program-svi-bioskopi_*.html snimak).
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
                        // Ako obogaćivanje iz nekog razloga potpuno padne, bolje vratiti filmove BEZ
                        // izvornih naslova nego izgubiti ceo ciklus skrapovanja zbog sporedne stvari.
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
     * Dopunjuje svaki film sa "Izvorni naslov" poljem sa njegove detalj-stranice. Isti film
     * (isti brojčani ID na kraju bookingUrl-a, npr. ".../hari-poter-i-kamen-mudrosti/6579") ume
     * da se pojavi jednom po SVAKOJ lokaciji gde igra - keširamo po ID-ju da izvorni naslov
     * čitamo samo JEDNOM po jedinstvenom filmu, ne po (film, lokacija) paru.
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
