package scraping


import com.milinko.scraping.model.ScrapedMovie
import com.milinko.scraping.scraper.CinemaScraper
import java.time.LocalDate

class ScrapingEngine(private val scrapers: List<CinemaScraper>) {

    fun runAllScrapers(date: LocalDate = LocalDate.now()): List<ScrapedMovie> {
        println("=== POKRETANJE GLOBALNOG SKRAPOVANJA ZA DATUM: $date ===")
        val startTimeEngine = System.currentTimeMillis()
        val sviNadjeniFilmovi = mutableListOf<ScrapedMovie>()

        for (scraper in scrapers) {
            println("\n>>> Pokrećem: ${scraper.cinemaName} <<<")

            val startTime = System.currentTimeMillis()
            val rezultati = scraper.scrape(date)
            val duration = (System.currentTimeMillis() - startTime) / 1000

            println("--- Završeno za ${scraper.cinemaName} ---")
            println("Ukupno pronađeno filmova: ${rezultati.size}")
            println("Vreme izvršavanja: $duration sekundi")

            // ISPIS REZULTATA (Sutra ovde ide poziv Database servisa: database.save(rezultati))
//            rezultati.forEach { film ->
//                println("[FILM] [${if(film.isUpcoming) "UPCOMING" else "ACTIVE"}] ${film.title} (${film.originalTitle ?: "Nema originalnog"}) Lokacija ${film.cinemaLocation} -> ${film.bookingUrl}")
//            }
            sviNadjeniFilmovi.addAll(rezultati)
        }
        val ukupnoVreme = (System.currentTimeMillis() - startTimeEngine) / 1000
        println("\n=== SVI BIOSKOPI SU USPEŠNO PROCESUIRANI ===")
        println("Ukupno skupljeno filmova za bazu: ${sviNadjeniFilmovi.size}")
        println("Ukupno vreme izvršavanja celog engine-a: $ukupnoVreme sekundi")
        return sviNadjeniFilmovi
    }
}