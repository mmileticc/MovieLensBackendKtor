// DEPRECATED / NEKORIŠĆENO: sekvencijalna verzija engine-a, zamenjena sa ScrapingEngineConcurrent
// (koji se koristi u Application.kt/configureScrapingLifecycle i MainConcurrent.kt). Ostavljeno
// samo zato što nije bilo moguće obrisati fajl u ovoj sesiji — bezbedno za ručno brisanje.
package scraping


import com.milinko.scraping.model.ScrapedMovie
import com.milinko.scraping.scraper.CinemaScraper
import java.time.LocalDate
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ScrapingEngine")

@Deprecated("Zamenjeno sa ScrapingEngineConcurrent. Ne koristi se u produkciji.")
class ScrapingEngine(private val scrapers: List<CinemaScraper>) {

    fun runAllScrapers(date: LocalDate = LocalDate.now()): List<ScrapedMovie> {
        logger.info("=== POKRETANJE GLOBALNOG SKRAPOVANJA ZA DATUM: $date ===")
        val startTimeEngine = System.currentTimeMillis()
        val sviNadjeniFilmovi = mutableListOf<ScrapedMovie>()

        for (scraper in scrapers) {
            logger.info(">>> Pokrećem: ${scraper.cinemaName} <<<")

            val startTime = System.currentTimeMillis()
            val rezultati = scraper.scrape(date)
            val duration = (System.currentTimeMillis() - startTime) / 1000

            logger.info("--- Završeno za ${scraper.cinemaName} ---")
            logger.info("Ukupno pronađeno filmova: ${rezultati.size}")
            logger.info("Vreme izvršavanja: $duration sekundi")

            // ISPIS REZULTATA (Sutra ovde ide poziv Database servisa: database.save(rezultati))
//            rezultati.forEach { film ->
//                println("[FILM] [${if(film.isUpcoming) "UPCOMING" else "ACTIVE"}] ${film.title} (${film.originalTitle ?: "Nema originalnog"}) Lokacija ${film.cinemaLocation} -> ${film.bookingUrl}")
//            }
            sviNadjeniFilmovi.addAll(rezultati)
        }
        val ukupnoVreme = (System.currentTimeMillis() - startTimeEngine) / 1000
        logger.info("=== SVI BIOSKOPI SU USPEŠNO PROCESUIRANI ===")
        logger.info("Ukupno skupljeno filmova za bazu: ${sviNadjeniFilmovi.size}")
        logger.info("Ukupno vreme izvršavanja celog engine-a: $ukupnoVreme sekundi")
        return sviNadjeniFilmovi
    }
}