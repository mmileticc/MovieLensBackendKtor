package scraping

import com.milinko.scraping.model.ScrapedMovie
import com.milinko.scraping.scraper.CinemaScraper
import kotlinx.coroutines.*
import java.time.LocalDate
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ScrapingEngineConcurrent")

/**
 * Runs all scrapers in parallel (each on the IO dispatcher). A scraper that throws only loses its
 * own cinema; the others continue.
 */
class ScrapingEngineConcurrent(private val scrapers: List<CinemaScraper>) {

    /** Scrapes every cinema for [date] and returns all movies in one list. */
    suspend fun runAllScrapers(date: LocalDate = LocalDate.now()): List<ScrapedMovie> = coroutineScope {
        logger.info("=== POKRETANJE PARALELNOG GLOBALNOG SKRAPOVANJA FOR DATE: $date ===")
        val startTimeEngine = System.currentTimeMillis()

        val poslovi = scrapers.map { scraper ->
            async(Dispatchers.IO) {
                logger.info("[Engine] Pokrećem paralelnu nit za: ${scraper.cinemaName}")
                val startTimeScraper = System.currentTimeMillis()


                val rezultati = try {
                    scraper.scrape(date)
                } catch (e: Exception) {
                    logger.warn("[Engine] KRITIČNA greška u scraperu ${scraper.cinemaName} - ostali scraperi nastavljaju normalno: ${e.message}")
                    emptyList()
                }

                val duration = (System.currentTimeMillis() - startTimeScraper) / 1000
                logger.info("[Engine] Završio ${scraper.cinemaName} za $duration sekundi. Nađeno: ${rezultati.size}")

                rezultati
            }
        }


        val sveListe: List<List<ScrapedMovie>> = poslovi.awaitAll()

        val finalniFilmovi = sveListe.flatten()
        val ukupnoVreme = (System.currentTimeMillis() - startTimeEngine) / 1000
        

        logger.info("=== SVI BIOSKOPI SU USPEŠNO PARALELNO PROCESUIRANI ===")
        logger.info("Ukupno skupljeno filmova za bazu: ${finalniFilmovi.size}")
        logger.info("Ukupno vreme izvršavanja celog engine-a: $ukupnoVreme sekundi")

        return@coroutineScope finalniFilmovi
    }
}