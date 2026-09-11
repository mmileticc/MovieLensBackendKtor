package com.milinko.scraping
import com.milinko.scraping.scraper.ArenaCineplexScraper
import com.milinko.scraping.scraper.CineGrandScraper
import com.milinko.scraping.scraper.CineplexxScraper
import com.milinko.scraping.scraper.MtsDvoranaScraper
import scraping.ScrapingEngine

import java.time.LocalDate

@Deprecated("Zamenjeno sa MainConcurrent.kt / ScrapingEngineConcurrent. Ne koristi se u produkciji.")
fun main() {
    System.setOut(java.io.PrintStream(System.out, true, "UTF-8"))
    System.setErr(java.io.PrintStream(System.err, true, "UTF-8"))
    println("=== INICIJALIZACIJA SISTEMA ZA SKRAPOVANJE ===")

    val aktivniScraperi = listOf(
        MtsDvoranaScraper(),
        CineplexxScraper(),
        ArenaCineplexScraper(),
        CineGrandScraper()
    )

    val engine = ScrapingEngine(aktivniScraperi)

    val danasnjiDatum = LocalDate.now()
    val listOfAllMovies =  engine.runAllScrapers(danasnjiDatum)
    listOfAllMovies.forEach { film ->
        println("[FILM] [${if(film.isUpcoming) "UPCOMING" else "ACTIVE"}] ${film.title} (${film.originalTitle ?: "Nema originalnog"}) Lokacija ${film.cinemaLocation} -> ${film.bookingUrl}")
    }
    println("\n=== PROGRAM JE USPEŠNO ZAVRŠIO RAD ===")
}