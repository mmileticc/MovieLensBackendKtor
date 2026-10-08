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
 * tuck.rs (Tuckwood Cineplex, Kneza Miloša 7a, Beograd) je klasičan server-renderovan WordPress
 * sajt (WPBakery Page Builder + "Amy Movie" plugin za repertoar) - potvrđeno preko
 * scraper-debug/tuckwoodprobe_*.html snimaka (TuckwoodProbe.kt). Kartica filma je već u
 * inicijalnom HTML-u (ne treba JS/hidratacija), pa - kao Arena/Roda Cineplex - koristimo prost
 * Jsoup.connect() umesto Playwright-a (lakše, brže, ne treba browser).
 *
 * Struktura kartice (".amy-movie-item"):
 * - naslov je UVEK oblika "Srpski naslov / Originalni naslov" (npr. "Blue Lock / Burû Rokku",
 *   "Poslednji Dani ulice Hrastova / The End of Oak Street") unutar "h3.amy-movie-field-title a" -
 *   razdvajamo na " / " (razmak-kosa crta-razmak) da izbegnemo pogrešno sečenje eventualnog naslova
 *   koji sadrži "/" bez razmaka oko njega.
 * - link ka detalju filma je isti href kao na naslovu/posteru (npr. "/amy_movie/blue-lock-.../").
 *
 * Jedna fiksna lokacija (kao Arena/Roda) - nema potrebe za LokacijeBioskopa lookup-om.
 */
class TuckwoodCineplexScraper : CinemaScraper {
    override val cinemaName = "Tuckwood Cineplex"
    private val baseUrl = "https://www.tuck.rs"
    private val fiksnaLokacija = "TUCKWOOD CINEPLEX"
    // Placeholder (isti dogovor kao za ostale bioskope) - korisnik ubacuje tačne koordinate za
    // Kneza Miloša 7a, Beograd.
    private val fiksniLat = 44.80984832581882
    private val fiksniLon = 20.465467427357588

    private val mojUserAgent = MOVIE_LENS_USER_AGENT

    private val timeoutMs = (System.getenv("TUCKWOOD_TIMEOUT_MS")?.toIntOrNull() ?: 25000)

    override fun scrape(date: LocalDate): List<ScrapedMovie> {
        val tekuciFilmovi = mutableListOf<ScrapedMovie>()
        val upcomingFilmovi = mutableListOf<ScrapedMovie>()

        val urlRepertoar = "$baseUrl/repertoar/"
        val urlUskoro = "$baseUrl/uskoro/"

        // 1. Skrapovanje trenutnog repertoara (isUpcoming = false)
        val docRepertoar = fetchDocSaPokusajima(urlRepertoar, "REPERTOAR")
        if (docRepertoar != null) {
            val filmoviSaRepertoara = parseMoviesFromDoc(docRepertoar, isUpcoming = false)
            if (filmoviSaRepertoara.isEmpty()) {
                logger.info("[$cinemaName] REPERTOAR vratio 0 filmova - snimam HTML za proveru selektora.")
            }
            tekuciFilmovi.addAll(filmoviSaRepertoara)
        }

        // 2. Skrapovanje sekcije "Uskoro" (isUpcoming = true)
        val docUskoro = fetchDocSaPokusajima(urlUskoro, "USKORO")
        if (docUskoro != null) {
            upcomingFilmovi.addAll(parseMoviesFromDoc(docUskoro, isUpcoming = true))
        }

        // 3. Interna deduplikacija unutar ovog bioskopa (isti obrazac kao Arena/Roda Cineplex)
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
     * Jsoup.connect().get() sa retry-em (2 pokušaja) - jednokratni "Read timed out" na sporijem
     * serveru kao tuck.rs ne treba da znači da ceo ciklus za ovaj bioskop ostaje bez podataka
     * (isti princip kao `scrapeDeepDetails` retry u CineGrandScraper-u). Vraća null tek ako i
     * drugi pokušaj padne - poziv iznad tad jednostavno preskače tu sekciju (repertoar/uskoro)
     * umesto da baci izuzetak.
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
