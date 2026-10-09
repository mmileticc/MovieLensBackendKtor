package com.milinko.scraping.agent

/** Settings shared by all scrapers. */
object ScrapingConfig {

    /**
     * User-Agent sent by every scraper: a regular browser string (some sites block unknown agents)
     * followed by the project name, purpose and a contact address, so site administrators can see
     * who is scraping and why.
     */
    const val MOVIE_LENS_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 " +
            "(MovieLensBot; Studentski projekat; kontakt: milinko.miletic.dev@gmail.com; Svi linkovi unutar aplikacije vode direktno na vasu stranicu za kupovinu karata. Generisem vam besplatan saobracaj )"
}