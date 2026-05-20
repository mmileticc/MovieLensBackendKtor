package com.milinko.scraping.scraper

import com.milinko.scraping.model.ScrapedMovie
import java.time.LocalDate

interface CinemaScraper {
    val cinemaName: String

    /**
     * Skrapuje bioskop za prosleđeni datum i vraća listu uniformnih modela filmova.
     */
    fun scrape(date: LocalDate): List<ScrapedMovie>
}