package scraping

import com.milinko.scraping.model.ScrapedMovie
import com.milinko.scraping.scraper.CinemaScraper
import kotlinx.coroutines.*
import java.time.LocalDate

class ScrapingEngineConcurrent(private val scrapers: List<CinemaScraper>) {

    // Dodajemo 'suspend' ključnu reč jer funkcija sada koristi korutine
    suspend fun runAllScrapers(date: LocalDate = LocalDate.now()): List<ScrapedMovie> = coroutineScope {
        println("=== POKRETANJE PARALELNOG GLOBALNOG SKRAPOVANJA FOR DATE: $date ===")
        val startTimeEngine = System.currentTimeMillis()

        // 1. Pokrećemo sve scrapere paralelno na IO nitima
        val poslovi = scrapers.map { scraper ->
            // async pokreće korutinu u pozadini i vraća "obećanje" (Deferred) da će dati rezultat
            async(Dispatchers.IO) {
                println("\n[Engine] Pokrećem paralelnu nit za: ${scraper.cinemaName}")
                val startTimeScraper = System.currentTimeMillis()

                // Izvršava se scraping za konkretan bioskop
                val rezultati = scraper.scrape(date)

                val duration = (System.currentTimeMillis() - startTimeScraper) / 1000
                println("[Engine] Završio ${scraper.cinemaName} za $duration sekundi. Nađeno: ${rezultati.size}")

                // Vraćamo listu iz async bloka
                rezultati
            }
        }

        // 2. ČEKANJE I BEZBEDNO SPAJANJE (Konkurentnost rešena na jeziku samog algoritma)
        // .awaitAll() blokira trenutnu korutinu dok svi scraperi ne završe.
        // Dobijamo listu listi: List<List<ScrapedMovie>>
        val sveListe: List<List<ScrapedMovie>> = poslovi.awaitAll()

        // .flatten() spaja sve pojedinačne liste u jednu veliku ravnu listu.
        // Pošto se ovo dešava sekvencijalno NAKON što su sve niti završile,
        // nema nikakve šanse za race condition ili threading greške!
        val finalniFilmovi = sveListe.flatten()
        val ukupnoVreme = (System.currentTimeMillis() - startTimeEngine) / 1000

//        finalniFilmovi.forEach { film ->
//            println("[FILM] [${if (film.isUpcoming) "UPCOMING" else "ACTIVE"}] ${film.title} (${film.originalTitle ?: "Nema originalnog"}) Lokacija ${film.cinemaLocation} -> ${film.bookingUrl}")
//        }

        println("\n=== SVI BIOSKOPI SU USPEŠNO PARALELNO PROCESUIRANI ===")
        println("Ukupno skupljeno filmova za bazu: ${finalniFilmovi.size}")
        println("Ukupno vreme izvršavanja celog engine-a: $ukupnoVreme sekundi")

        return@coroutineScope finalniFilmovi
    }
}