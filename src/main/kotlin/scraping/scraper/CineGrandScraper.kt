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

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val formatiranDatum = date.format(formatter)

        val urlRepertoar = "$baseUrl/repertoar?date=$formatiranDatum"

        println("[$cinemaName] Inicijalizacija Playwright drajvera...")

        Playwright.create().use { localPlaywright ->
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

            // 1. SKRAPOVANJE TEKUĆEG REPERTOARA
            try {
                println("[$cinemaName] [REPERTOAR] Otvaram stranicu: $urlRepertoar")
                page.navigate(urlRepertoar)
                page.waitForSelector("a.event-card", Page.WaitForSelectorOptions().setTimeout(15000.0))

                val docRepertoar = Jsoup.parse(page.content())
                tekuciFilmovi.addAll(
                    parseMoviesFromDoc(context, docRepertoar, isUpcoming = false, formatiranDatum = formatiranDatum, vecSkrapovaniLinkovi = emptySet())
                )
            } catch (e: Exception) {
                println("[$cinemaName] Greška pri skrapovanju tekućeg repertoara: ${e.message}")
            }

            val skrapovaniGlavniLinkovi = tekuciFilmovi.map { it.bookingUrl.substringBefore("?") }.toSet()

            // 2. SKRAPOVANJE SEKCIJE "USKORO" PREKO KLIKA NA NAVBAR
            try {
                println("[$cinemaName] [USKORO] Navigiram na početnu stranu radi klika na 'Uskoro'...")
                page.navigate(baseUrl)
                page.waitForLoadState(LoadState.DOMCONTENTLOADED)

                // Pronalaženje i klik na "Uskoro" u glavnom meniju (bazirano na slici image_e15ae1.jpg)
                val uskoroDugme = "a.text-desktop-main-menu:has-text('Uskoro')"
                page.waitForSelector(uskoroDugme, Page.WaitForSelectorOptions().setTimeout(10000.0))
                println("[$cinemaName] [USKORO] Klikćem na dugme 'Uskoro' u navigaciji...")
                page.click(uskoroDugme)

                // Sačekamo kratko da Angular zameni komponente i učita nove kartice filmova
                page.waitForTimeout(3000.0)
                page.waitForSelector("a.event-card", Page.WaitForSelectorOptions().setTimeout(10000.0))

                val docUskoro = Jsoup.parse(page.content())
                upcomingFilmovi.addAll(
                    parseMoviesFromDoc(context, docUskoro, isUpcoming = true, formatiranDatum = formatiranDatum, vecSkrapovaniLinkovi = skrapovaniGlavniLinkovi)
                )
            } catch (e: Exception) {
                println("[$cinemaName] Greška pri simulaciji klika za sekciju Uskoro: ${e.message}")
            } finally {
                context.close()
                browser.close()
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
        formatiranDatum: String,
        vecSkrapovaniLinkovi: Set<String>
    ): List<ScrapedMovie> {
        val result = mutableListOf<ScrapedMovie>()
        val filmovi = doc.select("a.event-card")
        val tipLoga = if (isUpcoming) "USKORO" else "REPERTOAR"

        println("[$cinemaName] [$tipLoga] Pronađeno filmova na stranici: ${filmovi.size}")

        for (film in filmovi) {
            var linkFilma = film.attr("href").trim()
            val naslov = film.select("div.event-title.d-md-block").first()?.text()?.trim()
                ?: film.select("div.event-title").first()?.text()?.trim()
                ?: ""

            if (linkFilma.startsWith("/")) {
                linkFilma = baseUrl + linkFilma
            }

            val cistUrl = linkFilma.substringBefore("?")

            if (naslov.isNotEmpty() && linkFilma.isNotEmpty()) {
                if (isUpcoming && vecSkrapovaniLinkovi.contains(cistUrl)) {
                    println("[$cinemaName] [$tipLoga] Preskačem duboki zahtev za: $naslov (već skinut kroz repertoar)")
                    continue
                }

                println("[$cinemaName] [$tipLoga] Otvaram detalje za film: $naslov")
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

                // REŠENJE ZA PROBLEM LOKACIJA:
                // 1. Prvo čekamo da se sigurno pojavi naslov (to nam govori da se stranica otvorila)
                privremenaStranica.waitForSelector("span.hero-title-original", Page.WaitForSelectorOptions().setTimeout(10000.0))

                // 2. Eksplicitno čekamo lokacije (h5.location-title). Dajemo Angularu 3.5 sekunde da povuče podatke sa API-ja.
                // Koristimo try-catch jer "Uskoro" filmovi nemaju lokacije, pa ne želimo da srušimo skrapovanje ako baci TimeoutException.
                try {
                    privremenaStranica.waitForSelector("h5.location-title", Page.WaitForSelectorOptions().setTimeout(3500.0))
                } catch (e: Exception) {
                    // Ignorišemo - film je verovatno stvarno u sekciji "Uskoro" i nema termine ni lokacije
                }

                val doc = Jsoup.parse(privremenaStranica.content())

                // Izvlačenje originalnog naslova
                val originalniNaslovSpan = doc.select("span.hero-title-original").first()
                if (originalniNaslovSpan != null) {
                    originalniNaslov = originalniNaslovSpan.text().trim()
                }

                // Izvlačenje svih lokacija u petlji (za svaku se pravi poseban objekat)
                val bioskopiElementi = doc.select("div.location-grouped-projections div.location-section h5.location-title")
                for (bioskop in bioskopiElementi) {
                    val imeBioskopa = bioskop.text().trim()
                    if (imeBioskopa.isNotEmpty() && !pronadjeneLokacije.contains(imeBioskopa)) {
                        pronadjeneLokacije.add(imeBioskopa)
                    }
                }

                return CineGrandDeepDetails(originalniNaslov, pronadjeneLokacije)
            } catch (e: Exception) {
                val suffix = if (attempt < maxAttempts) " - retrying" else ""
                println("[$cinemaName] Greška pri dubokom čitanju za $urlFilma (attempt $attempt/$maxAttempts): ${e.message}$suffix")
            } finally {
                privremenaStranica.close()
            }
        }

        return CineGrandDeepDetails(null, emptyList())
    }
}