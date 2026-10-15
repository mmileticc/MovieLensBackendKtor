package com.milinko.scraping.scraper

/** Arena Cineplex (Novi Sad), arenacineplex.com - one fixed location. See [CineplexPlatformScraper]. */
class ArenaCineplexScraper : CineplexPlatformScraper(
    cinemaName = "Arena Cineplex",
    baseUrl = "http://www.arenacineplex.com",
    location = "Arena Cineplex, Bulevar Mihajla Pupina 3, Novi Sad",
    latitude = 45.253912026035266,
    longitude = 19.845361912039472,
)
