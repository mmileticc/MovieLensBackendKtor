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

data class CineGrandDeepDetails(
    val originalTitle: String?,
    val locations: List<String>
)

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

                // 1. SKRAPOVANJE TEKUĆEG REPERTOARA (/u-bioskopu)
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
                    // Snimamo tačno šta je browser video u trenutku pada - bez ovoga se sledeći put
                    // opet ne bi znalo da li je selektor promenjen, sajt blokirao bota, ili nešto treće.
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

        // 3. PAMETNA DEDUPLIKACIJA LOKACIJA (Ostaje ista, savršeno radi posao)
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

                // PROLAZAK KROZ SVE PRONAĐENE LOKACIJE (Unrolling)
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
                    // Ignorišemo - film verovatno stvarno nema zakazane termine još uvek
                }

                val doc = Jsoup.parse(privremenaStranica.content())

                // Izvlačenje originalnog naslova
                val originalniNaslovDiv = doc.select(".hf-original").first()
                if (originalniNaslovDiv != null) {
                    originalniNaslov = originalniNaslovDiv.text().trim()
                }

                // Izvlačenje svih lokacija u petlji (za svaku se pravi poseban objekat) - svaki
                // bioskop sa terminima je ".hp-raspon-cinema" blok sa ".hp-raspon-cname" naslovom.
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