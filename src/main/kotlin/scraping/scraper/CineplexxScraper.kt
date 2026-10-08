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

data class CineplexxDeepDetails(
    val originalTitle: String?,
    val locations: List<String>
)

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

            // Blokiranje slika i fontova radi brzine
            page.route("**/*") { route ->
                val resourceType = route.request().resourceType()
                if (resourceType == "image" || resourceType == "font") {
                    route.abort()
                } else {
                    route.resume()
                }
            }

            // 1. Skrapovanje tekućeg repertoara
            try {
                logger.info("[$cinemaName] [REPERTOAR] Otvaram glavnu stranicu: $urlRepertoar")
                page.navigate(urlRepertoar)
                page.waitForSelector("ul.movie-list, li.l-entity__item", Page.WaitForSelectorOptions().setTimeout(15000.0))

                val docRepertoar = Jsoup.parse(page.content())
                tekuciFilmovi.addAll(parseMoviesFromDoc(context, docRepertoar, isUpcoming = false, formatiranDatum = formatiranDatum, vecSkrapovaniLinkovi = emptySet()))
            } catch (e: Exception) {
                logger.warn("[$cinemaName] Greška pri skrapovanju tekućeg repertoara: ${e.message}")
            }

            // Pravimo skup čistih linkova (bez query parametara) koji već igraju
            val skrapovaniGlavniLinkovi = tekuciFilmovi.map { it.bookingUrl.substringBefore("?") }.toSet()

            // 2. Skrapovanje sekcije "Uskoro"
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

        // 3. PAMETNA DEDUPLIKACIJA BEZ GUBITKA LOKACIJA
        val filtriranaMapa = tekuciFilmovi.associateBy { (it.originalTitle ?: it.title) + it.cinemaLocation }.toMutableMap()

        // Pravimo Set jedinstvenih naslova filmova koji VEĆ igraju na repertoaru za taj dan
        val nasloviKojiVecIgraju = tekuciFilmovi.map { it.originalTitle ?: it.title }.toSet()

        for (upcomingIno in upcomingFilmovi) {
            val naslovKljuc = upcomingIno.originalTitle ?: upcomingIno.title
            val punKljuc = naslovKljuc + upcomingIno.cinemaLocation

            if (upcomingIno.cinemaLocation == "Uskoro u bioskopima") {
                // Ako je film generički, dodajemo ga SAMO ako taj film uopšte ne igra ni u jednom bioskopu danas
                if (!nasloviKojiVecIgraju.contains(naslovKljuc)) {
                    filtriranaMapa[punKljuc] = upcomingIno
                } else {
                    logger.info("[$cinemaName] [DEDUPLIKACIJA] Preskačem prazan unos 'Uskoro u bioskopima' za '$naslovKljuc' jer već ima aktivne projekcije.")
                }
            } else {
                // Ako film iz sekcije Uskoro ima specifičnu lokaciju (krenula pretprodaja), tvoj ključ radi savršeno
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

            // Čist URL bez query parametara za proveru duplikata
            val cistUrl = linkFilma.substringBefore("?")

            if (naslov.isNotEmpty() && linkFilma.isNotEmpty()) {

                // Optimizacija: Ako film iz sekcije Uskoro već igra na repertoaru, preskoči skupu Playwright navigaciju
                if (isUpcoming && vecSkrapovaniLinkovi.contains(cistUrl)) {
                    logger.info("[$cinemaName] [$tipLoga] Preskačem mrežni zahtev za: $naslov (već skinut kroz tekući repertoar)")
                    continue
                }

                // Dodavanje datuma u URL samo ako je u pitanju tekući repertoar
                if (!isUpcoming && !linkFilma.contains("date=")) {
                    linkFilma = if (linkFilma.contains("?")) {
                        "$linkFilma&date=$formatiranDatum&location=all"
                    } else {
                        "$linkFilma?date=$formatiranDatum&location=all"
                    }
                }

                logger.info("[$cinemaName] [$tipLoga] Otvaram detalje za film: $naslov")
                val deepDetails = scrapeDeepDetails(context, linkFilma)

                // Ako film ima dodeljene lokacije (Repertoar)
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
                    // Ako nema lokacija (što je pravilo za sekciju "Uskoro" ili fallback za greške)
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

                // Izvlačenje originalnog naslova
                val strongTag = doc.select("strong:contains(Originalni naslov)").first()
                val naslovTekst = strongTag?.nextElementSibling()?.text()?.trim() ?: ""
                if (naslovTekst.isNotEmpty()) {
                    originalniNaslov = naslovTekst
                }

                // Izvlačenje lokacija
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