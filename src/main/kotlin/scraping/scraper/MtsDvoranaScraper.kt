package com.milinko.scraping.scraper

import com.milinko.scraping.agent.ScrapingConfig.MOVIE_LENS_USER_AGENT
import com.milinko.scraping.model.ScrapedMovie
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.time.LocalDate

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

        // 1. Skrapovanje trenutnog repertoara
        try {
            val docRepertoar = Jsoup.connect(urlRepertoar)
                .userAgent(mojUserAgent)
                .timeout(10000)
                .get()

            tekuciFilmovi.addAll(parseMoviesFromDoc(docRepertoar, isUpcoming = false, vecSkrapovaniLinkovi = emptySet()))
        } catch (e: IOException) {
            println("[$cinemaName] Greška pri skrapovanju glavne stranice repertoara: ${e.message}")
        }

        // Pravimo skup linkova koji već igraju na repertoaru da bismo preskočili njihovo ponovno skrapovanje unutar "Uskoro"
        val skrapovaniLinkoviNaRepertoaru = tekuciFilmovi.map { it.bookingUrl }.toSet()

        // 2. Skrapovanje sekcije "Uskoro"
        try {
            val docUskoro = Jsoup.connect(urlUskoro)
                .userAgent(mojUserAgent)
                .timeout(10000)
                .get()

            upcomingFilmovi.addAll(parseMoviesFromDoc(docUskoro, isUpcoming = true, vecSkrapovaniLinkovi = skrapovaniLinkoviNaRepertoaru))
        } catch (e: IOException) {
            println("[$cinemaName] Greška pri skrapovanju sekcije Uskoro: ${e.message}")
        }

        // 3. Interna deduplikacija pomoću originalTitle (ili title ako je originalTitle null)
        // Tekući filmovi imaju apsolutni prioritet
        val filtriranaMapa = tekuciFilmovi.associateBy { it.originalTitle ?: it.title }.toMutableMap()

        for (upcomingIno in upcomingFilmovi) {
            val kljuc = upcomingIno.originalTitle ?: upcomingIno.title
            if (!filtriranaMapa.containsKey(kljuc)) {
                filtriranaMapa[kljuc] = upcomingIno
            } else {
                println("[$cinemaName] [DEDUPLIKACIJA] Preskačem '${upcomingIno.title}' iz sekcije Uskoro jer već postoji na repertoaru.")
            }
        }

        return filtriranaMapa.values.toList()
    }

    /**
     * mtsdvorana.rs je redizajniran (oktobar 2026) u Nuxt + Tailwind - stare ".movie-item"
     * kartice više ne postoje. Kartice su i dalje server-renderovane (Jsoup je dovoljan):
     * `<a href="/film/<slug>" class="group block"><img/><h3>Naslov</h3><p>Žanr</p></a>`.
     * Početna strana u istom formatu prikazuje i događaje ("/dogadjaj/..."), pa filtriramo
     * isključivo linkove ka "/film/". Ista kartica se ne ponavlja, ali distinct ostaje radi sigurnosti.
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

                // Pametna provera: Ako film iz "Uskoro" sekcije ima link koji smo VEĆ skrapovali na repertoaru,
                // preskačemo otvaranje njegove stranice jer je on stoprocentni duplikat.
                if (isUpcoming && vecSkrapovaniLinkovi.contains(link)) {
                    println("[$cinemaName] [$tipLoga] Preskačem mrežni zahtev za: $naslov (već skinut kroz repertoar)")
                    continue
                }

                val nasumicnaPauza = (500..1500).random().toLong()
                Thread.sleep(nasumicnaPauza)

                println("[$cinemaName] [$tipLoga] Obrada filma: $naslov")

                // Otvaramo pojedinačnu stranicu filma samo ako je potrebno
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

            // Novi sajt (ista "hf-*" platforma kao Cine Grand) - originalni naslov je u ".hf-original"
            // (pojavljuje se 2x zbog mobilnog/desktop layout-a, zato first()).
            val originalTitle = doc.select(".hf-original").first()?.text()?.trim().orEmpty()
            originalTitle.ifEmpty { null }
        } catch (e: IOException) {
            println("[$cinemaName] Greška pri učitavanju stranice filma: $urlFilma -> ${e.message}")
            null
        }
    }
}