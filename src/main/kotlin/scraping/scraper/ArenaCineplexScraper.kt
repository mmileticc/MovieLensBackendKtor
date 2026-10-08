package com.milinko.scraping.scraper

import com.milinko.scraping.agent.ScrapingConfig.MOVIE_LENS_USER_AGENT
import com.milinko.scraping.model.ScrapedMovie
import org.jsoup.Jsoup
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ArenaCineplexScraper")


class ArenaCineplexScraper : CinemaScraper {
    override val cinemaName = "Arena Cineplex"
    private val baseUrl = "http://www.arenacineplex.com"
    private val fiksnaLokacija = "Arena Cineplex, Bulevar Mihajla Pupina 3, Novi Sad"
    private val fiksniLat = 45.253912026035266
    private val fiksniLon = 19.845361912039472

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

            tekuciFilmovi.addAll(parseMoviesFromDoc(docRepertoar, isUpcoming = false))
        } catch (e: IOException) {
            logger.warn("[$cinemaName] Greška pri skrapovanju repertoara: ${e.message}")
        }

        // 2. Skrapovanje sekcije "Uskoro" (isUpcoming = true)
        try {
            val docUskoro = Jsoup.connect(urlUskoro)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(10000)
                .get()

            upcomingFilmovi.addAll(parseMoviesFromDoc(docUskoro, isUpcoming = true))
        } catch (e: IOException) {
            logger.warn("[$cinemaName] Greška pri skrapovanju sekcije Uskoro: ${e.message}")
        }

        // 3. Interna deduplikacija unutar ovog bioskopa
        // Prvo ubacujemo tekuće filmove jer oni imaju prioritet (isUpcoming = false)
        val filtriranaMapa = tekuciFilmovi.associateBy { it.originalTitle ?: it.title }.toMutableMap()

        // Dodajemo upcoming filmove SAMO ako već ne postoje na repertoaru za taj dan
        for (upcomingIno in upcomingFilmovi) {
            val key = upcomingIno.originalTitle ?: upcomingIno.title
            if (!filtriranaMapa.containsKey(key)) {
                filtriranaMapa[key] = upcomingIno
            } else {
                logger.info("[$cinemaName] [DEDUPLIKACIJA] Preskačem '${key}' iz sekcije Uskoro jer već igra na repertoaru.")
            }
        }

        // Vraćamo čistu listu za ovaj bioskop
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