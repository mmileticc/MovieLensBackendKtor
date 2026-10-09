package com.milinko.scraping.scraper

import com.milinko.scraping.agent.ScrapingConfig.MOVIE_LENS_USER_AGENT
import com.milinko.scraping.model.ScrapedMovie
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.time.LocalDate
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("TuckwoodCineplexScraper")

/**
 * Tuckwood Cineplex (Kneza Miloša 7a, Belgrade), tuck.rs: a server-rendered WordPress site
 * (WPBakery + the "Amy Movie" plugin), so - like Arena/Roda - plain Jsoup is enough.
 *
 * Movie cards (".amy-movie-item"):
 * - the title is always "Serbian title / Original title" in "h3.amy-movie-field-title a"; it is
 *   split on " / " (with spaces) so a title containing a bare "/" is not cut;
 * - the link to the movie page is the same href as on the title/poster ("/amy_movie/.../").
 *
 * One fixed location.
 */
class TuckwoodCineplexScraper : CinemaScraper {
    override val cinemaName = "Tuckwood Cineplex"
    private val baseUrl = "https://www.tuck.rs"
    private val fiksnaLokacija = "TUCKWOOD CINEPLEX"
    // Approximate coordinates of Kneza Miloša 7a, Belgrade.
    private val fiksniLat = 44.80984832581882
    private val fiksniLon = 20.465467427357588

    private val mojUserAgent = MOVIE_LENS_USER_AGENT

    private val timeoutMs = (System.getenv("TUCKWOOD_TIMEOUT_MS")?.toIntOrNull() ?: 25000)

    override fun scrape(date: LocalDate): List<ScrapedMovie> {
        val tekuciFilmovi = mutableListOf<ScrapedMovie>()
        val upcomingFilmovi = mutableListOf<ScrapedMovie>()

        val urlRepertoar = "$baseUrl/repertoar/"
        val urlUskoro = "$baseUrl/uskoro/"

        // 1. Current programme (isUpcoming = false)
        val docRepertoar = fetchDocSaPokusajima(urlRepertoar, "REPERTOAR")
        if (docRepertoar != null) {
            val filmoviSaRepertoara = parseMoviesFromDoc(docRepertoar, isUpcoming = false)
            if (filmoviSaRepertoara.isEmpty()) {
                logger.info("[$cinemaName] REPERTOAR vratio 0 filmova - snimam HTML za proveru selektora.")
            }
            tekuciFilmovi.addAll(filmoviSaRepertoara)
        }

        // 2. "Coming soon" section (isUpcoming = true)
        val docUskoro = fetchDocSaPokusajima(urlUskoro, "USKORO")
        if (docUskoro != null) {
            upcomingFilmovi.addAll(parseMoviesFromDoc(docUskoro, isUpcoming = true))
        }

        // 3. De-duplicate within this cinema (same as Arena/Roda Cineplex).
        val filtriranaMapa = tekuciFilmovi.associateBy { it.originalTitle ?: it.title }.toMutableMap()

        for (upcomingIno in upcomingFilmovi) {
            val key = upcomingIno.originalTitle ?: upcomingIno.title
            if (!filtriranaMapa.containsKey(key)) {
                filtriranaMapa[key] = upcomingIno
            } else {
                logger.info("[$cinemaName] [DEDUPLIKACIJA] Preskačem '$key' iz sekcije Uskoro jer već igra na repertoaru.")
            }
        }

        return filtriranaMapa.values.toList()
    }

    /**
     * Jsoup GET with one retry: a single "Read timed out" on this slower server should not leave
     * the cinema without data for the whole day. Returns null only if both attempts fail; the
     * caller then skips that section instead of throwing.
     */
    private fun fetchDocSaPokusajima(url: String, oznakaSekcije: String): Document? {
        val maxPokusaja = 2

        repeat(maxPokusaja) { index ->
            val pokusaj = index + 1
            try {
                return Jsoup.connect(url)
                    .userAgent(mojUserAgent)
                    .timeout(timeoutMs)
                    .get()
            } catch (e: IOException) {
                val sufiks = if (pokusaj < maxPokusaja) " - pokušavam ponovo" else ""
                logger.warn("[$cinemaName] [$oznakaSekcije] Greška pri skrapovanju (pokušaj $pokusaj/$maxPokusaja, timeout ${timeoutMs}ms): ${e.message}$sufiks")
            }
        }

        return null
    }

    private fun parseMoviesFromDoc(doc: Document, isUpcoming: Boolean): List<ScrapedMovie> {
        val result = mutableListOf<ScrapedMovie>()
        val filmovi = doc.select(".amy-movie-item")

        for (film in filmovi) {
            val naslovLink = film.select("h3.amy-movie-field-title a").first()
            val naslovPun = naslovLink?.text()?.trim().orEmpty()
            var link = naslovLink?.attr("href")?.trim().orEmpty()

            if (link.isEmpty()) {
                link = film.select(".amy-movie-item-poster a").first()?.attr("href")?.trim().orEmpty()
            }

            if (naslovPun.isEmpty() || link.isEmpty()) continue

            val naslov = naslovPun.substringBeforeLast(" / ").trim()
            val originalniNaslov = naslovPun.substringAfterLast(" / ").trim()
                .takeIf { it.isNotEmpty() && it != naslov }

            if (naslov.isEmpty()) continue

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
        return result
    }
}
