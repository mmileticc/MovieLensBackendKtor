package com.milinko.scraping.agent

/** Settings shared by all scrapers. */
object ScrapingConfig {

    /** How many days of showtimes are collected, starting with the scraped date. */
    const val SHOWTIME_DAYS = 7

    /** Showtimes are local times in Belgrade. */
    val ZONE: java.time.ZoneId = java.time.ZoneId.of("Europe/Belgrade")

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36 " +
            "MovieneeScrapers/1.0 (+https://github.com/mmileticc/movienee-scrapers)"

    /**
     * User-Agent sent by every scraper: a regular browser string (some sites block unknown agents)
     * followed by the project name and a link, so site administrators can see who is scraping.
     *
     * Set the `SCRAPER_USER_AGENT` environment variable to use your own, ideally with your own
     * contact address.
     */
    val USER_AGENT: String = System.getenv("SCRAPER_USER_AGENT")?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT
}
