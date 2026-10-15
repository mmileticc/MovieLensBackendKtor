package com.milinko.scraping.scraper

/**
 * Roda Cineplex (Belgrade), rodacineplex.com - same company and platform as Arena Cineplex, one
 * fixed location. See [CineplexPlatformScraper].
 */
class RodaCineplexScraper : CineplexPlatformScraper(
    cinemaName = "Roda Cineplex",
    baseUrl = "http://www.rodacineplex.com",
    location = "RODA CINEPLEX",
    latitude = 44.77397506094518,
    longitude = 20.414585469313216,
)
