package com.milinko.scraping.scraper

import com.milinko.scraping.model.ScrapedMovie
import java.time.LocalDate

/** One cinema chain's website. Implementations must not throw for a single failing page. */
interface CinemaScraper {
    /** Chain name used in the results and logs (e.g. "Cineplexx"). */
    val cinemaName: String

    /** Scrapes the programme for [date] and returns it as uniform [ScrapedMovie]s. */
    fun scrape(date: LocalDate): List<ScrapedMovie>
}