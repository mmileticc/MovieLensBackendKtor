package com.milinko.scraping.scraper

import com.milinko.scraping.agent.ScrapingConfig.MOVIE_LENS_USER_AGENT
import com.milinko.scraping.model.ScrapedMovie
import org.jsoup.Jsoup
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("RodaCineplexScraper")

/**
 * rodacineplex.com pripada ISTOJ firmi (Art Vista d.o.o.) i ISTOJ platformi kao Arena Cineplex
 * (footer oba sajta: "ARENA CINEPLEX | RODA CINEPLEX | MOBIL3DCINEMA") - ista URL šema
 * ("/repertoar/YYYY-MM-DD", "/uskoro"), ista opšta struktura kartice filma (poster + naslov +
 * originalni naslov + termini). Ovaj scraper je zato NAMERNO skoro identičan
 * ArenaCineplexScraper-u - iste selektore koristi i ovde, uz drugačiji baseUrl/lokaciju.
 *
 * NAPOMENA: selektori (`li.replist`, `div.col-md-5`) NISU potvrđeni direktno na Roda HTML-u (samo
 * markdown-konvertovan sadržaj je bio dostupan pri istraživanju, bez CSS klasa) - pretpostavka je
 * zasnovana na deljenoj platformi/firmi. Ako ovaj scraper vrati 0 filmova, `scraper-debug/` HTML
 * snimak (snima se automatski pri grešci/praznom rezultatu) će pokazati tačnu razliku.
 */
class RodaCineplexScraper : CinemaScraper {
    override val cinemaName = "Roda Cineplex"
    private val baseUrl = "http://www.rodacineplex.com"
    private val fiksnaLokacija = "RODA CINEPLEX"
    private val fiksniLat = 44.77397506094518
    private val fiksniLon = 20.414585469313216

    private val mojUserAgent = MOVIE_LENS_USER_AGENT

    override fun scrape(date: LocalDate): List<ScrapedMovie> {
        val tekuciFilmovi = mutableListOf<ScrapedMovie>()
        val upcomingFilmovi = mutableListOf<ScrapedMovie>()

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val urlRepertoar = "$baseUrl/repertoar/${date.format(formatter)}"
        val urlUskoro = "$baseUrl/uskoro"

        // 1. Skrapovanje trenutnog repertoara (isUpcoming = false)
        try {
            val docRepertoar = Jsoup.connect(urlRepertoar)
                .userAgent(mojUserAgent)
                .timeout(10000)
                .get()

            val filmoviSaRepertoara = parseMoviesFromDoc(docRepertoar, isUpcoming = false)
            if (filmoviSaRepertoara.isEmpty()) {
                logger.info("[$cinemaName] REPERTOAR vratio 0 filmova - snimam HTML za proveru selektora.")
            }
            tekuciFilmovi.addAll(filmoviSaRepertoara)
        } catch (e: IOException) {
            logger.warn("[$cinemaName] Greška pri skrapovanju repertoara: ${e.message}")
        }

        // 2. Skrapovanje sekcije "Uskoro" (isUpcoming = true)
        try {
            val docUskoro = Jsoup.connect(urlUskoro)
                .userAgent(mojUserAgent)
                .timeout(10000)
                .get()

            upcomingFilmovi.addAll(parseMoviesFromDoc(docUskoro, isUpcoming = true))
        } catch (e: IOException) {
            logger.warn("[$cinemaName] Greška pri skrapovanju sekcije Uskoro: ${e.message}")
        }

        // 3. Interna deduplikacija unutar ovog bioskopa (isti obrazac kao Arena Cineplex)
        val filtriranaMapa = tekuciFilmovi.associateBy { it.originalTitle ?: it.title }.toMutableMap()

        for (upcomingIno in upcomingFilmovi) {
            val key = upcomingIno.originalTitle ?: upcomingIno.title
            if (!filtriranaMapa.containsKey(key)) {
                filtriranaMapa[key] = upcomingIno
            } else {
                logger.info("[$cinemaName] [DEDUPLIKACIJA] Preskačem '${key}' iz sekcije Uskoro jer već igra na repertoaru.")
            }
        }

        return filtriranaMapa.values.toList()
    }

    private fun parseMoviesFromDoc(doc: Document, isUpcoming: Boolean): List<ScrapedMovie> {
        val result = mutableListOf<ScrapedMovie>()
        val filmovi = doc.select("li.replist")

        for (film in filmovi) {
            val detaljiBlok = film.select("div.col-md-5")
            if (detaljiBlok.isEmpty()) continue

            val naslov = detaljiBlok.select("h3").text().trim()
            val originalniNaslovRaw = detaljiBlok.select("p").text().trim()
            var link = detaljiBlok.select("a").attr("href").trim()

            if (link.startsWith("/")) {
                link = baseUrl + link
            }

            val originalniNaslov = if (originalniNaslovRaw.isNotEmpty() && originalniNaslovRaw != naslov) {
                originalniNaslovRaw
            } else {
                null
            }

            if (naslov.isNotEmpty() && link.isNotEmpty()) {
                val tipLoga = if (isUpcoming) "USKORO" else "REPERTOAR"
                logger.info("[$cinemaName] [$tipLoga] Pronađen film: $naslov")

                result.add(
                    ScrapedMovie(
                        title = naslov,
                        originalTitle = originalniNaslov,
                        cinemaName = cinemaName,
                        bookingUrl = link,
                        cinemaLocation = fiksnaLokacija,
                        latitude = fiksniLat,
                        longitude = fiksniLon,
                        isUpcoming = isUpcoming
                    )
                )
            }
        }
        return result
    }
}
