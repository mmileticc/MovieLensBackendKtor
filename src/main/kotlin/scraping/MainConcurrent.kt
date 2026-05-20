package com.milinko.scraping
import com.milinko.scraping.scraper.ArenaCineplexScraper
import com.milinko.scraping.scraper.CineplexxScraper
import com.milinko.scraping.scraper.MtsDvoranaScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import scraping.ScrapingEngineConcurrent
import java.io.PrintStream
import java.time.LocalDate

suspend fun main() {
    System.setOut(withContext(Dispatchers.IO) {
        PrintStream(System.out, true, "UTF-8")
    })
    System.setErr(withContext(Dispatchers.IO) {
        PrintStream(System.err, true, "UTF-8")
    })
    println("=== INICIJALIZACIJA SISTEMA ZA SKRAPOVANJE ===")

    val aktivniScraperi = listOf(
        MtsDvoranaScraper(),
        CineplexxScraper(),
        ArenaCineplexScraper()
    )

    val engine = ScrapingEngineConcurrent(aktivniScraperi)

    val danasnjiDatum = LocalDate.now()
    val listOfAllMovies = engine.runAllScrapers(danasnjiDatum)
    listOfAllMovies.forEach { film ->
            println("[FILM] [${if (film.isUpcoming) "UPCOMING" else "ACTIVE"}] ${film.title} | (${film.originalTitle ?: "Nema originalnog"}) Lokacija ${film.cinemaLocation} -> ${film.bookingUrl}")
        }
    println("\n=== PROGRAM JE USPEŠNO ZAVRŠIO RAD ===")
}