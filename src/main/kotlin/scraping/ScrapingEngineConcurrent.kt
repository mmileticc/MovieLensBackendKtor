package com.milinko.scraping

import com.milinko.scraping.model.ScrapedMovie
import com.milinko.scraping.scraper.CinemaScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.time.LocalDate

private val logger = LoggerFactory.getLogger("ScrapingEngineConcurrent")

/**
 * Runs all scrapers in parallel (each on the IO dispatcher). A scraper that throws only loses its
 * own cinema; the others continue.
 */
class ScrapingEngineConcurrent(private val scrapers: List<CinemaScraper>) {

    /** Scrapes every cinema for [date] and returns all movies in one list. */
    suspend fun runAllScrapers(date: LocalDate = LocalDate.now()): List<ScrapedMovie> = coroutineScope {
        logger.info("=== Starting parallel scraping for $date ===")
        val engineStart = System.currentTimeMillis()

        val jobs = scrapers.map { scraper ->
            async(Dispatchers.IO) {
                logger.info("[Engine] Starting ${scraper.cinemaName}")
                val scraperStart = System.currentTimeMillis()

                val results = try {
                    scraper.scrape(date)
                } catch (e: Exception) {
                    logger.warn("[Engine] ${scraper.cinemaName} failed, the other scrapers continue: ${e.message}")
                    emptyList()
                }

                val seconds = (System.currentTimeMillis() - scraperStart) / 1000
                logger.info("[Engine] Finished ${scraper.cinemaName} in ${seconds}s. Found: ${results.size}")
                results
            }
        }

        val movies = jobs.awaitAll().flatten()
        val totalSeconds = (System.currentTimeMillis() - engineStart) / 1000

        logger.info("=== All cinemas processed: ${movies.size} movies in ${totalSeconds}s ===")
        movies
    }
}
