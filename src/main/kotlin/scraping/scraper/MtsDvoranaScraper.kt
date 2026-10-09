package com.milinko.scraping.scraper

import com.milinko.scraping.agent.ScrapingConfig.MOVIE_LENS_USER_AGENT
import com.milinko.scraping.model.ScrapedMovie
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.time.LocalDate
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("MtsDvoranaScraper")

/**
 * mts Dvorana (Belgrade), mtsdvorana.rs. The home page lists the current programme and
 * `/filmovi/uskoro` the coming-soon movies; each movie's own page provides the original title.
 */
class MtsDvoranaScraper : CinemaScraper {
    override val cinemaName = "MTS Dvorana"
    private val baseUrl = "https://www.mtsdvorana.rs"
    private val fiksnaLokacija = "MTS Dvorana, Trg Nikole Pašića"
    private val fiksniLat = 44.81348329306508
    private val fiksniLon = 20.46304171756712

    private val mojUserAgent = MOVIE_LENS_USER_AGENT

    override fun scrape(date: LocalDate): List<ScrapedMovie> {
        val tekuciFilmovi = mutableListOf<ScrapedMovie>()
        val upcomingFilmovi = mutableListOf<ScrapedMovie>()

        val urlRepertoar = "$baseUrl/"
        val urlUskoro = "$baseUrl/filmovi/uskoro"

        // 1. Current programme
        try {
            val docRepertoar = Jsoup.connect(urlRepertoar)
                .userAgent(mojUserAgent)
                .timeout(10000)
                .get()

            tekuciFilmovi.addAll(parseMoviesFromDoc(docRepertoar, isUpcoming = false, vecSkrapovaniLinkovi = emptySet()))
        } catch (e: IOException) {
            logger.warn("[$cinemaName] Greška pri skrapovanju glavne stranice repertoara: ${e.message}")
        }

        // Links already in the programme, so their pages are not fetched again for "coming soon".
        val skrapovaniLinkoviNaRepertoaru = tekuciFilmovi.map { it.bookingUrl }.toSet()

        // 2. "Coming soon" section
        try {
            val docUskoro = Jsoup.connect(urlUskoro)
                .userAgent(mojUserAgent)
                .timeout(10000)
                .get()

            upcomingFilmovi.addAll(parseMoviesFromDoc(docUskoro, isUpcoming = true, vecSkrapovaniLinkovi = skrapovaniLinkoviNaRepertoaru))
        } catch (e: IOException) {
            logger.warn("[$cinemaName] Greška pri skrapovanju sekcije Uskoro: ${e.message}")
        }

        // 3. De-duplicate by originalTitle (or title when there is none); the current programme wins.
        val filtriranaMapa = tekuciFilmovi.associateBy { it.originalTitle ?: it.title }.toMutableMap()

        for (upcomingIno in upcomingFilmovi) {
            val kljuc = upcomingIno.originalTitle ?: upcomingIno.title
            if (!filtriranaMapa.containsKey(kljuc)) {
                filtriranaMapa[kljuc] = upcomingIno
            } else {
                logger.info("[$cinemaName] [DEDUPLIKACIJA] Preskačem '${upcomingIno.title}' iz sekcije Uskoro jer već postoji na repertoaru.")
            }
        }

        return filtriranaMapa.values.toList()
    }

    /**
     * The site is a Nuxt + Tailwind app, but the movie cards are server-rendered, so Jsoup is
     * enough: `<a href="/film/<slug>" class="group block"><img/><h3>Title</h3><p>Genre</p></a>`.
     * The home page also lists events ("/dogadjaj/..."), so only "/film/" links are kept.
     */
    private fun parseMoviesFromDoc(doc: Document, isUpcoming: Boolean, vecSkrapovaniLinkovi: Set<String>): List<ScrapedMovie> {
        val result = mutableListOf<ScrapedMovie>()
        val filmovi = doc.select("a[href^=/film/]:has(h3)").distinctBy { it.attr("href") }

        for (film in filmovi) {
            val naslov = film.select("h3").first()?.text()?.trim().orEmpty()
            var link = film.attr("href").trim()

            if (link.startsWith("/")) {
                link = baseUrl + link
            }

            if (naslov.isNotEmpty() && link.isNotEmpty()) {
                val tipLoga = if (isUpcoming) "USKORO" else "REPERTOAR"

                // A coming-soon movie whose link is already in the programme is a duplicate - skip
                // fetching its page.
                if (isUpcoming && vecSkrapovaniLinkovi.contains(link)) {
                    logger.info("[$cinemaName] [$tipLoga] Preskačem mrežni zahtev za: $naslov (već skinut kroz repertoar)")
                    continue
                }

                val nasumicnaPauza = (500..1500).random().toLong()
                Thread.sleep(nasumicnaPauza)

                logger.info("[$cinemaName] [$tipLoga] Obrada filma: $naslov")

                // Fetch the movie's own page only when needed (original title).
                val originalniNaslov = scrapeOriginalTitle(link)

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

    private fun scrapeOriginalTitle(urlFilma: String): String? {
        return try {
            val doc = Jsoup.connect(urlFilma)
                .userAgent(mojUserAgent)
                .timeout(5000)
                .get()

            // Same "hf-*" platform as Cine Grand: the original title is in ".hf-original" (present
            // twice because of the mobile and desktop layouts, hence first()).
            val originalTitle = doc.select(".hf-original").first()?.text()?.trim().orEmpty()
            originalTitle.ifEmpty { null }
        } catch (e: IOException) {
            logger.warn("[$cinemaName] Greška pri učitavanju stranice filma: $urlFilma -> ${e.message}")
            null
        }
    }
}