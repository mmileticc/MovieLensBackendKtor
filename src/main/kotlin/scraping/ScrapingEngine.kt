// Deprecated sequential engine, replaced by ScrapingEngineConcurrent. Only used by Main.kt (a
// manual test) and safe to delete together with it.
package scraping


import com.milinko.scraping.model.ScrapedMovie
import com.milinko.scraping.scraper.CinemaScraper
import java.time.LocalDate
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ScrapingEngine")

/** Runs the scrapers one after another. Deprecated: use [ScrapingEngineConcurrent]. */
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

            sviNadjeniFilmovi.addAll(rezultati)
        }
        val ukupnoVreme = (System.currentTimeMillis() - startTimeEngine) / 1000
        logger.info("=== SVI BIOSKOPI SU USPEŠNO PROCESUIRANI ===")
        logger.info("Ukupno skupljeno filmova za bazu: ${sviNadjeniFilmovi.size}")
        logger.info("Ukupno vreme izvršavanja celog engine-a: $ukupnoVreme sekundi")
        return sviNadjeniFilmovi
    }
}