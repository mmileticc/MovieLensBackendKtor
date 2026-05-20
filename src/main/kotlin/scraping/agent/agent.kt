package com.milinko.scraping.agent

object ScrapingConfig {

    /**
     * Zvanični, etički potpisani User-Agent za sve skrapere unutar MovieLens sistema.
     * Sadrži informacije o projektu, vlasniku i nameri radi transparentnosti prema administratorima sajtova.
     */
    const val MOVIE_LENS_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 " +
            "(MovieLensBot; Studentski projekat; kontakt: milinko.miletic03@gmail.com; Svi linkovi unutar aplikacije vode direktno na vasu stranicu za kupovinu karata. Generisem vam besplatan saobracaj )"
}